package com.rkacast

import com.rkacast.remote.SamsungPayloads
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SamsungPayloadsTest {
    @Test
    fun keyClick() {
        val p = SamsungPayloads.key("Click", "KEY_HOME")
        assertEquals("ms.remote.control", p["method"]!!.jsonPrimitive.content)
        val params = p["params"]!!.jsonObject
        assertEquals("Click", params["Cmd"]!!.jsonPrimitive.content)
        assertEquals("KEY_HOME", params["DataOfCmd"]!!.jsonPrimitive.content)
        assertEquals("SendRemoteKey", params["TypeOfRemote"]!!.jsonPrimitive.content)
    }

    @Test
    fun inputStringIsBase64() {
        val params = SamsungPayloads.inputString("héllo")["params"]!!.jsonObject
        val decoded = String(Base64.getDecoder().decode(params["Cmd"]!!.jsonPrimitive.content), Charsets.UTF_8)
        assertEquals("héllo", decoded)
        assertEquals("base64", params["DataOfCmd"]!!.jsonPrimitive.content)
    }

    @Test
    fun mouseMove() {
        val params = SamsungPayloads.mouseMove(5, -3)["params"]!!.jsonObject
        val pos = params["Position"]!!.jsonObject
        assertEquals("5", pos["x"]!!.jsonPrimitive.content)
        assertEquals("-3", pos["y"]!!.jsonPrimitive.content)
        assertEquals("ProcessMouseDevice", params["TypeOfRemote"]!!.jsonPrimitive.content)
    }

    @Test
    fun launchBrowserWithUrl() {
        val p = SamsungPayloads.launchApp("org.tizen.browser", nativeLaunch = true, metaTag = "https://a.b")
        val data = p["params"]!!.jsonObject["data"]!!.jsonObject
        assertEquals("NATIVE_LAUNCH", data["action_type"]!!.jsonPrimitive.content)
        assertEquals("https://a.b", data["metaTag"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun connectUrls() {
        val secure = SamsungPayloads.connectUrl("10.0.0.5", "RKACast", "12345", secure = true)
        assertTrue(secure.startsWith("wss://10.0.0.5:8002/"))
        assertTrue(secure.contains("name=" + Base64.getEncoder().encodeToString("RKACast".toByteArray())))
        assertTrue(secure.endsWith("&token=12345"))
        val plain = SamsungPayloads.connectUrl("10.0.0.5", "RKACast", "12345", secure = false)
        assertTrue(plain.startsWith("ws://10.0.0.5:8001/"))
    }

    @Test
    fun magicPacket() {
        val p = SamsungPayloads.magicPacket("AA:BB:CC:DD:EE:FF")
        assertEquals(102, p.size)
        for (i in 0 until 6) assertEquals(0xFF.toByte(), p[i])
        assertEquals(0xAA.toByte(), p[6])
        assertEquals(0xFF.toByte(), p[101])
    }
}
