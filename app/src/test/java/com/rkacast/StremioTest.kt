package com.rkacast

import com.rkacast.stremio.InstalledAddon
import com.rkacast.stremio.Manifest
import com.rkacast.stremio.MetaPreview
import com.rkacast.stremio.SearchService
import com.rkacast.stremio.StremioClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioTest {
    private val json = StremioClient.json

    @Test
    fun manifestSupportsStringResources() {
        val m = json.decodeFromString<Manifest>(
            """{"id":"a","name":"A","types":["movie","series"],"resources":["stream"],"idPrefixes":["tt"]}""",
        )
        assertTrue(m.supports("stream", "movie", "tt123"))
        assertFalse(m.supports("stream", "movie", "kitsu:1"))
        assertFalse(m.supports("meta", "movie", "tt123"))
        assertFalse(m.supports("stream", "tv", "tt123"))
    }

    @Test
    fun manifestSupportsObjectResources() {
        val m = json.decodeFromString<Manifest>(
            """{"id":"a","name":"A","types":["movie"],"resources":[
                 "catalog",
                 {"name":"meta","types":["series"],"idPrefixes":["kitsu"]},
                 {"name":"subtitles","types":["movie","series"]}
               ],"catalogs":[{"type":"movie","id":"top","extra":[{"name":"search"}]}]}""",
        )
        assertTrue(m.supports("meta", "series", "kitsu:5"))
        assertFalse(m.supports("meta", "movie", "kitsu:5"))
        assertTrue(m.supports("subtitles", "series", "tt1:1:2"))
        assertTrue(m.catalogs.first().supportsSearch)
    }

    @Test
    fun normalizeAddonUrl() {
        assertEquals("https://torrentio.strem.fun/manifest.json", StremioClient.normalizeAddonUrl("stremio://torrentio.strem.fun/manifest.json"))
        assertEquals("https://x.com/cfg/manifest.json", StremioClient.normalizeAddonUrl("x.com/cfg/"))
        assertEquals("https://x.com/manifest.json", StremioClient.normalizeAddonUrl("https://x.com/manifest.json"))
    }

    @Test
    fun searchScoringPrefersExactThenPrefix() {
        fun m(name: String) = MetaPreview(id = name, type = "movie", name = name)
        val q = "the matrix"
        val exact = SearchService.score(q, m("The Matrix"))
        val prefix = SearchService.score(q, m("The Matrix Reloaded"))
        val partial = SearchService.score(q, m("Matrix of Leadership"))
        val other = SearchService.score(q, m("Inception"))
        assertTrue(exact > prefix)
        assertTrue(prefix > partial)
        assertTrue(partial > other)
        assertEquals("amelie", SearchService.normalize("Amélie!"))
    }

    @Test
    fun streamsFromAddonAreParsed() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"streams":[
                {"name":"Torrentio\n1080p","title":"Movie.mkv","infoHash":"ABCDEF0123456789ABCDEF0123456789ABCDEF01","fileIdx":2,
                 "sources":["tracker:udp://t.example:80/announce","dht:abc"],
                 "behaviorHints":{"bingeGroup":"torrentio|1080p","filename":"Movie.mkv"}},
                {"url":"https://cdn.example/v.mp4","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://site"}}}}
            ]}""",
        ))
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val addon = InstalledAddon(
                "$base/manifest.json",
                json.decodeFromString("""{"id":"t","name":"Test","types":["movie"],"resources":["stream"]}"""),
            )
            val streams = StremioClient.streams(listOf(addon), "movie", "tt1")
            assertEquals(2, streams.size)
            val t = streams[0].stream
            assertTrue(t.isTorrent)
            assertEquals(2, t.fileIdx)
            assertEquals(listOf("udp://t.example:80/announce"), t.trackers)
            assertEquals("torrentio|1080p", t.behaviorHints?.bingeGroup)
            assertEquals("https://site", streams[1].stream.behaviorHints?.proxyHeaders?.request?.get("Referer"))
            assertEquals("/stream/movie/tt1.json", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}
