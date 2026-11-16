package com.rkacast.stremio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
 * Search across every addon catalog that supports search (in parallel), plus IMDb's suggestion API
 * which is fast and tolerant of typos and partial words. IMDb ids (tt…) are what Cinemeta and most
 * stream addons such as Torrentio use, so those results are directly playable.
 */
object SearchService {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    data class Results(val movies: List<MetaPreview>, val series: List<MetaPreview>, val other: List<MetaPreview>)

    suspend fun search(addons: List<InstalledAddon>, rawQuery: String): Results = coroutineScope {
        val query = rawQuery.trim()
        val catalogJobs = addons.flatMap { a -> a.manifest.catalogs.filter { it.supportsSearch }.map { a to it } }
            .map { (a, c) -> async { runCatching { StremioClient.catalog(a, c, search = query) }.getOrDefault(emptyList()) } }
        val imdbJob = async { runCatching { imdbSuggestions(query) }.getOrDefault(emptyList()) }

        val addonResults = catalogJobs.awaitAll().flatten()
        val imdb = imdbJob.await()

        // Addon results carry better posters/metadata; IMDb fills gaps and fixes typos.
        val merged = LinkedHashMap<String, MetaPreview>()
        (addonResults + imdb).forEach { m -> if (m.id !in merged) merged[m.id] = m }
        val ranked = merged.values.sortedByDescending { score(query, it) }

        Results(
            movies = ranked.filter { it.type == "movie" },
            series = ranked.filter { it.type == "series" },
            other = ranked.filter { it.type != "movie" && it.type != "series" },
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

    private fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Simple relevance: exact > prefix > all words present > partial, with a small bonus for recency. */
    private fun score(query: String, m: MetaPreview): Double {
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
