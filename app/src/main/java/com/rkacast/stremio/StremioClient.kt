package com.rkacast.stremio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Implements the Stremio addon protocol (manifest / catalog / meta / stream / subtitles) and account sync. */
object StremioClient {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val API = "https://api.strem.io/api"

    fun normalizeAddonUrl(input: String): String {
        var u = input.trim()
        if (u.startsWith("stremio://")) u = "https://" + u.removePrefix("stremio://")
        if (!u.startsWith("http")) u = "https://$u"
        if (!u.endsWith("manifest.json")) u = u.trimEnd('/') + "/manifest.json"
        return u
    }

    /** Cancellable GET: if the coroutine is cancelled (e.g. a search timeout) the HTTP call is aborted too. */
    private suspend fun get(url: String): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(Request.Builder().url(url).build())
        val started = System.currentTimeMillis()
        val label = com.rkacast.data.DebugLog.hostOnly(url) + " " + url.substringAfter("/manifest.json", "").ifBlank {
            Regex("/(catalog|meta|stream|subtitles)/[^?]*").find(url)?.value ?: ""
        }
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) com.rkacast.data.DebugLog.w("Stremio", "GET $label failed", e)
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                // Always resume the continuation, whatever happens in here.
                val result = runCatching {
                    response.use { r ->
                        com.rkacast.data.DebugLog.i("Stremio", "GET $label -> ${r.code} in ${System.currentTimeMillis() - started}ms")
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
                        r.body?.string() ?: throw IOException("Empty response")
                    }
                }
                if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
        })
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun fetchManifest(url: String): InstalledAddon {
        val normalized = normalizeAddonUrl(url)
        val manifest = json.decodeFromString<Manifest>(get(normalized))
        return InstalledAddon(normalized, manifest)
    }

    suspend fun catalog(addon: InstalledAddon, cat: CatalogDef, search: String? = null, skip: Int = 0): List<MetaPreview> {
        val extras = buildList {
            if (search != null) add("search=${enc(search)}")
            if (skip > 0) add("skip=$skip")
        }
        val extraPath = if (extras.isEmpty()) "" else "/" + extras.joinToString("&")
        val url = "${addon.baseUrl}/catalog/${cat.type}/${enc(cat.id)}$extraPath.json"
        return json.decodeFromString<CatalogResponse>(get(url)).metas
    }

    suspend fun meta(addons: List<InstalledAddon>, type: String, id: String): Meta? {
        for (a in addons.filter { it.manifest.supports("meta", type, id) }) {
            val m = runCatching {
                json.decodeFromString<MetaResponse>(get("${a.baseUrl}/meta/$type/${enc(id)}.json")).meta
            }.getOrNull()
            if (m != null) return m
        }
        return null
    }

    suspend fun streams(addons: List<InstalledAddon>, type: String, id: String): List<AddonStream> = coroutineScope {
        addons.filter { it.manifest.supports("stream", type, id) }.map { a ->
            async {
                runCatching {
                    json.decodeFromString<StreamResponse>(get("${a.baseUrl}/stream/$type/${enc(id)}.json"))
                        .streams.map { AddonStream(a.manifest.name, it) }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    suspend fun subtitles(addons: List<InstalledAddon>, type: String, id: String): List<Subtitle> = coroutineScope {
        addons.filter { it.manifest.supports("subtitles", type, id) }.map { a ->
            async {
                runCatching {
                    json.decodeFromString<SubtitleResponse>(get("${a.baseUrl}/subtitles/$type/${enc(id)}.json")).subtitles
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    // ---------------------------------------------------------------- Stremio account

    private suspend fun api(method: String, body: JsonObject): JsonObject =
        apiRaw(method, body) as? JsonObject ?: throw IOException("Unexpected response from Stremio")

    /** Like [api] but returns the raw "result" element (some methods return arrays). */
    private suspend fun apiRaw(method: String, body: JsonObject): kotlinx.serialization.json.JsonElement = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$API/$method")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val obj = json.parseToJsonElement(r.body?.string() ?: "{}").jsonObject
            obj["error"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { err ->
                val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()
                throw IOException(msg)
            }
            obj["result"] ?: throw IOException("Unexpected response from Stremio")
        }
    }

    /** Read-only: items in the user's Stremio library (most recently watched first). */
    suspend fun library(authKey: String): List<LibraryItem> {
        val result = apiRaw("datastoreGet", buildJsonObject {
            put("authKey", authKey); put("collection", "libraryItem"); put("all", true)
            put("ids", kotlinx.serialization.json.JsonArray(emptyList()))
        })
        com.rkacast.data.DebugLog.i("Stremio", "Library raw: ${result.toString().take(600)}")
        val arr = result as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { runCatching { json.decodeFromJsonElement(LibraryItem.serializer(), it) }.getOrNull() }
            .filter { !it.removed && !it.temp && (it.type == "movie" || it.type == "series") }
            .sortedByDescending { it.state?.lastWatched ?: it.mtime ?: "" }
    }

    /** Logs in to a Stremio account and returns the authKey. */
    suspend fun login(email: String, password: String): String {
        val result = api("login", buildJsonObject {
            put("type", "Login"); put("email", email); put("password", password); put("facebook", false)
        })
        return result["authKey"]?.jsonPrimitive?.contentOrNull ?: throw IOException("No authKey returned")
    }

    /** Returns the addons installed on the user's Stremio account. */
    suspend fun accountAddons(authKey: String): List<InstalledAddon> {
        val result = api("addonCollectionGet", buildJsonObject {
            put("type", "AddonCollectionGet"); put("authKey", authKey); put("update", true)
        })
        return result["addons"]?.jsonArray?.mapNotNull {
            runCatching { json.decodeFromJsonElement(InstalledAddon.serializer(), it) }.getOrNull()
        } ?: emptyList()
    }
}
