package com.rkacast.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.rkacast.cast.CastMedia

/** Hands a stream to VLC for Android (which can also cast to Chromecast from its own player). */
object VlcLauncher {
    const val PACKAGE = "org.videolan.vlc"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /** Returns false if VLC isn't installed (the Play Store page is opened instead). */
    fun play(context: Context, media: CastMedia): Boolean {
        if (!isInstalled(context)) {
            openStore(context)
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setPackage(PACKAGE)
            setDataAndTypeAndNormalize(Uri.parse(media.url), if (media.mimeType.startsWith("audio")) "audio/*" else "video/*")
            putExtra("title", media.title)
            media.subtitleUrl?.let { putExtra("subtitles_location", it) }
            if (media.startPositionMs > 0) putExtra("position", media.startPositionMs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent); true
        } catch (e: ActivityNotFoundException) {
            openStore(context); false
        }
    }

    fun openStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.onFailure {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
