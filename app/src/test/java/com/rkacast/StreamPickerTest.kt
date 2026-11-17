package com.rkacast

import com.rkacast.playback.StreamPicker
import com.rkacast.stremio.AddonStream
import com.rkacast.stremio.BehaviorHints
import com.rkacast.stremio.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamPickerTest {
    private fun s(addon: String, binge: String?, url: String? = "http://x/$addon/$binge", yt: Boolean = false) =
        AddonStream(addon, if (yt) Stream(ytId = "abc") else Stream(url = url, behaviorHints = BehaviorHints(bingeGroup = binge)))

    @Test
    fun prefersSameAddonAndBingeGroup() {
        val list = listOf(s("Other", "g1"), s("Torrentio", "720p"), s("Torrentio", "1080p"))
        assertEquals("1080p", StreamPicker.choose(list, "Torrentio", "1080p")?.stream?.behaviorHints?.bingeGroup)
    }

    @Test
    fun fallsBackToSameAddonThenAnything() {
        val list = listOf(s("Other", "g1"), s("Torrentio", "720p"))
        assertEquals("720p", StreamPicker.choose(list, "Torrentio", "4k")?.stream?.behaviorHints?.bingeGroup)
        assertEquals("Other", StreamPicker.choose(list, "Missing", null)?.addonName)
    }

    @Test
    fun skipsUnplayable() {
        assertNull(StreamPicker.choose(listOf(s("A", null, yt = true)), "A", null))
    }
}
