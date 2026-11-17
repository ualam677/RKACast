package com.rkacast

import com.rkacast.server.SubtitleConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleConverterTest {
    @Test
    fun srtToVtt() {
        val srt = "1\r\n00:00:01,500 --> 00:00:03,250\r\nHello\r\nWorld\r\n\r\n2\r\n00:01:02,000 --> 00:01:04,000\r\nBye\r\n"
        val vtt = SubtitleConverter.toVtt(srt)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:03.250"))
        assertTrue(vtt.contains("00:01:02.000 --> 00:01:04.000"))
        assertTrue(vtt.contains("Hello\nWorld"))
        assertFalse(vtt.contains(","))
    }

    @Test
    fun vttPassThrough() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n"
        assertEquals(vtt, SubtitleConverter.toVtt(vtt))
    }

    @Test
    fun assToVtt() {
        val ass = """
            [Script Info]
            Title: test
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.10,0:00:07.00,Default,,0,0,0,,{\an8\pos(10,20)}Top line\NSecond, with comma
            Comment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,ignored
            Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,First{\i1} italic{\i0}
            Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100{\p0}
        """.trimIndent()
        assertTrue(SubtitleConverter.isAss(ass))
        val vtt = SubtitleConverter.toVtt(ass)
        assertTrue(vtt.startsWith("WEBVTT"))
        // Sorted by start time
        val first = vtt.indexOf("00:00:01.000 --> 00:00:02.500")
        val second = vtt.indexOf("00:00:05.100 --> 00:00:07.000")
        assertTrue("first cue missing:\n$vtt", first > 0)
        assertTrue("second cue missing:\n$vtt", second > first)
        assertTrue(vtt.contains("First italic"))
        assertTrue(vtt.contains("Top line\nSecond, with comma"))
        assertFalse(vtt.contains("{"))
        assertFalse(vtt.contains("ignored"))
        assertFalse("drawing commands should be dropped", vtt.contains("m 0 0 l"))
    }

    @Test
    fun decodeStripsBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "1\n".toByteArray()
        assertEquals("1\n", SubtitleConverter.decode(bytes))
    }
}
