package com.rkacast.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rkacast.MainActivity
import com.rkacast.R
import com.rkacast.cast.CastManager
import com.rkacast.data.MediaUtils
import com.rkacast.playback.CastMediaSession
import com.rkacast.torrent.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service active while casting: keeps the phone's HTTP server / torrent engine alive with
 * the screen off, and shows a media notification (with lock-screen controls and TV volume via the
 * phone's volume buttons).
 */
class StreamService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null
    private var fallbackText = "Streaming to your TV"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CastManager.stopMedia()
                TorrentEngine.stopAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> CastManager.togglePlayPause()
            ACTION_REWIND -> CastManager.seekBy(-10_000)
            ACTION_FORWARD -> CastManager.seekBy(30_000)
        }
        intent?.getStringExtra(EXTRA_TEXT)?.let { fallbackText = it }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        LocalMediaServer.ensureStarted()
        acquireLocks()
        if (observer == null) {
            observer = scope.launch {
                combine(CastManager.state, CastManager.selected, TorrentEngine.status) { a, b, c -> Triple(a, b, c) }
                    .collect { (state, device, _) ->
                        CastMediaSession.update(this@StreamService, state, device)
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                    }
            }
        }
        return START_STICKY
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RKACast:stream").apply {
                    setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
                }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RKACast:wifi").apply {
                    setReferenceCounted(false); acquire()
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        CastMediaSession.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun action(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, StreamService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val state = CastManager.state.value
        val device = CastManager.selected.value
        val torrent = TorrentEngine.status.value
        val title = state.media?.title ?: fallbackText
        val text = buildString {
            append(device?.name ?: "RKACast")
            if (state.status.isNotBlank()) append(" · ").append(state.status)
            torrent?.let { append(" · ${it.peers} peers · ${MediaUtils.formatBytes(it.downloadRate)}/s") }
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (state.media != null) {
            b.addAction(android.R.drawable.ic_media_rew, "Back 10s", action(ACTION_REWIND, 2))
            b.addAction(
                if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.isPlaying) "Pause" else "Play", action(ACTION_TOGGLE, 3),
            )
            b.addAction(android.R.drawable.ic_media_ff, "Forward 30s", action(ACTION_FORWARD, 4))
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(ACTION_STOP, 1))
            b.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(CastMediaSession.get(this).sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        } else {
            b.addAction(0, "Stop", action(ACTION_STOP, 1))
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.rkacast.STOP"
        private const val ACTION_TOGGLE = "com.rkacast.TOGGLE"
        private const val ACTION_REWIND = "com.rkacast.REWIND"
        private const val ACTION_FORWARD = "com.rkacast.FORWARD"
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            val i = Intent(context, StreamService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, i) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
