package com.rkacast.server

import java.nio.charset.Charset

object SubtitleConverter {
    fun decode(bytes: ByteArray): String {
        // Strip UTF-8 BOM; fall back to Windows-1252 for legacy SRT files.
        val utf8 = String(bytes, Charsets.UTF_8)
        val text = if ('�' in utf8) String(bytes, Charset.forName("windows-1252")) else utf8
        return text.removePrefix("﻿")
    }

    /** Converts SRT to WebVTT. Input that is already VTT is returned unchanged. */
    fun toVtt(input: String): String {
        val text = input.replace("\r\n", "\n").replace('\r', '\n')
        if (text.trimStart().startsWith("WEBVTT")) return text
        val sb = StringBuilder("WEBVTT\n\n")
        val timing = Regex("""(\d{1,2}:\d{2}:\d{2}),(\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}),(\d{3})""")
        for (line in text.lines()) {
            val m = timing.find(line)
            if (m != null) {
                sb.append(m.groupValues[1]).append('.').append(m.groupValues[2])
                    .append(" --> ")
                    .append(m.groupValues[3]).append('.').append(m.groupValues[4]).append('\n')
            } else {
                sb.append(line).append('\n')
            }
        }
        return sb.toString()
    }
}
