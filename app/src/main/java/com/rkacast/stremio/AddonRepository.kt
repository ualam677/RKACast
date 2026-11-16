package com.rkacast.stremio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer

/** Persists the user's installed Stremio addons. */
object AddonRepository {
    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

    /** One-tap suggestions shown in the Addons screen. */
    val suggested = listOf(
        "Cinemeta (movie & series catalog)" to CINEMETA,
        "Torrentio (torrent streams)" to "https://torrentio.strem.fun/manifest.json",
        "OpenSubtitles v3 (subtitles)" to "https://opensubtitles-v3.strem.io/manifest.json",
        "Public Domain Movies" to "https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json",
    )

    private lateinit var prefs: SharedPreferences
    private val serializer = ListSerializer(InstalledAddon.serializer())

    private val _addons = MutableStateFlow<List<InstalledAddon>>(emptyList())
    val addons: StateFlow<List<InstalledAddon>> = _addons

    fun init(context: Context) {
        prefs = context.getSharedPreferences("addons", Context.MODE_PRIVATE)
        _addons.value = runCatching {
            StremioClient.json.decodeFromString(serializer, prefs.getString("list", "[]")!!)
        }.getOrDefault(emptyList())
    }

    val isEmpty get() = _addons.value.isEmpty()

    suspend fun install(url: String): InstalledAddon {
        val addon = StremioClient.fetchManifest(url)
        add(addon)
        return addon
    }

    fun add(addon: InstalledAddon) {
        _addons.value = _addons.value.filter { it.manifest.id != addon.manifest.id } + addon
        save()
    }

    fun replaceAll(list: List<InstalledAddon>) {
        _addons.value = list.distinctBy { it.manifest.id }
        save()
    }

    fun remove(addon: InstalledAddon) {
        _addons.value = _addons.value.filter { it.transportUrl != addon.transportUrl }
        save()
    }

    fun move(addon: InstalledAddon, up: Boolean) {
        val list = _addons.value.toMutableList()
        val i = list.indexOf(addon)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        _addons.value = list
        save()
    }

    private fun save() {
        prefs.edit().putString("list", StremioClient.json.encodeToString(serializer, _addons.value)).apply()
    }
}
