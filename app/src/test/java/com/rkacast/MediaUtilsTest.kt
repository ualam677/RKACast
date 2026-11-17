package com.rkacast

import com.rkacast.data.MediaUtils
import com.rkacast.torrent.TorrentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUtilsTest {
    @Test
    fun mimeTypes() {
        assertEquals("video/x-matroska", MediaUtils.mimeFor("http://x/a.MKV?token=1"))
        assertEquals("application/x-mpegURL", MediaUtils.mimeFor("https://x/master.m3u8"))
        assertEquals("video/mp4", MediaUtils.mimeFor("https://x/noext"))
    }

    @Test
    fun mediaDetection() {
        assertTrue(MediaUtils.looksLikeMedia("https://cdn/x/video.mp4?sig=1"))
        assertTrue(MediaUtils.looksLikeMedia("https://cdn/hls/index.m3u8"))
        assertFalse(MediaUtils.looksLikeMedia("https://site.com/watch/123"))
    }

    @Test
    fun torrentInputs() {
        assertTrue(MediaUtils.isMagnet(" magnet:?xt=urn:btih:abc"))
        assertTrue(MediaUtils.isInfoHash("ABCDEF0123456789ABCDEF0123456789ABCDEF01"))
        assertFalse(MediaUtils.isInfoHash("xyz"))
        val magnet = TorrentEngine.buildMagnet("ABCDEF0123456789ABCDEF0123456789ABCDEF01", listOf("udp://t:1"), "My Movie")
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", TorrentEngine.infoHashFromMagnet(magnet))
        assertTrue(magnet.contains("&tr=udp%3A%2F%2Ft%3A1"))
        assertTrue(magnet.contains("&dn=My+Movie"))
    }

    @Test
    fun formatting() {
        assertEquals("1:02:03", MediaUtils.formatTime(3_723_000))
        assertEquals("0:05", MediaUtils.formatTime(5_000))
        assertEquals("1.5 MB", MediaUtils.formatBytes(1_572_864))
    }
}
