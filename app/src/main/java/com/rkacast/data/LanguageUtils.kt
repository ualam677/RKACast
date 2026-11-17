package com.rkacast.data

import java.util.Locale

/** Language-code helpers shared by subtitle and audio selection (pure JVM, unit-tested). */
object LanguageUtils {
    /** "en", "en-US", "eng", "ENG" → "eng". Returns the lowercased input if it can't be mapped. */
    fun toIso3(code: String?): String? {
        val c = code?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val base = c.substringBefore('-').substringBefore('_').lowercase(Locale.US)
        if (base.length == 3) {
            // Bibliographic vs terminologic variants used by some files.
            return when (base) {
                "fre" -> "fra"; "ger" -> "deu"; "dut" -> "nld"; "chi" -> "zho"; "cze" -> "ces"
                "gre" -> "ell"; "per" -> "fas"; "rum" -> "ron"; "slo" -> "slk"; "alb" -> "sqi"
                "arm" -> "hye"; "baq" -> "eus"; "bur" -> "mya"; "geo" -> "kat"; "ice" -> "isl"
                "mac" -> "mkd"; "mao" -> "mri"; "may" -> "msa"; "wel" -> "cym"; "tib" -> "bod"
                else -> base
            }
        }
        return runCatching { Locale(base).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: base
    }

    fun sameLanguage(a: String?, b: String?): Boolean {
        val x = toIso3(a) ?: return false
        val y = toIso3(b) ?: return false
        return x == y
    }
}
