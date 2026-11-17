package com.rkacast.stremio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fast search: IMDb's suggestion API answers in a few hundred ms and is typo-tolerant, so its results
 * are shown first; every addon catalog that supports search is queried in parallel and merged in as
 * each one answers. Slow addons are cut off after a few seconds, and results are cached.
 */
object SearchService {
    private const val ADDON_TIMEOUT_MS = 5000L
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val cache = object : LinkedHashMap<String, Results>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Results>?) = size > 30
    }

    data class Results(
        val movies: List<MetaPreview>,
        val series: List<MetaPreview>,
        val other: List<MetaPreview>,
        val complete: Boolean,
    ) {
        val isEmpty get() = movies.isEmpty() && series.isEmpty() && other.isEmpty()
    }

    /** Emits progressively better results; the last emission has complete = true. */
    fun search(addons: List<InstalledAddon>, rawQuery: String): Flow<Results> = channelFlow {
        val query = rawQuery.trim()
        val key = query.lowercase(Locale.US) + "|" + addons.joinToString { it.transportUrl }
        synchronized(cache) { cache[key] }?.let { send(it); return@channelFlow }

        val merged = LinkedHashMap<String, MetaPreview>()
        val lock = Mutex()
        var pending = 0

        suspend fun add(items: List<MetaPreview>, preferNew: Boolean) {
            val snapshot = lock.withLock {
                items.forEach { m ->
                    val existing = merged[m.id]
                    // Addon metadata (better posters) replaces IMDb's when both have the same title.
                    if (existing == null || (preferNew && m.poster != null)) merged[m.id] = m
                }
                pending--
                build(query, merged.values.toList(), complete = pending == 0)
            }
            send(snapshot)
            if (snapshot.complete) synchronized(cache) { cache[key] = snapshot }
        }

        val sources = addons.flatMap { a -> a.manifest.catalogs.filter { it.supportsSearch }.map { a to it } }
        pending = sources.size + 1

        launch {
            val r = withTimeoutOrNull(ADDON_TIMEOUT_MS) { runCatching { imdbSuggestions(query) }.getOrNull() } ?: emptyList()
            add(r, preferNew = false)
        }
        sources.forEach { (a, c) ->
            launch(Dispatchers.IO) {
                val r = withTimeoutOrNull(ADDON_TIMEOUT_MS) {
                    runCatching { StremioClient.catalog(a, c, search = query) }.getOrNull()
                } ?: emptyList()
                add(r, preferNew = true)
            }
        }
    }

    private fun build(query: String, all: List<MetaPreview>, complete: Boolean): Results {
        val ranked = all.sortedByDescending { score(query, it) }
        return Results(
            movies = ranked.filter { it.type == "movie" },
            series = ranked.filter { it.type == "series" },
            other = ranked.filter { it.type != "movie" && it.type != "series" },
            complete = complete,
        )
    }

    private suspend fun imdbSuggestions(query: String): List<MetaPreview> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val first = normalize(query).firstOrNull { it.isLetterOrDigit() } ?: 'x'
        val q = URLEncoder.encode(query.lowercase(Locale.US), "UTF-8").replace("+", "%20")
        val url = "https://v3.sg.media-imdb.com/suggestion/$first/$q.json"
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build())
            .execute().use { it.body?.string() } ?: return@withContext emptyList()
        val arr = StremioClient.json.parseToJsonElement(body).jsonObject["d"]?.jsonArray ?: return@withContext emptyList()
        arr.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!id.startsWith("tt")) return@mapNotNull null
            val type = when (o["qid"]?.jsonPrimitive?.contentOrNull) {
                "movie", "tvMovie", "short", "video", "tvSpecial" -> "movie"
                "tvSeries", "tvMiniSeries" -> "series"
                else -> return@mapNotNull null
            }
            val poster = o["i"]?.jsonObject?.get("imageUrl")?.jsonPrimitive?.contentOrNull
                ?.let { if (it.contains("._V1_")) it.substringBefore("._V1_") + "._V1_SX300.jpg" else it }
            MetaPreview(
                id = id,
                type = type,
                name = o["l"]?.jsonPrimitive?.contentOrNull ?: id,
                poster = poster,
                releaseInfo = o["y"]?.jsonPrimitive?.intOrNull?.toString(),
            )
        }
    }

    internal fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    internal fun score(query: String, m: MetaPreview): Double {
        val q = normalize(query)
        val n = normalize(m.name)
        var s = when {
            n == q -> 100.0
            n.startsWith(q) -> 80.0
            q.split(' ').all { it in n } -> 60.0
            q.split(' ').any { it.length > 2 && it in n } -> 30.0
            else -> 10.0
        }
        if (m.poster != null) s += 2
        m.releaseInfo?.take(4)?.toIntOrNull()?.let { s += (it - 1950).coerceIn(0, 80) / 40.0 }
        return s
    }
}
