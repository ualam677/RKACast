package com.rkacast.playback

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One remembered item. Stremio items store ids (not stream URLs, which expire) so they can be
 * re-resolved when resumed; plain URL items store the URL itself.
 */
@Serializable
data class WatchEntry(
    val key: String,
    val title: String,
    val poster: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updated: Long = 0,
    val watched: Boolean = false,
    // Stremio
    val type: String? = null,
    val metaId: String? = null,
    val videoId: String? = null,
    val metaName: String? = null,
    // Plain URL
    val url: String? = null,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val resumable: Boolean get() = !watched && positionMs > 60_000
}

object WatchHistory {
    private const val MAX = 200
    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WatchEntry.serializer())

    private val _entries = MutableStateFlow<List<WatchEntry>>(emptyList())
    val entries: StateFlow<List<WatchEntry>> = _entries

    fun init(context: Context) {
        prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)
        _entries.value = runCatching { json.decodeFromString(serializer, prefs.getString("list", "[]")!!) }
            .getOrDefault(emptyList())
    }

    fun stremioKey(videoId: String) = "stremio:$videoId"
    fun urlKey(url: String) = "url:$url"

    fun get(key: String): WatchEntry? = _entries.value.firstOrNull { it.key == key }

    fun upsert(entry: WatchEntry) {
        _entries.value = (listOf(entry) + _entries.value.filter { it.key != entry.key }).take(MAX)
        save()
    }

    fun remove(key: String) {
        _entries.value = _entries.value.filter { it.key != key }
        save()
    }

    /** Unfinished items, most recent first (one per show: the latest episode). */
    fun continueWatching(): List<WatchEntry> =
        _entries.value.filter { it.resumable }
            .distinctBy { it.metaId ?: it.key }
            .take(20)

    private fun save() {
        prefs.edit().putString("list", json.encodeToString(serializer, _entries.value)).apply()
    }
}
