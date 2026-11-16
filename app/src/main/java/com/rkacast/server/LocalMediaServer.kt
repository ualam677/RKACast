package com.rkacast.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.rkacast.data.MediaUtils
import com.rkacast.torrent.TorrentEngine
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Embedded HTTP server running on the phone. TVs can only pull media over the network, so anything
 * that is not already a public URL (local files, torrents, streams that need special headers,
 * SRT subtitles that Chromecast cannot read) is exposed through here.
 */
object LocalMediaServer {
    private const val TAG = "LocalMediaServer"
    private const val PREFERRED_PORT = 8765

    private lateinit var appContext: Context
    private var server: Server? = null

    private data class ContentEntry(val uri: Uri, val name: String, val size: Long)
    private data class ProxyEntry(val url: String, val headers: Map<String, String>)

    private val contentEntries = ConcurrentHashMap<String, ContentEntry>()
    private val proxyEntries = ConcurrentHashMap<String, ProxyEntry>()
    private val subtitleEntries = ConcurrentHashMap<String, String>()

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun ensureStarted(): Int {
        server?.let { if (it.isAlive) return it.listeningPort }
        val s = try {
            Server(PREFERRED_PORT).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        } catch (e: IOException) {
            Log.w(TAG, "Port $PREFERRED_PORT busy, using a random port", e)
            Server(0).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        }
        server = s
        return s.listeningPort
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    private fun baseUrl(): String {
        val port = ensureStarted()
        val ip = MediaUtils.localIpAddress() ?: "127.0.0.1"
        return "http://$ip:$port"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Exposes a content:// or file:// Uri (e.g. a video picked from the gallery). */
    fun urlForContent(uri: Uri): String {
        var name = "video.mp4"
        var size = -1L
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size < 0) {
            size = runCatching {
                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            }.getOrDefault(-1L)
        }
        val id = UUID.randomUUID().toString().take(8)
        contentEntries[id] = ContentEntry(uri, name, size)
        return "${baseUrl()}/file/$id/${enc(name)}"
    }

    /** Re-serves a remote stream through the phone so we can attach Referer/User-Agent/cookie headers. */
    fun urlForProxy(url: String, headers: Map<String, String>): String {
        val id = UUID.randomUUID().toString().take(8)
        proxyEntries[id] = ProxyEntry(url, headers)
        val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "stream" }
        return "${baseUrl()}/proxy/$id/${enc(name)}"
    }

    /** Serves any subtitle (SRT or VTT) as WebVTT with CORS, which is what Chromecast requires. */
    fun urlForSubtitle(url: String): String {
        val id = UUID.randomUUID().toString().take(8)
        subtitleEntries[id] = url
        return "${baseUrl()}/sub/$id.vtt"
    }

    /** Serves a subtitle file picked from the phone's storage, converted to VTT. */
    fun urlForLocalSubtitle(uri: Uri): String = urlForSubtitle(uri.toString())

    fun urlForTorrent(infoHash: String, fileIndex: Int, fileName: String): String =
        "${baseUrl()}/torrent/${infoHash.lowercase()}/$fileIndex/${enc(fileName)}"

    // ---------------------------------------------------------------------------------------------

    private class Server(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val parts = session.uri.trim('/').split('/')
            val resp = try {
                when (parts.firstOrNull()) {
                    "file" -> serveContent(session, parts)
                    "proxy" -> serveProxy(session, parts)
                    "sub" -> serveSubtitle(parts)
                    "torrent" -> serveTorrent(session, parts)
                    "" , null -> newFixedLengthResponse("RKACast server is running")
                    else -> notFound()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error serving ${session.uri}", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
            }
            resp.addHeader("Access-Control-Allow-Origin", "*")
            resp.addHeader("Access-Control-Allow-Headers", "*")
            resp.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
            return resp
        }

        private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")

        private fun serveContent(session: IHTTPSession, parts: List<String>): Response {
            val entry = contentEntries[parts.getOrNull(1)] ?: return notFound()
            val ctx = appContext
            return serveRanged(session, entry.size, MediaUtils.mimeFor(entry.name)) { offset ->
                val pfd = ctx.contentResolver.openFileDescriptor(entry.uri, "r")
                    ?: throw IOException("Cannot open ${entry.uri}")
                val fis = FileInputStream(pfd.fileDescriptor)
                fis.channel.position(offset)
                object : java.io.FilterInputStream(fis) {
                    override fun close() {
                        super.close(); pfd.close()
                    }
                }
            }
        }

        private fun serveTorrent(session: IHTTPSession, parts: List<String>): Response {
            val hash = parts.getOrNull(1) ?: return notFound()
            val index = parts.getOrNull(2)?.toIntOrNull() ?: return notFound()
            val source = TorrentEngine.fileSource(hash, index) ?: return notFound()
            return serveRanged(session, source.length, MediaUtils.mimeFor(source.name)) { offset ->
                source.open(offset)
            }
        }

        private fun serveProxy(session: IHTTPSession, parts: List<String>): Response {
            val entry = proxyEntries[parts.getOrNull(1)] ?: return notFound()
            val req = Request.Builder().url(entry.url).apply {
                entry.headers.forEach { (k, v) -> header(k, v) }
                session.headers["range"]?.let { header("Range", it) }
                if (session.method == Method.HEAD) head()
            }.build()
            val upstream = http.newCall(req).execute()
            val status = Response.Status.lookup(upstream.code) ?: Response.Status.OK
            val mime = upstream.header("Content-Type") ?: MediaUtils.mimeFor(entry.url)
            val body = upstream.body
            val length = body?.contentLength() ?: -1L
            val stream: InputStream = body?.byteStream() ?: "".byteInputStream()
            val resp = if (length >= 0) newFixedLengthResponse(status, mime, stream, length)
            else newChunkedResponse(status, mime, stream)
            listOf("Content-Range", "Accept-Ranges").forEach { h -> upstream.header(h)?.let { resp.addHeader(h, it) } }
            if (upstream.header("Accept-Ranges") == null) resp.addHeader("Accept-Ranges", "bytes")
            dlnaHeaders(resp)
            return resp
        }

        private fun serveSubtitle(parts: List<String>): Response {
            val id = parts.getOrNull(1)?.removeSuffix(".vtt") ?: return notFound()
            val url = subtitleEntries[id] ?: return notFound()
            val raw = if (url.startsWith("content:") || url.startsWith("file:")) {
                appContext.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() } ?: ByteArray(0)
            } else {
                http.newCall(Request.Builder().url(url).build()).execute().use { r -> r.body?.bytes() ?: ByteArray(0) }
            }
            val text = SubtitleConverter.decode(raw)
            return newFixedLengthResponse(Response.Status.OK, "text/vtt", SubtitleConverter.toVtt(text))
        }

        /** Generic HTTP Range implementation shared by local files and torrents. */
        private fun serveRanged(
            session: IHTTPSession,
            totalLength: Long,
            mime: String,
            open: (Long) -> InputStream,
        ): Response {
            if (totalLength < 0) {
                val r = newChunkedResponse(Response.Status.OK, mime, open(0))
                dlnaHeaders(r)
                return r
            }
            val range = session.headers["range"]
            var start = 0L
            var end = totalLength - 1
            var partial = false
            if (range != null && range.startsWith("bytes=")) {
                val spec = range.removePrefix("bytes=").substringBefore(',')
                val s = spec.substringBefore('-')
                val e = spec.substringAfter('-', "")
                if (s.isEmpty()) {
                    // suffix range: last N bytes
                    val n = e.toLongOrNull() ?: 0
                    start = (totalLength - n).coerceAtLeast(0)
                } else {
                    start = s.toLongOrNull() ?: 0
                    if (e.isNotEmpty()) end = e.toLongOrNull()?.coerceAtMost(totalLength - 1) ?: end
                }
                partial = true
            }
            if (start >= totalLength || start > end) {
                val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                r.addHeader("Content-Range", "bytes */$totalLength")
                return r
            }
            val len = end - start + 1
            val stream = if (session.method == Method.HEAD) "".byteInputStream() else open(start)
            val r = newFixedLengthResponse(
                if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK, mime, stream, len,
            )
            r.addHeader("Accept-Ranges", "bytes")
            if (partial) r.addHeader("Content-Range", "bytes $start-$end/$totalLength")
            dlnaHeaders(r)
            return r
        }

        private fun dlnaHeaders(r: Response) {
            // Many Samsung/LG/Sony TVs refuse to play without these.
            r.addHeader("transferMode.dlna.org", "Streaming")
            r.addHeader(
                "contentFeatures.dlna.org",
                "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            )
        }
    }
}
