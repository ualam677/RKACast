package com.rkacast.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.rkacast.data.DebugLog
import com.rkacast.cast.dlna.DlnaController
import com.rkacast.cast.dlna.DlnaDevice
import com.rkacast.cast.dlna.SsdpDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class DeviceKind { CHROMECAST, DLNA }

data class CastDevice(val id: String, val name: String, val subtitle: String, val kind: DeviceKind)

data class CastMedia(
    val url: String,
    val title: String,
    val mimeType: String,
    val posterUrl: String? = null,
    val subtitleUrl: String? = null,
    val isLive: Boolean = false,
    val startPositionMs: Long = 0,
)

data class PlayerState(
    val media: CastMedia? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val status: String = "",
    val error: String? = null,
    /** True once the TV reports the video played to the end (not when the user stopped it). */
    val finished: Boolean = false,
)

/** One API over Google Cast (Chromecast / Google TV / Android TV) and DLNA renderers. */
object CastManager {
    private const val TAG = "CastManager"
    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var castContext: CastContext? = null
    private var mediaRouter: MediaRouter? = null
    private var selector: MediaRouteSelector? = null

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices

    private val _selected = MutableStateFlow<CastDevice?>(null)
    val selected: StateFlow<CastDevice?> = _selected

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    val castAvailable: Boolean get() = castContext != null

    private var dlnaDevices = emptyList<DlnaDevice>()
    private var dlna: DlnaController? = null
    private var dlnaPoll: Job? = null
    private var pendingMedia: CastMedia? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        castContext = try {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Google Cast unavailable (no Play Services?)", e)
            null
        }
        castContext?.let { cc ->
            mediaRouter = MediaRouter.getInstance(appContext)
            selector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()
            cc.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            cc.sessionManager.currentCastSession?.let { attachSession(it) }
        }
    }

    // ---------------------------------------------------------------- discovery

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
    }

    fun startDiscovery() {
        val router = mediaRouter
        val sel = selector
        if (router != null && sel != null) {
            router.addCallback(sel, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        }
        refreshDlna()
        publishDevices()
    }

    fun stopDiscovery() {
        mediaRouter?.removeCallback(routerCallback)
    }

    fun refreshDlna() {
        if (_scanning.value) return
        _scanning.value = true
        scope.launch {
            dlnaDevices = runCatching { SsdpDiscovery(appContext).search() }.getOrDefault(dlnaDevices)
            _scanning.value = false
            publishDevices()
        }
    }

    private fun castRoutes(): List<MediaRouter.RouteInfo> {
        val router = mediaRouter ?: return emptyList()
        val sel = selector ?: return emptyList()
        return router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(sel) }
    }

    private fun publishDevices() {
        val cast = castRoutes().map { CastDevice(it.id, it.name, it.description ?: "Google Cast", DeviceKind.CHROMECAST) }
        val upnp = dlnaDevices.map {
            CastDevice(it.usn, it.friendlyName, listOf(it.manufacturer, it.modelName).filter { s -> s.isNotBlank() }
                .joinToString(" ").ifBlank { "DLNA" } + " · DLNA", DeviceKind.DLNA)
        }
        _devices.value = cast + upnp
    }

    // ---------------------------------------------------------------- selection

    fun select(device: CastDevice) {
        if (_selected.value?.id == device.id) return
        DebugLog.i("Cast", "Select ${device.kind} '${device.name}'")
        disconnect(stopMedia = false)
        when (device.kind) {
            DeviceKind.CHROMECAST -> {
                val route = castRoutes().firstOrNull { it.id == device.id } ?: return
                mediaRouter?.selectRoute(route)
                _selected.value = device
                _state.value = PlayerState(status = "Connecting to ${device.name}…")
            }
            DeviceKind.DLNA -> {
                val d = dlnaDevices.firstOrNull { it.usn == device.id } ?: return
                dlna = DlnaController(d)
                _selected.value = device
                _state.value = PlayerState(status = "Connected to ${device.name}")
                pendingMedia?.let { m -> pendingMedia = null; cast(m) }
            }
        }
    }

    fun disconnect(stopMedia: Boolean = true) {
        val sel = _selected.value ?: return
        when (sel.kind) {
            DeviceKind.CHROMECAST -> {
                castContext?.sessionManager?.endCurrentSession(stopMedia)
                mediaRouter?.let { it.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED) }
            }
            DeviceKind.DLNA -> {
                val c = dlna
                if (stopMedia && c != null) scope.launch { runCatching { c.stop() } }
                dlnaPoll?.cancel()
                dlna = null
            }
        }
        _selected.value = null
        _state.value = PlayerState()
        // Nothing left to serve: drop the foreground service unless a torrent is still running.
        if (com.rkacast.torrent.TorrentEngine.status.value == null) com.rkacast.server.StreamService.stop(appContext)
    }

    // ---------------------------------------------------------------- playback

    /** Sends [media] to the selected device. If nothing is selected yet, it is queued until one is. */
    fun cast(media: CastMedia) {
        val sel = _selected.value
        if (sel == null) {
            pendingMedia = media
            return
        }
        com.rkacast.server.StreamService.start(appContext, "Casting to ${sel.name}")
        DebugLog.i("Cast", "Cast to ${sel.kind} '${sel.name}': ${DebugLog.mediaHint(media.url)} mime=${media.mimeType} subs=${media.subtitleUrl != null} start=${media.startPositionMs}")
        _state.value = PlayerState(media = media, isBuffering = true, status = "Loading on ${sel.name}…")
        when (sel.kind) {
            DeviceKind.CHROMECAST -> {
                val client = currentClient()
                if (client == null) pendingMedia = media else loadOnCast(client, media)
            }
            DeviceKind.DLNA -> {
                val c = dlna ?: return
                scope.launch {
                    runCatching {
                        c.load(media.url, media.title, media.mimeType, media.subtitleUrl)
                        if (media.startPositionMs > 0) {
                            delay(2500)
                            runCatching { c.seek(media.startPositionMs) }
                        }
                    }
                        .onSuccess { startDlnaPolling(c) }
                        .onFailure { e ->
                            DebugLog.e("DLNA", "Load failed", e)
                            _state.value = _state.value.copy(isBuffering = false, error = e.message)
                        }
                }
            }
        }
    }

    fun hasPending() = pendingMedia != null

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun play() {
        when (_selected.value?.kind) {
            DeviceKind.CHROMECAST -> currentClient()?.play()
            DeviceKind.DLNA -> dlna?.let { c -> scope.launch { runCatching { c.play() } } }
            null -> {}
        }
        _state.value = _state.value.copy(isPlaying = true)
    }

    fun pause() {
        when (_selected.value?.kind) {
            DeviceKind.CHROMECAST -> currentClient()?.pause()
            DeviceKind.DLNA -> dlna?.let { c -> scope.launch { runCatching { c.pause() } } }
            null -> {}
        }
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0)
        when (_selected.value?.kind) {
            DeviceKind.CHROMECAST -> currentClient()?.seek(MediaSeekOptions.Builder().setPosition(target).build())
            DeviceKind.DLNA -> dlna?.let { c -> scope.launch { runCatching { c.seek(target) } } }
            null -> {}
        }
        _state.value = _state.value.copy(positionMs = target)
    }

    fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    fun stopMedia() {
        com.rkacast.playback.PlaybackTracker.onUserStop()
        when (_selected.value?.kind) {
            DeviceKind.CHROMECAST -> currentClient()?.stop()
            DeviceKind.DLNA -> dlna?.let { c -> scope.launch { runCatching { c.stop() } } }
            null -> {}
        }
        dlnaPoll?.cancel()
        _state.value = PlayerState(status = _selected.value?.let { "Connected to ${it.name}" } ?: "")
    }

    private var dlnaVolume = 30

    /** Volume up/down for whatever is being watched; prefers the Samsung TV remote when connected. */
    fun adjustVolume(up: Boolean) {
        if (com.rkacast.remote.SamsungRemote.status.value == com.rkacast.remote.SamsungRemote.Status.CONNECTED) {
            com.rkacast.remote.SamsungRemote.key(if (up) "KEY_VOLUP" else "KEY_VOLDOWN")
            return
        }
        when (_selected.value?.kind) {
            DeviceKind.CHROMECAST -> runCatching {
                val s = castContext?.sessionManager?.currentCastSession ?: return
                s.volume = (s.volume + if (up) 0.05 else -0.05).coerceIn(0.0, 1.0)
            }
            DeviceKind.DLNA -> {
                dlnaVolume = (dlnaVolume + if (up) 5 else -5).coerceIn(0, 100)
                dlna?.let { c -> val v = dlnaVolume; scope.launch { runCatching { c.setVolume(v) } } }
            }
            null -> {}
        }
    }

    fun setVolume(fraction: Float) {
        when (_selected.value?.kind) {
            DeviceKind.CHROMECAST -> runCatching { castContext?.sessionManager?.currentCastSession?.volume = fraction.toDouble() }
            DeviceKind.DLNA -> dlna?.let { c -> scope.launch { runCatching { c.setVolume((fraction * 100).toInt()) } } }
            null -> {}
        }
    }

    // ---------------------------------------------------------------- Google Cast internals

    private fun currentClient(): RemoteMediaClient? = castContext?.sessionManager?.currentCastSession?.remoteMediaClient

    private fun loadOnCast(client: RemoteMediaClient, media: CastMedia) {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
            media.posterUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val tracks = media.subtitleUrl?.let {
            listOf(
                MediaTrack.Builder(1, MediaTrack.TYPE_TEXT)
                    .setName("Subtitles")
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(it)
                    .setContentType("text/vtt")
                    .setLanguage("en")
                    .build(),
            )
        }
        val info = MediaInfo.Builder(media.url)
            .setStreamType(if (media.isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.mimeType)
            .setMetadata(metadata)
            .apply { if (tracks != null) setMediaTracks(tracks) }
            .setTextTrackStyle(subtitleStyle())
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(media.startPositionMs)
            .apply { if (tracks != null) setActiveTrackIds(longArrayOf(1)) }
            .build()
        client.load(request).setResultCallback { result ->
            DebugLog.i("Cast", "Load result: ${result.status.statusCode} ${result.status.statusMessage ?: ""}")
            if (!result.status.isSuccess) {
                _state.value = _state.value.copy(
                    isBuffering = false,
                    error = "The TV couldn't play this (code ${result.status.statusCode}). " +
                        "Try 'Cast via phone proxy' or a different format.",
                )
            }
        }
    }

    private fun subtitleStyle(): TextTrackStyle = TextTrackStyle().apply {
        fontScale = com.rkacast.data.Settings.subtitleScale.value
        foregroundColor = 0xFFFFFFFF.toInt()
        backgroundColor = 0x99000000.toInt()
        edgeType = TextTrackStyle.EDGE_TYPE_DROP_SHADOW
        edgeColor = 0xFF000000.toInt()
    }

    /** Re-sends the current video with a different subtitle (or none), resuming where it was. */
    fun changeSubtitle(subtitleUrl: String?) {
        val m = _state.value.media ?: return
        cast(m.copy(subtitleUrl = subtitleUrl, startPositionMs = _state.value.positionMs))
    }

    private var lastLoggedState = -1

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = currentClient() ?: return
            val ps = client.playerState
            if (ps != lastLoggedState) {
                lastLoggedState = ps
                DebugLog.i("Cast", "Player state=$ps idleReason=${client.idleReason}")
            }
            _state.value = _state.value.copy(
                finished = ps == MediaStatus.PLAYER_STATE_IDLE && client.idleReason == MediaStatus.IDLE_REASON_FINISHED,
                isPlaying = ps == MediaStatus.PLAYER_STATE_PLAYING,
                isBuffering = ps == MediaStatus.PLAYER_STATE_BUFFERING || ps == MediaStatus.PLAYER_STATE_LOADING,
                durationMs = client.streamDuration.coerceAtLeast(0),
                status = when (ps) {
                    MediaStatus.PLAYER_STATE_PLAYING -> "Playing"
                    MediaStatus.PLAYER_STATE_PAUSED -> "Paused"
                    MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> "Buffering…"
                    MediaStatus.PLAYER_STATE_IDLE -> when (client.idleReason) {
                        MediaStatus.IDLE_REASON_FINISHED -> "Finished"
                        MediaStatus.IDLE_REASON_ERROR -> "Playback error on TV"
                        else -> "Idle"
                    }
                    else -> ""
                },
            )
        }
    }

    private val progressListener = RemoteMediaClient.ProgressListener { progress, duration ->
        _state.value = _state.value.copy(positionMs = progress, durationMs = duration.coerceAtLeast(0))
    }

    private fun attachSession(session: CastSession) {
        val client = session.remoteMediaClient ?: return
        client.registerCallback(mediaCallback)
        client.addProgressListener(progressListener, 1000)
        val device = session.castDevice
        if (_selected.value == null && device != null) {
            _selected.value = CastDevice(device.deviceId, device.friendlyName ?: "Chromecast", "Google Cast", DeviceKind.CHROMECAST)
        }
        pendingMedia?.let { m ->
            pendingMedia = null
            main.post { cast(m) }
        }
    }

    private fun detachSession(session: CastSession?) {
        session?.remoteMediaClient?.let {
            it.unregisterCallback(mediaCallback)
            it.removeProgressListener(progressListener)
        }
        if (_selected.value?.kind == DeviceKind.CHROMECAST) {
            _selected.value = null
            _state.value = PlayerState()
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) = attachSession(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            DebugLog.w("Cast", "Session start failed: $error")
            _state.value = PlayerState(error = "Couldn't connect to the Cast device (error $error)")
            _selected.value = null
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            DebugLog.i("Cast", "Session ended ($error)")
            detachSession(session)
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attachSession(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) = detachSession(session)
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    // ---------------------------------------------------------------- DLNA internals

    private fun startDlnaPolling(c: DlnaController) {
        dlnaPoll?.cancel()
        dlnaPoll = scope.launch {
            var prevState = ""
            var lastPos = 0L
            var lastDur = 0L
            while (isActive && dlna === c) {
                runCatching {
                    val pos = c.position()
                    val ts = c.transportState()
                    if (pos.positionMs > 0) lastPos = pos.positionMs
                    if (pos.durationMs > 0) lastDur = pos.durationMs
                    // DLNA has no "finished" event: PLAYING -> STOPPED within 30 s of the end counts as finished.
                    val endedNaturally = (prevState == "PLAYING" || prevState == "TRANSITIONING") &&
                        (ts == "STOPPED" || ts == "NO_MEDIA_PRESENT") && lastDur > 0 && lastPos >= lastDur - 30_000
                    if (ts != prevState) DebugLog.i("DLNA", "State $prevState -> $ts at ${lastPos / 1000}s/${lastDur / 1000}s")
                    prevState = ts
                    _state.value = _state.value.copy(
                        finished = _state.value.finished || endedNaturally,
                        positionMs = pos.positionMs,
                        durationMs = pos.durationMs,
                        isPlaying = ts == "PLAYING",
                        isBuffering = ts == "TRANSITIONING",
                        status = when (ts) {
                            "PLAYING" -> "Playing"
                            "PAUSED_PLAYBACK" -> "Paused"
                            "TRANSITIONING" -> "Buffering…"
                            "STOPPED" -> "Stopped"
                            else -> ts.lowercase()
                        },
                    )
                }
                delay(1000)
            }
        }
    }
}
