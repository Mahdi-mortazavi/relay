package io.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every string this app shows exists in English *and* Persian.
 *
 * The rule is in CLAUDE.md and the Windows side has enforced it since six keys
 * shipped missing there — someone who typed a code no phone was answering was
 * told "CodeNoDevice". Android had the same rule and nothing checking it, which
 * is a worse position than Windows was in: a string present in `values/` and
 * absent from `values-fa/` compiles, runs, passes every test, and shows English
 * to someone reading Persian. The compiler catches the English half for free —
 * an unresolved `R.string.x` will not build — so the half worth writing down is
 * the other one.
 */
class StringsCoverageTest {

    private val english = File(resources, "values/strings.xml").readText(Charsets.UTF_8)
    private val persian = File(resources, "values-fa/strings.xml").readText(Charsets.UTF_8)

    @Test
    fun `the two languages define the same strings`() {
        assertEquals(
            "English-only: ${(names(english) - names(persian)).sorted()}\n" +
                "Persian-only: ${(names(persian) - names(english)).sorted()}",
            names(english),
            names(persian),
        )
    }

    @Test
    fun `the two languages define the same plurals and arrays`() {
        // A missing plural is the same bug wearing a different tag, and there is
        // one behind the "N devices connected" line on the main screen.
        for (tag in listOf("plurals", "string-array")) {
            assertEquals("$tag differs between the two languages", names(english, tag), names(persian, tag))
        }
    }

    @Test
    fun `a translation takes the same arguments as its original`() {
        // This one is not cosmetic. String.format throws when the placeholders
        // do not line up, so a Persian string that drops the %1$s its English
        // original has crashes the screen it appears on — and only for the
        // people reading Persian, who are the ones this project ships to first.
        val originals = strings(english)
        val translations = strings(persian)
        val wrong = originals.keys
            .filter { it in translations }
            .filter { placeholders(originals.getValue(it)) != placeholders(translations.getValue(it)) }
            .map { "$it: en${placeholders(originals.getValue(it))} vs fa${placeholders(translations.getValue(it))}" }

        assertTrue("These translations take different arguments:\n  " + wrong.joinToString("\n  "), wrong.isEmpty())
    }

    private fun names(xml: String, tag: String = "string"): Set<String> =
        Regex("<" + Regex.escape(tag) + " name=\"([^\"]+)\"")
            .findAll(xml).map { it.groupValues[1] }.toSet()

    private fun strings(xml: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * Positional arguments, sorted so the order they appear in may differ —
     * Persian word order is not English word order, and %2$s coming first is a
     * translation doing its job.
     *
     * The dollar is written as a character class so that neither Kotlin nor the
     * regex engine can read it as something else: bare in a raw string it opens
     * a template, and bare in a pattern it anchors the end of input.
     */
    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+[${'$'}])?[a-zA-Z]""").findAll(text).map { it.value }.sorted()

    private companion object {
        /** `app/src/main/res`, found from the module directory Gradle runs in. */
        val resources: File by lazy {
            var here: File? = File(System.getProperty("user.dir")).absoluteFile
            while (here != null) {
                val candidate = File(here, "app/src/main/res/values/strings.xml")
                if (candidate.isFile) return@lazy File(here, "app/src/main/res")
                here = here.parentFile
            }
            error("Could not find app/src/main/res above ${System.getProperty("user.dir")}")
        }
    }
}
