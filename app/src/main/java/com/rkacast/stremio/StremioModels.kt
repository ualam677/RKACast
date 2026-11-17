package com.rkacast.stremio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Manifest(
    val id: String,
    val name: String,
    val version: String = "",
    val description: String? = null,
    val logo: String? = null,
    val types: List<String> = emptyList(),
    val catalogs: List<CatalogDef> = emptyList(),
    val resources: List<JsonElement> = emptyList(),
    val idPrefixes: List<String>? = null,
) {
    /** Resources can be plain strings ("stream") or objects ({name, types, idPrefixes}). */
    fun supports(resource: String, type: String, id: String): Boolean {
        for (r in resources) {
            if (r is JsonPrimitive) {
                if (r.contentOrNull == resource) {
                    return type in types && prefixOk(idPrefixes, id)
                }
            } else if (r is JsonObject) {
                if (r["name"]?.jsonPrimitive?.contentOrNull != resource) continue
                val rTypes = r["types"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: types
                val rPrefixes = r["idPrefixes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: idPrefixes
                return type in rTypes && prefixOk(rPrefixes, id)
            }
        }
        return false
    }

    private fun prefixOk(prefixes: List<String>?, id: String) = prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
}

@Serializable
data class CatalogDef(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<ExtraDef> = emptyList(),
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList(),
) {
    val supportsSearch get() = extra.any { it.name == "search" } || "search" in extraSupported
    val requiresExtra get() = extra.any { it.isRequired } || extraRequired.isNotEmpty()
    val searchOnly get() = extra.any { it.name == "search" && it.isRequired } || "search" in extraRequired
}

@Serializable
data class ExtraDef(val name: String, val isRequired: Boolean = false, val options: List<String> = emptyList())

@Serializable
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val posterShape: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
)

@Serializable
data class Meta(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val videos: List<Video> = emptyList(),
)

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
) {
    val label get() = title ?: name ?: "Episode ${episode ?: ""}"
}

@Serializable
data class ProxyHeaders(val request: Map<String, String> = emptyMap())

@Serializable
data class BehaviorHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val proxyHeaders: ProxyHeaders? = null,
)

@Serializable
data class Stream(
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val sources: List<String> = emptyList(),
    val behaviorHints: BehaviorHints? = null,
) {
    val label get() = listOfNotNull(name, title ?: description).joinToString("\n").ifBlank { url ?: infoHash ?: "Stream" }
    val isTorrent get() = infoHash != null
    val trackers get() = sources.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") }
}

@Serializable
data class Subtitle(val id: String = "", val url: String, val lang: String = "")

@Serializable
data class CatalogResponse(val metas: List<MetaPreview> = emptyList())

@Serializable
data class MetaResponse(val meta: Meta? = null)

@Serializable
data class StreamResponse(val streams: List<Stream> = emptyList())

@Serializable
data class SubtitleResponse(val subtitles: List<Subtitle> = emptyList())

@Serializable
data class InstalledAddon(val transportUrl: String, val manifest: Manifest) {
    val baseUrl get() = transportUrl.removeSuffix("/manifest.json").trimEnd('/')
}

/** A stream together with the addon it came from, for display. */
data class AddonStream(val addonName: String, val stream: Stream)

@Serializable
data class LibraryState(
    val timeOffset: Double = 0.0,
    val duration: Double = 0.0,
    @kotlinx.serialization.SerialName("video_id") val videoId: String? = null,
    val lastWatched: String? = null,
    val timesWatched: Int = 0,
)

@Serializable
data class LibraryItem(
    @kotlinx.serialization.SerialName("_id") val id: String,
    val name: String = "",
    val type: String = "",
    val poster: String? = null,
    val removed: Boolean = false,
    val temp: Boolean = false,
    @kotlinx.serialization.SerialName("_mtime") val mtime: String? = null,
    val state: LibraryState? = null,
) {
    val progress: Float get() = state?.let { if (it.duration > 0) (it.timeOffset / it.duration).toFloat().coerceIn(0f, 1f) else 0f } ?: 0f
    fun toPreview() = MetaPreview(id = id, type = type, name = name, poster = poster)
}
