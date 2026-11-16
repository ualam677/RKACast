package com.rkacast.cast.dlna

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Minimal UPnP AVTransport client: load, play, pause, stop, seek and position polling. */
class DlnaController(val device: DlnaDevice) {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val avt = "urn:schemas-upnp-org:service:AVTransport:1"
    private val rcs = "urn:schemas-upnp-org:service:RenderingControl:1"

    data class Position(val positionMs: Long, val durationMs: Long)

    suspend fun load(url: String, title: String, mime: String, subtitleUrl: String?) {
        runCatching { stop() }
        val meta = didl(url, title, mime, subtitleUrl)
        soap(
            device.avTransportControlUrl, avt, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(meta)}</CurrentURIMetaData>",
        )
        play()
    }

    suspend fun play() = soap(device.avTransportControlUrl, avt, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    suspend fun pause() = soap(device.avTransportControlUrl, avt, "Pause", "<InstanceID>0</InstanceID>")
    suspend fun stop() = soap(device.avTransportControlUrl, avt, "Stop", "<InstanceID>0</InstanceID>")

    suspend fun seek(ms: Long) = soap(
        device.avTransportControlUrl, avt, "Seek",
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${hms(ms)}</Target>",
    )

    suspend fun setVolume(volume: Int) {
        val rc = device.renderingControlUrl ?: return
        soap(rc, rcs, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$volume</DesiredVolume>")
    }

    suspend fun position(): Position {
        val xml = soap(device.avTransportControlUrl, avt, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        return Position(parseHms(tag(xml, "RelTime")), parseHms(tag(xml, "TrackDuration")))
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT */
    suspend fun transportState(): String {
        val xml = soap(device.avTransportControlUrl, avt, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        return tag(xml, "CurrentTransportState") ?: "UNKNOWN"
    }

    private suspend fun soap(controlUrl: String, service: String, action: String, args: String): String =
        withContext(Dispatchers.IO) {
            val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""
            val req = Request.Builder().url(controlUrl)
                .header("SOAPACTION", "\"$service#$action\"")
                .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val err = tag(text, "errorDescription") ?: "HTTP ${r.code}"
                    throw IOException("$action failed: $err")
                }
                text
            }
        }

    private fun didl(url: String, title: String, mime: String, subtitleUrl: String?): String {
        val upnpClass = if (mime.startsWith("audio")) "object.item.audioItem.musicTrack" else "object.item.videoItem.movie"
        val sub = subtitleUrl?.let {
            "<sec:CaptionInfoEx sec:type=\"srt\">${esc(it)}</sec:CaptionInfoEx>"
        } ?: ""
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:sec=\"http://www.sec.co.kr/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${esc(title)}</dc:title><upnp:class>$upnpClass</upnp:class>$sub" +
            "<res protocolInfo=\"http-get:*:$mime:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\">${esc(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name[^>]*>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    private fun hms(ms: Long): String {
        val t = ms / 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    }

    private fun parseHms(s: String?): Long {
        if (s.isNullOrBlank() || s == "NOT_IMPLEMENTED") return 0
        val parts = s.substringBefore('.').split(':').mapNotNull { it.toLongOrNull() }
        if (parts.size != 3) return 0
        return ((parts[0] * 3600) + parts[1] * 60 + parts[2]) * 1000
    }
}
