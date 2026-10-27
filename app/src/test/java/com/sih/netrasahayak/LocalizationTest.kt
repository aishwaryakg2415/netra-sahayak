package com.sih.netrasahayak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every language offered in the picker must translate every string, with the
 * same format placeholders - a missing %1$s would crash or garble a message
 * shown to a health worker.
 */
class LocalizationTest {

    private val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
    private val stringPattern = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%\d+\$[sd]""")

    private fun strings(dir: String): Map<String, String> =
        stringPattern.findAll(File(res, "$dir/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private val locales = listOf("hi", "mr", "ta", "te", "bn", "gu", "es")

    @Test
    fun everyLocaleTranslatesEveryString() {
        val base = strings("values")
        assertTrue(base.isNotEmpty())
        for (lang in locales) {
            val missing = base.keys - strings("values-$lang").keys
            assertEquals("values-$lang is missing $missing", emptySet<String>(), missing)
        }
    }

    @Test
    fun placeholdersMatchEnglish() {
        val base = strings("values")
        for (lang in locales) {
            for ((key, value) in strings("values-$lang")) {
                val english = base[key] ?: continue
                assertEquals(
                    "values-$lang/$key placeholders",
                    placeholder.findAll(english).map { it.value }.sorted().toList(),
                    placeholder.findAll(value).map { it.value }.sorted().toList()
                )
            }
        }
    }

    @Test
    fun pickerLanguagesMatchLocaleConfig() {
        val config = File(res, "xml/locales_config.xml").readText()
        for (lang in locales + "en") assertTrue("locales_config lacks $lang", config.contains("\"$lang\""))
    }
}
