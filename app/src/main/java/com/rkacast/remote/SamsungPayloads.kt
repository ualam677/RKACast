package com.rkacast.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Pure builders for the Samsung Tizen remote WebSocket messages (unit-tested). */
object SamsungPayloads {
    private fun control(params: JsonObject) = buildJsonObject {
        put("method", "ms.remote.control"); put("params", params)
    }

    fun connectUrl(ip: String, appName: String, token: String?, secure: Boolean): String {
        val name = Base64.getEncoder().encodeToString(appName.toByteArray())
        return if (secure) {
            val tok = token?.let { "&token=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
            "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name$tok"
        } else {
            "ws://$ip:8001/api/v2/channels/samsung.remote.control?name=$name"
        }
    }

    /** cmd: Click, Press or Release */
    fun key(cmd: String, code: String) = control(buildJsonObject {
        put("Cmd", cmd); put("DataOfCmd", code); put("Option", "false"); put("TypeOfRemote", "SendRemoteKey")
    })

    fun mouseMove(dx: Int, dy: Int) = control(buildJsonObject {
        put("Cmd", "Move")
        putJsonObject("Position") { put("x", dx); put("y", dy); put("Time", "0") }
        put("TypeOfRemote", "ProcessMouseDevice")
    })

    fun mouseClick(right: Boolean) = control(buildJsonObject {
        put("Cmd", if (right) "RightClick" else "LeftClick"); put("TypeOfRemote", "ProcessMouseDevice")
    })

    fun inputString(text: String) = control(buildJsonObject {
        put("Cmd", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
        put("DataOfCmd", "base64")
        put("TypeOfRemote", "SendInputString")
    })

    fun inputEnd() = control(buildJsonObject { put("TypeOfRemote", "SendInputEnd") })

    fun launchApp(appId: String, nativeLaunch: Boolean, metaTag: String?) = buildJsonObject {
        put("method", "ms.channel.emit")
        putJsonObject("params") {
            put("event", "ed.apps.launch")
            put("to", "host")
            putJsonObject("data") {
                put("appId", appId)
                put("action_type", if (nativeLaunch) "NATIVE_LAUNCH" else "DEEP_LINK")
                if (metaTag != null) put("metaTag", metaTag)
            }
        }
    }

    fun installedApps() = buildJsonObject {
        put("method", "ms.channel.emit")
        putJsonObject("params") { put("event", "ed.installedApp.get"); put("to", "host") }
    }

    /** Wake-on-LAN magic packet: 6×0xFF followed by the MAC repeated 16 times. */
    fun magicPacket(mac: String): ByteArray {
        val macBytes = mac.split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
        require(macBytes.size == 6) { "Bad MAC address: $mac" }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
        return packet
    }
}
