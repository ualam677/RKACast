package com.rkacast

import com.rkacast.data.LanguageUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageUtilsTest {
    @Test
    fun normalizesCodes() {
        assertEquals("eng", LanguageUtils.toIso3("en"))
        assertEquals("eng", LanguageUtils.toIso3("en-US"))
        assertEquals("eng", LanguageUtils.toIso3("ENG"))
        assertEquals("spa", LanguageUtils.toIso3("es_MX"))
        assertEquals("fra", LanguageUtils.toIso3("fre"))
        assertEquals("deu", LanguageUtils.toIso3("de"))
        assertNull(LanguageUtils.toIso3(""))
        assertNull(LanguageUtils.toIso3(null))
    }

    @Test
    fun comparesLanguages() {
        assertTrue(LanguageUtils.sameLanguage("en-GB", "eng"))
        assertTrue(LanguageUtils.sameLanguage("ger", "de"))
        assertFalse(LanguageUtils.sameLanguage("ja", "eng"))
        assertFalse(LanguageUtils.sameLanguage(null, "eng"))
    }
}
