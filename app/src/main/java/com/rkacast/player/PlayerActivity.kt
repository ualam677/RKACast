package com.rkacast.player

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Plays a stream on the phone itself (with optional subtitles). */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val sub = intent.getStringExtra(EXTRA_SUBTITLE)

        val view = PlayerView(this)
        setContentView(view)
        val p = ExoPlayer.Builder(this).build()
        player = p
        view.player = p
        view.setShowSubtitleButton(true)

        val item = MediaItem.Builder().setUri(url).setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(intent.getStringExtra(EXTRA_TITLE)).build(),
        ).apply {
            if (sub != null) {
                setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                            .setMimeType(MimeTypes.TEXT_VTT)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .setLanguage("und")
                            .build(),
                    ),
                )
            }
        }.build()
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
    }
}
