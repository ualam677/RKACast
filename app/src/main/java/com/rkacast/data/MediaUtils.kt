package com.rkacast.data

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

object MediaUtils {
    private val videoExt = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "flv", "wmv", "mpg", "mpeg", "3gp")
    private val audioExt = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")

    fun extension(pathOrUrl: String): String {
        val path = pathOrUrl.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        return if ('.' in name) name.substringAfterLast('.').lowercase(Locale.US) else ""
    }

    fun isVideoFile(name: String) = extension(name) in videoExt

    fun mimeFor(pathOrUrl: String, fallback: String = "video/mp4"): String = when (extension(pathOrUrl)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "mpg", "mpeg" -> "video/mpeg"
        "3gp" -> "video/3gpp"
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "vtt" -> "text/vtt"
        "srt" -> "application/x-subrip"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> fallback
    }

    /** Heuristic used by the browser sniffer and the URL box. */
    fun looksLikeMedia(url: String): Boolean {
        val ext = extension(url)
        if (ext in videoExt || ext in audioExt || ext == "m3u8" || ext == "mpd") return true
        val lower = url.lowercase(Locale.US)
        return ".m3u8" in lower || "/manifest(format=" in lower || "mime=video" in lower || "videoplayback" in lower
    }

    fun isMagnet(text: String) = text.trim().startsWith("magnet:", ignoreCase = true)

    fun isInfoHash(text: String) = text.trim().matches(Regex("^[a-fA-F0-9]{40}$"))

    /** First site-local IPv4 address of this phone (the address the TV uses to reach our server). */
    fun localIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }
}
