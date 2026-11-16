package com.rkacast.cast

import android.content.Context
import android.net.Uri
import com.rkacast.data.MediaUtils
import com.rkacast.data.Settings
import com.rkacast.data.TorrentMode
import com.rkacast.server.LocalMediaServer
import com.rkacast.server.StreamService
import com.rkacast.stremio.Stream
import com.rkacast.torrent.TorrentEngine
import java.net.URI

/** Turns anything the user wants to watch (URL, magnet, Stremio stream, local file) into a castable URL. */
object MediaResolver {

    fun fromUrl(
        context: Context,
        url: String,
        title: String? = null,
        headers: Map<String, String> = emptyMap(),
        forceProxy: Boolean = false,
        posterUrl: String? = null,
        subtitleUrl: String? = null,
    ): CastMedia {
        val mime = MediaUtils.mimeFor(url)
        val isHls = mime == "application/x-mpegURL"
        // HLS playlists contain relative segment URLs, so the proxy (which doesn't rewrite playlists) is skipped for them.
        val useProxy = !isHls && (forceProxy || headers.isNotEmpty() || Settings.proxyByDefault.value)
        val finalUrl = if (useProxy) {
            StreamService.start(context, "Relaying video to your TV")
            LocalMediaServer.urlForProxy(url, headers)
        } else url
        return CastMedia(
            url = finalUrl,
            title = title ?: url.substringBefore('?').substringAfterLast('/').ifBlank { url },
            mimeType = mime,
            posterUrl = posterUrl,
            subtitleUrl = subtitleUrl?.let { subtitleFor(context, it) },
        )
    }

    fun fromLocal(context: Context, uri: Uri, title: String? = null): CastMedia {
        StreamService.start(context, "Streaming a local video to your TV")
        val url = LocalMediaServer.urlForContent(uri)
        val name = Uri.decode(url.substringAfterLast('/'))
        return CastMedia(url = url, title = title ?: name, mimeType = MediaUtils.mimeFor(name))
    }

    /** Subtitles always go through the phone so SRT gets converted to VTT and CORS headers are added. */
    fun subtitleFor(context: Context, url: String): String {
        StreamService.start(context, "Serving subtitles to your TV")
        return LocalMediaServer.urlForSubtitle(url)
    }

    suspend fun fromTorrent(
        context: Context,
        magnetOrHash: String,
        title: String?,
        fileIdx: Int? = null,
        fileNameHint: String? = null,
        trackers: List<String> = emptyList(),
        posterUrl: String? = null,
        subtitleUrl: String? = null,
    ): CastMedia {
        val magnet = if (MediaUtils.isMagnet(magnetOrHash)) magnetOrHash
        else TorrentEngine.buildMagnet(magnetOrHash, trackers, title)
        val hash = TorrentEngine.infoHashFromMagnet(magnet) ?: magnetOrHash
        val sub = subtitleUrl?.let { subtitleFor(context, it) }

        return when (Settings.torrentMode.value) {
            TorrentMode.STREMIO_SERVER -> {
                val server = reachableServer(Settings.stremioServer.value)
                val url = "$server/${hash.lowercase()}/${fileIdx ?: -1}"
                val mime = MediaUtils.mimeFor(fileNameHint ?: "", "video/mp4")
                CastMedia(url, title ?: hash, mime, posterUrl, sub)
            }
            TorrentMode.BUILT_IN -> {
                StreamService.start(context, "Streaming torrent to your TV")
                val source = TorrentEngine.start(magnet, fileIdx ?: -1, fileNameHint)
                val (h, idx) = TorrentEngine.keyOf(source) ?: (hash to (fileIdx ?: 0))
                val url = LocalMediaServer.urlForTorrent(h, idx, source.name)
                CastMedia(url, title ?: source.name, MediaUtils.mimeFor(source.name), posterUrl, sub)
            }
        }
    }

    suspend fun fromStremio(
        context: Context,
        stream: Stream,
        title: String,
        posterUrl: String?,
        subtitleUrl: String?,
    ): CastMedia? {
        stream.url?.let { url ->
            val headers = stream.behaviorHints?.proxyHeaders?.request ?: emptyMap()
            return fromUrl(context, url, title, headers, posterUrl = posterUrl, subtitleUrl = subtitleUrl)
        }
        stream.infoHash?.let { hash ->
            return fromTorrent(
                context, hash, title, stream.fileIdx, stream.behaviorHints?.filename, stream.trackers, posterUrl, subtitleUrl,
            )
        }
        return null
    }

    /** A Stremio server on 127.0.0.1 is only reachable from the phone itself, so swap in the phone's LAN IP. */
    private fun reachableServer(server: String): String {
        val uri = runCatching { URI(server) }.getOrNull() ?: return server
        val host = uri.host ?: return server
        if (host == "127.0.0.1" || host == "localhost") {
            val ip = MediaUtils.localIpAddress() ?: return server
            return server.replaceFirst(host, ip).trimEnd('/')
        }
        return server.trimEnd('/')
    }
}
