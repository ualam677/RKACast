package com.rkacast.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class TorrentMode { BUILT_IN, STREMIO_SERVER }

@Serializable
data class HistoryEntry(val url: String, val title: String, val time: Long)

/** Small SharedPreferences-backed settings store exposed as StateFlows for Compose. */
object Settings {
    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    private val _torrentMode = MutableStateFlow(TorrentMode.BUILT_IN)
    val torrentMode: StateFlow<TorrentMode> = _torrentMode

    private val _stremioServer = MutableStateFlow("http://127.0.0.1:11470")
    val stremioServer: StateFlow<String> = _stremioServer

    private val _proxyByDefault = MutableStateFlow(false)
    val proxyByDefault: StateFlow<Boolean> = _proxyByDefault

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    private val _stremioAuthKey = MutableStateFlow<String?>(null)
    val stremioAuthKey: StateFlow<String?> = _stremioAuthKey

    private val _subtitleScale = MutableStateFlow(1.0f)
    val subtitleScale: StateFlow<Float> = _subtitleScale

    private val _subtitleLang = MutableStateFlow("eng")
    /** Preferred subtitle language (ISO 639-2, as used by OpenSubtitles), auto-selected when available. */
    val subtitleLang: StateFlow<String> = _subtitleLang

    fun setSubtitleScale(v: Float) {
        _subtitleScale.value = v
        prefs.edit().putFloat("subtitleScale", v).apply()
    }

    fun setSubtitleLang(v: String) {
        _subtitleLang.value = v
        prefs.edit().putString("subtitleLang", v).apply()
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("rkacast", Context.MODE_PRIVATE)
        _torrentMode.value = runCatching {
            TorrentMode.valueOf(prefs.getString("torrentMode", TorrentMode.BUILT_IN.name)!!)
        }.getOrDefault(TorrentMode.BUILT_IN)
        _stremioServer.value = prefs.getString("stremioServer", _stremioServer.value)!!
        _proxyByDefault.value = prefs.getBoolean("proxyByDefault", false)
        _stremioAuthKey.value = prefs.getString("stremioAuthKey", null)
        _subtitleScale.value = prefs.getFloat("subtitleScale", 1.0f)
        _subtitleLang.value = prefs.getString("subtitleLang", "eng")!!
        _history.value = runCatching {
            json.decodeFromString<List<HistoryEntry>>(prefs.getString("history", "[]")!!)
        }.getOrDefault(emptyList())
    }

    fun setTorrentMode(mode: TorrentMode) {
        _torrentMode.value = mode
        prefs.edit().putString("torrentMode", mode.name).apply()
    }

    fun setStremioServer(url: String) {
        val clean = url.trim().trimEnd('/')
        _stremioServer.value = clean
        prefs.edit().putString("stremioServer", clean).apply()
    }

    fun setProxyByDefault(value: Boolean) {
        _proxyByDefault.value = value
        prefs.edit().putBoolean("proxyByDefault", value).apply()
    }

    fun setStremioAuthKey(key: String?) {
        _stremioAuthKey.value = key
        prefs.edit().putString("stremioAuthKey", key).apply()
    }

    fun addHistory(url: String, title: String) {
        val list = listOf(HistoryEntry(url, title, System.currentTimeMillis())) +
            _history.value.filter { it.url != url }
        _history.value = list.take(50)
        prefs.edit().putString("history", json.encodeToString(_history.value)).apply()
    }

    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().putString("history", "[]").apply()
    }
}
