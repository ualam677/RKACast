package com.rkacast.server

import java.nio.charset.Charset

object SubtitleConverter {
    fun decode(bytes: ByteArray): String {
        // Strip UTF-8 BOM; fall back to Windows-1252 for legacy SRT files.
        val utf8 = String(bytes, Charsets.UTF_8)
        val text = if ('�' in utf8) String(bytes, Charset.forName("windows-1252")) else utf8
        return text.removePrefix("﻿")
    }

    /** Converts SRT or SSA/ASS to WebVTT. Input that is already VTT is returned unchanged. */
    fun toVtt(input: String): String {
        val text = input.replace("\r\n", "\n").replace('\r', '\n')
        if (text.trimStart().startsWith("WEBVTT")) return text
        if (isAss(text)) return assToVtt(text)
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

    fun isAss(text: String) = text.contains("[Script Info]", ignoreCase = true) ||
        (text.contains("[Events]", ignoreCase = true) && text.contains("Dialogue:"))

    /**
     * SSA/ASS → WebVTT. Keeps timing and text (including line breaks); drops styling/positioning
     * override tags like {\\an8\\pos(…)} which VTT renderers on TVs don't support.
     */
    fun assToVtt(text: String): String {
        data class Cue(val start: Long, val end: Long, val text: String)
        var inEvents = false
        var format = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        val cues = mutableListOf<Cue>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) { inEvents = line.equals("[Events]", ignoreCase = true); continue }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                format = line.substringAfter(':').split(',').map { it.trim() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val fields = line.substringAfter(':').split(',', limit = format.size)
            if (fields.size < format.size) continue
            fun field(name: String) = fields.getOrNull(format.indexOfFirst { it.equals(name, ignoreCase = true) })?.trim()
            val start = parseAssTime(field("Start")) ?: continue
            val end = parseAssTime(field("End")) ?: continue
            val body = (fields.last())
                .replace(Regex("\\{[^}]*\\}"), "")          // override tags
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                .replace(Regex("<[^>]*>"), "")
                .trim()
            // Drawing commands ({\\p1}) leave junk like "m 0 0 l ..." – skip them.
            if (body.isEmpty() || Regex("^m [-\\d. ]+ l ").containsMatchIn(body)) continue
            cues += Cue(start, end, body)
        }
        val sb = StringBuilder("WEBVTT\n\n")
        cues.sortedBy { it.start }.forEach { c ->
            sb.append(vttTime(c.start)).append(" --> ").append(vttTime(c.end)).append('\n')
                .append(c.text).append("\n\n")
        }
        return sb.toString()
    }

    /** "H:MM:SS.cc" → milliseconds */
    private fun parseAssTime(t: String?): Long? {
        if (t == null) return null
        val parts = t.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val sec = parts[2].toDoubleOrNull() ?: return null
        return h * 3_600_000 + m * 60_000 + (sec * 1000).toLong()
    }

    private fun vttTime(ms: Long) = String.format(
        java.util.Locale.US, "%02d:%02d:%02d.%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000,
    )
}
