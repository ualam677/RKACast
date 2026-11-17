package com.rkacast.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.rkacast.data.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

/**
 * Samsung Smart TV (Tizen) remote over the TV's local WebSocket API:
 * remote keys, mouse pointer (touchpad), typing into the TV's on-screen keyboard, app launching.
 * The first connection shows an "Allow" prompt on the TV; the token it returns is saved.
 */
object SamsungRemote {
    private const val TAG = "SamsungRemote"
    private const val APP_NAME = "RKACast"

    enum class Status { DISCONNECTED, CONNECTING, WAITING_FOR_APPROVAL, CONNECTED, ERROR }

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = _status
    private val _tv = MutableStateFlow<SamsungTv?>(null)
    val tv: StateFlow<SamsungTv?> = _tv
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    /** True while the TV's on-screen keyboard is open (a text box is focused). */
    private val _keyboardOpen = MutableStateFlow(false)
    val keyboardOpen: StateFlow<Boolean> = _keyboardOpen
    private val _saved = MutableStateFlow<List<SamsungTv>>(emptyList())
    val saved: StateFlow<List<SamsungTv>> = _saved

    private var socket: WebSocket? = null
    private var triedInsecureFallback = false

    // Mouse movement is accumulated and flushed ~30 times a second.
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var mouseJob: Job? = null

    /** Self-signed certificate on port 8002 is expected, so this client (and only this one) trusts it. */
    private val client: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("samsung", Context.MODE_PRIVATE)
        _saved.value = runCatching {
            json.decodeFromString(ListSerializer(SamsungTv.serializer()), prefs.getString("tvs", "[]")!!)
        }.getOrDefault(emptyList())
    }

    private fun token(ip: String): String? = prefs.getString("token_$ip", null)

    private fun remember(tv: SamsungTv) {
        _saved.value = listOf(tv) + _saved.value.filter { it.ip != tv.ip }
        prefs.edit().putString("tvs", json.encodeToString(ListSerializer(SamsungTv.serializer()), _saved.value)).apply()
    }

    fun forget(tv: SamsungTv) {
        _saved.value = _saved.value.filter { it.ip != tv.ip }
        prefs.edit().remove("token_${tv.ip}")
            .putString("tvs", json.encodeToString(ListSerializer(SamsungTv.serializer()), _saved.value)).apply()
        if (_tv.value?.ip == tv.ip) disconnect()
    }

    // ------------------------------------------------------------------ connection

    fun connect(tv: SamsungTv) {
        disconnect()
        _tv.value = tv
        _status.value = Status.CONNECTING
        _message.value = null
        triedInsecureFallback = false
        remember(tv)
        open(tv, secure = true)
    }

    private fun open(tv: SamsungTv, secure: Boolean) {
        val url = SamsungPayloads.connectUrl(tv.ip, APP_NAME, token(tv.ip), secure)
        DebugLog.i("Samsung", "Connecting to ${tv.ip} secure=$secure hasToken=${token(tv.ip) != null}")
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener(tv, secure))
    }

    fun disconnect() {
        mouseJob?.cancel()
        mouseJob = null
        socket?.close(1000, null)
        socket = null
        _status.value = Status.DISCONNECTED
        _keyboardOpen.value = false
    }

    private class Listener(val tv: SamsungTv, val secure: Boolean) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (_status.value != Status.CONNECTED) {
                _status.value = Status.WAITING_FOR_APPROVAL
                _message.value = "Check your TV and choose Allow"
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val event = obj["event"]?.jsonPrimitive?.contentOrNull
            DebugLog.i("Samsung", "Event $event" + if (event == "ed.installedApp.get") " raw=${text.take(1500)}" else "")
            when (event) {
                "ms.channel.connect" -> {
                    (obj["data"] as? JsonObject)?.get("token")?.jsonPrimitive?.contentOrNull?.let {
                        prefs.edit().putString("token_${tv.ip}", it).apply()
                    }
                    _status.value = Status.CONNECTED
                    _message.value = null
                    startMouseLoop()
                }
                "ms.channel.unauthorized" -> {
                    _status.value = Status.ERROR
                    _message.value = "The TV denied access. Allow RKACast in TV Settings → General → External Device Manager → Device Connection Manager."
                }
                "ms.channel.timeOut" -> {
                    _status.value = Status.ERROR
                    _message.value = "No answer on the TV. Try again and press Allow."
                }
                "ms.remote.imeStart" -> _keyboardOpen.value = true
                "ms.remote.imeEnd" -> _keyboardOpen.value = false
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            DebugLog.w("Samsung", "Socket failure secure=$secure http=${response?.code}", t)
            if (socket !== webSocket) return
            if (secure && !triedInsecureFallback && _status.value != Status.CONNECTED) {
                // Older (2016-2017) models only expose the unencrypted port.
                triedInsecureFallback = true
                open(tv, secure = false)
                return
            }
            _status.value = Status.ERROR
            _message.value = "Lost connection to ${tv.name}: ${t.message ?: "network error"}. Is the TV on and on the same Wi-Fi?"
            _keyboardOpen.value = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) {
                _status.value = Status.DISCONNECTED
                _keyboardOpen.value = false
            }
        }
    }

    private fun send(payload: JsonObject) {
        socket?.send(payload.toString())
    }

    // ------------------------------------------------------------------ keys

    fun key(code: String) = sendKeyCmd("Click", code)
    fun press(code: String) = sendKeyCmd("Press", code)
    fun release(code: String) = sendKeyCmd("Release", code)

    private fun sendKeyCmd(cmd: String, code: String) = send(SamsungPayloads.key(cmd, code))

    // ------------------------------------------------------------------ mouse / touchpad

    fun moveMouse(dx: Float, dy: Float) {
        synchronized(this) { pendingDx += dx; pendingDy += dy }
    }

    private fun startMouseLoop() {
        mouseJob?.cancel()
        mouseJob = scope.launch {
            while (isActive) {
                val (dx, dy) = synchronized(this@SamsungRemote) {
                    val r = pendingDx.roundToInt() to pendingDy.roundToInt()
                    pendingDx -= r.first; pendingDy -= r.second
                    r
                }
                if (dx != 0 || dy != 0) {
                    send(SamsungPayloads.mouseMove(dx, dy))
                }
                delay(33)
            }
        }
    }

    fun leftClick() = send(SamsungPayloads.mouseClick(right = false))
    fun rightClick() = send(SamsungPayloads.mouseClick(right = true))

    // ------------------------------------------------------------------ keyboard

    /** Replaces the content of the focused text box on the TV with [text]. */
    fun sendText(text: String) {
        send(SamsungPayloads.inputString(text))
    }

    /** Closes the TV keyboard, submitting the text (like pressing Done/Go). */
    fun submitText() = send(SamsungPayloads.inputEnd())

    // ------------------------------------------------------------------ apps

    data class TvApp(val name: String, val id: String)

    /** Common app ids on Tizen; ids can vary by region/model, so launching falls back to the REST API. */
    val apps = listOf(
        TvApp("YouTube", "111299001912"),
        TvApp("Netflix", "3201907018807"),
        TvApp("Prime Video", "3201910019365"),
        TvApp("Disney+", "3201901017640"),
        TvApp("Plex", "3201512006963"),
        TvApp("Spotify", "3201606009684"),
        TvApp("Internet", "org.tizen.browser"),
    )

    fun launchApp(appId: String, deepLink: String? = null) {
        send(SamsungPayloads.launchApp(appId, nativeLaunch = appId.contains('.'), metaTag = deepLink))
        val ip = _tv.value?.ip ?: return
        if (deepLink == null) {
            scope.launch {
                runCatching {
                    client.newCall(
                        Request.Builder().url("http://$ip:8001/api/v2/applications/$appId").post(ByteArray(0).toRequestBody()).build(),
                    ).execute().close()
                }
            }
        }
    }

    /** Opens [url] in the TV's built-in web browser. */
    fun openInTvBrowser(url: String) = launchApp("org.tizen.browser", url)

    // ------------------------------------------------------------------ power

    /** Turns the TV on with Wake-on-LAN (TV must have "Power On with Mobile" / network standby enabled). */
    fun wake(tv: SamsungTv) {
        val mac = tv.mac ?: run { _message.value = "This TV didn't report its MAC address, so it can't be woken remotely."; return }
        scope.launch {
            runCatching {
                val packet = SamsungPayloads.magicPacket(mac)
                DatagramSocket().use { s ->
                    s.broadcast = true
                    repeat(3) {
                        s.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), 9))
                        s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(tv.ip), 9))
                    }
                }
                _message.value = "Wake signal sent. Connecting in a few seconds…"
                delay(6000)
                connect(tv)
            }.onFailure { _message.value = "Couldn't send wake signal: ${it.message}" }
        }
    }
}
