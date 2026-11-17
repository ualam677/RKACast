package com.rkacast.playback

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.VolumeProviderCompat
import com.rkacast.cast.CastDevice
import com.rkacast.cast.CastManager
import com.rkacast.cast.PlayerState

/**
 * Media session mirroring the TV playback, so lock-screen / notification controls and the phone's
 * volume buttons (remote volume) control the TV.
 */
object CastMediaSession {
    private var session: MediaSessionCompat? = null

    private val volume = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 100, 50) {
        override fun onAdjustVolume(direction: Int) {
            if (direction > 0) CastManager.adjustVolume(true) else if (direction < 0) CastManager.adjustVolume(false)
        }
    }

    fun get(context: Context): MediaSessionCompat {
        session?.let { return it }
        val s = MediaSessionCompat(context.applicationContext, "RKACast")
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = CastManager.play()
            override fun onPause() = CastManager.pause()
            override fun onStop() = CastManager.stopMedia()
            override fun onSeekTo(pos: Long) = CastManager.seekTo(pos)
            override fun onFastForward() = CastManager.seekBy(30_000)
            override fun onRewind() = CastManager.seekBy(-10_000)
            override fun onSkipToNext() = CastManager.seekBy(30_000)
            override fun onSkipToPrevious() = CastManager.seekBy(-10_000)
        })
        s.setPlaybackToRemote(volume)
        s.isActive = true
        session = s
        return s
    }

    fun update(context: Context, state: PlayerState, device: CastDevice?) {
        val s = get(context)
        val media = state.media
        s.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, media?.title ?: "Nothing playing")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, device?.name ?: "RKACast")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
                .build(),
        )
        val ps = when {
            media == null -> PlaybackStateCompat.STATE_STOPPED
            state.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(ps, state.positionMs, if (state.isPlaying) 1f else 0f)
                .build(),
        )
    }

    fun release() {
        session?.release()
        session = null
    }
}
