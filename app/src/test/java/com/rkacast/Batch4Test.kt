package com.rkacast

import com.rkacast.remote.SamsungPayloads
import com.rkacast.stremio.LibraryItem
import com.rkacast.stremio.StremioClient
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch4Test {
    @Test
    fun parsesInstalledApps() {
        val msg = StremioClient.json.parseToJsonElement(
            """{"event":"ed.installedApp.get","from":"host","data":{"data":[
                {"appId":"111299001912","app_type":2,"name":"YouTube"},
                {"appId":"org.tizen.browser","app_type":4,"name":"Internet"},
                {"name":"no id"}
            ]}}""",
        ).jsonObject
        val apps = SamsungPayloads.parseInstalledApps(msg)
        assertEquals(2, apps.size)
        assertEquals("YouTube", apps[0].name)
        assertEquals(false, apps[0].nativeLaunch)
        assertEquals(true, apps[1].nativeLaunch)
    }

    @Test
    fun parsesLibraryItem() {
        val item = StremioClient.json.decodeFromString(
            LibraryItem.serializer(),
            """{"_id":"tt0903747","name":"Breaking Bad","type":"series","poster":"p.jpg","removed":false,"temp":false,
                "_mtime":"2026-01-01T00:00:00.000Z","state":{"timeOffset":1200000,"duration":2400000.5,"video_id":"tt0903747:1:2","timesWatched":1}}""",
        )
        assertEquals("tt0903747", item.id)
        assertEquals("tt0903747:1:2", item.state?.videoId)
        assertTrue(item.progress in 0.49f..0.51f)
    }
}
