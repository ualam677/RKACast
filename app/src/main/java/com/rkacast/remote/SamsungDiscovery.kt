package com.rkacast.remote

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Finds Samsung Smart TVs (Tizen, 2016+) on the network and reads their info from :8001/api/v2/. */
object SamsungDiscovery {
    private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun discover(context: Context, timeoutMs: Int = 3000): List<SamsungTv> = withContext(Dispatchers.IO) {
        val ips = linkedSetOf<String>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("RKACast:samsung").apply { setReferenceCounted(false) }
        try {
            lock.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                val targets = listOf(
                    "urn:samsung.com:device:RemoteControlReceiver:1",
                    "urn:dial-multiscreen-org:service:dial:1",
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                )
                repeat(2) {
                    for (st in targets) {
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                        val data = msg.toByteArray()
                        socket.send(DatagramPacket(data, data.size, group, 1900))
                    }
                }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        p.address?.hostAddress?.let { ips.add(it) }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            if (lock.isHeld) lock.release()
        }
        coroutineScope {
            ips.map { ip -> async { probe(ip) } }.awaitAll().filterNotNull()
        }
    }

    /** Returns TV info if [ip] is a Samsung TV answering on port 8001. */
    suspend fun probe(ip: String): SamsungTv? = withContext(Dispatchers.IO) {
        runCatching {
            val body = http.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                r.body?.string()
            } ?: return@runCatching null
            val root = json.parseToJsonElement(body).jsonObject
            val device = root["device"] as? JsonObject ?: return@runCatching null
            fun str(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull
            SamsungTv(
                ip = ip,
                name = str(device, "name") ?: str(root, "name") ?: "Samsung TV",
                model = str(device, "modelName") ?: "",
                mac = str(device, "wifiMac")?.takeIf { it.count { c -> c == ':' } == 5 },
                tokenAuth = str(device, "TokenAuthSupport") != "false",
            )
        }.getOrNull()
    }
}
