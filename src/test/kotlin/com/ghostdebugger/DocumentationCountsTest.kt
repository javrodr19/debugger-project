package com.ghostdebugger

import com.ghostdebugger.fix.FixerRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The 2026-09 final-release audit's closing finding was that nothing pinned the documented
 * capability counts to the code, which is how "eleven analyzers" and "five fixers" survived in
 * three shipped surfaces (README, plugin.xml, site/index.html) while the registries held twelve
 * and eight. This test is that pin.
 *
 * Every expected count below is re-derived from the source it documents, not copied from a plan
 * or a prior doc pass — if a registry changes, this test fails here first, with a message that
 * names which doc(s) are now stale, rather than drifting silently the way the original claims did.
 */
class DocumentationCountsTest {

    private val readme = File("README.md").readText()
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()
    private val siteHtml = File("site/index.html").readText()
    // Lazy: only the inspection-count test reads it, so the other tests fail on their own
    // assertion rather than all failing on a shared FileNotFoundException before this file exists.
    private val implementationStatus by lazy { File("docs/IMPLEMENTATION_STATUS.md").readText() }

    @Test
    fun `fixer count in the docs matches the registry`() {
        val actual = FixerRegistry.all().size
        val claim = "$actual deterministic fixers"
        assertContains(readme, claim, message = "README.md does not state '$claim'")
        assertContains(pluginXml, claim, message = "plugin.xml <description> does not state '$claim'")
        assertContains(siteHtml, claim, message = "site/index.html does not state '$claim'")
    }

    @Test
    fun `inspection count in plugin xml matches the documented count`() {
        val registered = Regex("<localInspection").findAll(pluginXml).count()
        assertEquals(
            11,
            registered,
            "plugin.xml's <localInspection> count changed; update docs/IMPLEMENTATION_STATUS.md and this test together"
        )
        assertContains(
            implementationStatus,
            "$registered local inspection",
            message = "docs/IMPLEMENTATION_STATUS.md does not state the inspection count $registered"
        )
    }

    @Test
    fun `analyzer count is stated as twelve analyzers over eleven built-in rules everywhere`() {
        // "Eleven deterministic analyzers" undercounted the registry (12 entries: 11 built-in rule
        // IDs + CustomRuleAnalyzer, a dispatcher for user-authored YAML rules rather than a built-in
        // rule of its own). "Twelve analyzers" alone would overcount the rule-id total. Both numbers
        // must appear together on every marketing surface so a reader counting either number in the
        // code agrees with the doc.
        listOf(readme, pluginXml, siteHtml).forEach { text ->
            assertContains(text, "12 analyzers")
            assertContains(text, "11 built-in rules")
        }
    }

    @Test
    fun `no shipped surface still claims eleven analyzers or five fixers`() {
        listOf(readme, pluginXml, siteHtml).forEach { text ->
            listOf(
                "eleven deterministic analyzers", "Eleven deterministic analyzers",
                "five PSI-validated fixers", "Five PSI-validated fixers",
                "five deterministic fixers", "Five deterministic fixers",
                "Five core analyzers", "five core analyzers"
            ).forEach { claim ->
                assertFalse(text.contains(claim), "stale claim still present: $claim")
            }
        }
    }
}
