package com.rkacast.torrent

import android.content.Context
import android.util.Log
import com.rkacast.data.DebugLog
import com.rkacast.data.MediaUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.deadline_flags_t
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class TorrentStatusInfo(
    val infoHash: String,
    val name: String,
    val fileName: String,
    val state: String,
    val progress: Float,
    val downloadRate: Long,
    val peers: Int,
    val seeds: Int,
    val bufferedBytes: Long,
    val fileSize: Long,
)

/** A single file inside an active torrent that the HTTP server can stream with seeking. */
class TorrentFileSource(
    private val handle: TorrentHandle,
    private val ti: TorrentInfo,
    private val fileIndex: Int,
    private val savePath: File,
) {
    val name: String = ti.files().fileName(fileIndex)
    val length: Long = ti.files().fileSize(fileIndex)
    private val fileOffset = ti.files().fileOffset(fileIndex)
    private val pieceLength = ti.pieceLength().toLong()
    val firstPiece = ti.files().pieceIndexAtFile(fileIndex)
    val lastPiece = ti.files().lastPieceIndexAtFile(fileIndex)
    val file: File get() = File(savePath, ti.files().filePath(fileIndex))

    fun pieceAt(position: Long) = ((fileOffset + position) / pieceLength).toInt()

    /** Ask libtorrent to fetch the pieces right after [position] first (this is what makes seeking work). */
    fun prioritizeFrom(position: Long, window: Int = 12) {
        val start = pieceAt(position)
        val end = minOf(lastPiece, start + window)
        for ((i, p) in (start..end).withIndex()) {
            if (!handle.havePiece(p)) handle.setPieceDeadline(p, 300 + i * 150, deadline_flags_t())
        }
    }

    fun open(offset: Long): InputStream = PieceWaitingInputStream(offset)

    private inner class PieceWaitingInputStream(private var position: Long) : InputStream() {
        private var raf: RandomAccessFile? = null
        private var closed = false

        init {
            prioritizeFrom(position)
        }

        private fun waitForPiece(piece: Int) {
            var waited = 0L
            var lastBoost = -1L
            while (!handle.havePiece(piece)) {
                if (closed) throw IOException("closed")
                if (!handle.isValid) throw IOException("torrent removed")
                if (waited - lastBoost >= 2000 || lastBoost < 0) {
                    prioritizeFrom(position)
                    lastBoost = waited
                }
                if (waited > 5 * 60 * 1000) throw IOException("Timed out waiting for piece $piece")
                Thread.sleep(100)
                waited += 100
            }
        }

        private fun ensureFile(): RandomAccessFile {
            raf?.let { return it }
            var waited = 0
            while (!file.exists()) {
                if (waited > 60_000) throw IOException("File not created")
                Thread.sleep(100); waited += 100
            }
            return RandomAccessFile(file, "r").also { raf = it }
        }

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n <= 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= length) return -1
            val piece = pieceAt(position)
            waitForPiece(piece)
            // Only read up to the end of this piece; the next call will wait for the next one.
            val pieceEndInFile = (piece + 1) * pieceLength - fileOffset
            val maxLen = minOf(len.toLong(), pieceEndInFile - position, length - position).toInt()
            val f = ensureFile()
            f.seek(position)
            val n = f.read(b, off, maxLen)
            if (n > 0) {
                position += n
                if (pieceAt(position) != piece) prioritizeFrom(position)
            }
            return n
        }

        override fun skip(n: Long): Long {
            val s = minOf(n, length - position)
            position += s
            return s
        }

        override fun available(): Int = 0

        override fun close() {
            closed = true
            raf?.close()
        }
    }
}

object TorrentEngine {
    private const val TAG = "TorrentEngine"
    private lateinit var baseDir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: SessionManager? = null

    private val sources = ConcurrentHashMap<String, TorrentFileSource>() // key: "$hash/$index"
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private var statusJob: Job? = null

    private val _status = MutableStateFlow<TorrentStatusInfo?>(null)
    val status: StateFlow<TorrentStatusInfo?> = _status

    val defaultTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://open.stealth.si:80/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
    )

    fun init(context: Context) {
        baseDir = File(context.cacheDir, "torrents")
    }

    @Synchronized
    private fun session(): SessionManager {
        session?.let { return it }
        val s = SessionManager(false)
        s.start()
        session = s
        return s
    }

    fun buildMagnet(infoHash: String, trackers: List<String> = emptyList(), name: String? = null): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(infoHash.lowercase())
        if (name != null) sb.append("&dn=").append(URLEncoder.encode(name, "UTF-8"))
        (trackers + defaultTrackers).distinct().forEach { sb.append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
        return sb.toString()
    }

    fun infoHashFromMagnet(magnet: String): String? =
        Regex("xt=urn:btih:([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE).find(magnet)?.groupValues?.get(1)?.lowercase()

    /**
     * Resolves metadata for [magnet], starts a sequential download of the chosen file and returns
     * the source to stream. [fileIndex] < 0 picks the largest video file; [fileNameHint] (from Stremio
     * behaviorHints.filename) is used when present.
     */
    suspend fun start(magnet: String, fileIndex: Int = -1, fileNameHint: String? = null): TorrentFileSource =
        withContext(Dispatchers.IO) {
            val s = session()
            baseDir.mkdirs()
            val hashFromMagnet = infoHashFromMagnet(magnet)
            _status.value = TorrentStatusInfo(hashFromMagnet ?: "", "Fetching metadata…", "", "metadata", 0f, 0, 0, 0, 0, 0)

            DebugLog.i("Torrent", "Fetching metadata for $hashFromMagnet")
            val bytes = s.fetchMagnet(magnet, 90, baseDir)
                ?: throw IOException("Could not get torrent metadata (no peers found)")
            val ti = TorrentInfo.bdecode(bytes)
            val hash = ti.infoHash().toHex().lowercase()
            val files = ti.files()

            val chosen = when {
                fileNameHint != null && (0 until files.numFiles()).any { files.fileName(it) == fileNameHint } ->
                    (0 until files.numFiles()).first { files.fileName(it) == fileNameHint }
                fileIndex in 0 until files.numFiles() -> fileIndex
                else -> (0 until files.numFiles())
                    .filter { !files.padFileAt(it) }
                    .sortedWith(compareByDescending<Int> { MediaUtils.isVideoFile(files.fileName(it)) }
                        .thenByDescending { files.fileSize(it) })
                    .first()
            }

            DebugLog.i("Torrent", "Metadata ok: ${files.numFiles()} files, chose #$chosen '${files.fileName(chosen)}' (${files.fileSize(chosen)} bytes), piece=${ti.pieceLength()}")
            sources["$hash/$chosen"]?.let { return@withContext it }

            val priorities = Array(files.numFiles()) { if (it == chosen) Priority.TOP_PRIORITY else Priority.IGNORE }
            val saveDir = File(baseDir, hash).apply { mkdirs() }
            s.download(ti, saveDir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)

            var handle: TorrentHandle? = null
            for (attempt in 0 until 100) {
                handle = s.find(ti.infoHash())
                if (handle?.isValid == true) break
                Thread.sleep(100)
            }
            val h = handle?.takeIf { it.isValid } ?: throw IOException("Torrent failed to start")
            h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            h.prioritizeFiles(priorities)

            val source = TorrentFileSource(h, ti, chosen, saveDir)
            // Players often read the end of the file (MP4 moov / MKV cues) before starting.
            h.setPieceDeadline(source.firstPiece, 100, deadline_flags_t())
            h.setPieceDeadline(source.lastPiece, 200, deadline_flags_t())
            if (source.lastPiece > source.firstPiece) h.setPieceDeadline(source.lastPiece - 1, 300, deadline_flags_t())

            sources["$hash/$chosen"] = source
            handles[hash] = h
            startStatusUpdates(hash, ti.name(), source)
            source
        }

    fun fileSource(infoHash: String, fileIndex: Int): TorrentFileSource? = sources["${infoHash.lowercase()}/$fileIndex"]

    /** Returns (hash, index) of a registered source so the server can build URLs. */
    fun keyOf(source: TorrentFileSource): Pair<String, Int>? =
        sources.entries.firstOrNull { it.value === source }?.key?.split('/')?.let { it[0] to it[1].toInt() }

    private fun startStatusUpdates(hash: String, name: String, source: TorrentFileSource) {
        statusJob?.cancel()
        statusJob = scope.launch {
            while (isActive) {
                val h = handles[hash]
                if (h == null || !h.isValid) break
                runCatching {
                    val st = h.status()
                    var buffered = 0L
                    // contiguous bytes available from the start of the file
                    var p = source.firstPiece
                    while (p <= source.lastPiece && h.havePiece(p)) p++
                    if (p > source.firstPiece) buffered = (p - source.firstPiece).toLong() * h.torrentFile().pieceLength()
                    _status.value = TorrentStatusInfo(
                        infoHash = hash,
                        name = name,
                        fileName = source.name,
                        state = st.state().name.lowercase().replace('_', ' '),
                        progress = st.progress(),
                        downloadRate = st.downloadRate().toLong(),
                        peers = st.numPeers(),
                        seeds = st.numSeeds(),
                        bufferedBytes = buffered.coerceAtMost(source.length),
                        fileSize = source.length,
                    )
                }.onFailure { Log.w(TAG, "status", it) }
                delay(1000)
            }
        }
    }

    fun stopAll() {
        statusJob?.cancel()
        val s = session ?: return
        handles.values.forEach { h -> runCatching { if (h.isValid) s.remove(h) } }
        handles.clear()
        sources.clear()
        _status.value = null
        scope.launch { runCatching { baseDir.deleteRecursively() } }
    }
}
