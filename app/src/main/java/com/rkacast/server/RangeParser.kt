package com.rkacast.server

/** Parsed HTTP byte range, inclusive [start, end]. */
data class ByteRange(val start: Long, val end: Long, val partial: Boolean) {
    val length get() = end - start + 1
}

object RangeParser {
    /**
     * Parses a Range header against a resource of [total] bytes.
     * Returns a full-content range when there's no (or an unsupported) header, and null when
     * the range is unsatisfiable (→ 416).
     */
    fun parse(header: String?, total: Long): ByteRange? {
        if (total <= 0) return if (header == null) ByteRange(0, -1, false) else null
        if (header == null || !header.trim().startsWith("bytes=")) return ByteRange(0, total - 1, false)
        val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
        val s = spec.substringBefore('-').trim()
        val e = spec.substringAfter('-', "").trim()
        val start: Long
        var end = total - 1
        if (s.isEmpty()) {
            val n = e.toLongOrNull() ?: return null
            if (n <= 0) return null
            start = (total - n).coerceAtLeast(0)
        } else {
            start = s.toLongOrNull() ?: return null
            if (e.isNotEmpty()) end = (e.toLongOrNull() ?: return null).coerceAtMost(total - 1)
        }
        if (start >= total || start > end) return null
        return ByteRange(start, end, true)
    }
}
