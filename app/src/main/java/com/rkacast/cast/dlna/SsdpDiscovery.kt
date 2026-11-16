package com.rkacast.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit

/** Finds UPnP/DLNA media renderers (smart TVs, Fire TV/Roku apps, Kodi, consoles…) on the LAN. */
class SsdpDiscovery(private val context: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()

    suspend fun search(timeoutMs: Int = 3500): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("RKACast:ssdp").apply { setReferenceCounted(false) }
        val locations = mutableMapOf<String, String>() // location -> usn
        try {
            lock.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                val targets = listOf(
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                )
                repeat(2) {
                    for (st in targets) {
                        val msg = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: $st\r\n\r\n"
                        val data = msg.toByteArray()
                        socket.send(DatagramPacket(data, data.size, group, 1900))
                    }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        val headers = text.lines().mapNotNull { line ->
                            val i = line.indexOf(':')
                            if (i > 0) line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim() else null
                        }.toMap()
                        val loc = headers["LOCATION"] ?: continue
                        locations[loc] = headers["USN"]?.substringBefore("::") ?: loc
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("SsdpDiscovery", "search failed", e)
        } finally {
            if (lock.isHeld) lock.release()
        }
        coroutineScope {
            locations.entries.map { (loc, usn) -> async { runCatching { describe(loc, usn) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
                .distinctBy { it.usn }
        }
    }

    private fun describe(location: String, usn: String): DlnaDevice? {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() } ?: return null
        val base = tag(xml, "URLBase") ?: location
        var av: String? = null
        var rc: String? = null
        Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val block = m.groupValues[1]
            val type = tag(block, "serviceType") ?: return@forEach
            val control = tag(block, "controlURL") ?: return@forEach
            val abs = URL(URL(base), control).toString()
            if (type.contains("AVTransport")) av = abs
            if (type.contains("RenderingControl")) rc = abs
        }
        val avUrl = av ?: return null
        return DlnaDevice(
            usn = usn,
            friendlyName = tag(xml, "friendlyName") ?: "DLNA device",
            manufacturer = tag(xml, "manufacturer") ?: "",
            modelName = tag(xml, "modelName") ?: "",
            location = location,
            avTransportControlUrl = avUrl,
            renderingControlUrl = rc,
        )
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name[^>]*>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.let { unescape(it) }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
