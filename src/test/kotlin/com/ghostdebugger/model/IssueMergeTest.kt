package com.ghostdebugger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssueMergeTest {
    // Hand-run the same merge logic used inside AnalysisEngine.mergeIssues.
    private fun merge(issues: List<Issue>): List<Issue> =
        issues.groupBy { it.fingerprint() }.map { (_, group) ->
            val base = group.maxByOrNull { it.confidence ?: 0.0 } ?: group.first()
            base.copy(
                sources = group.flatMap { it.sources }.distinct(),
                providers = group.flatMap { it.providers }.distinct(),
                confidence = group.mapNotNull { it.confidence }.maxOrNull()
            )
        }

    private fun issue(
        ruleId: String? = "AEG-NULL-001",
        line: Int = 5,
        sources: List<IssueSource> = listOf(IssueSource.STATIC),
        providers: List<EngineProvider> = listOf(EngineProvider.STATIC),
        confidence: Double? = 1.0
    ) = Issue(
        id = "x", type = IssueType.NULL_SAFETY,
        severity = IssueSeverity.ERROR,
        title = "t", description = "d",
        filePath = "/src/A.tsx", line = line,
        ruleId = ruleId, sources = sources, providers = providers,
        confidence = confidence
    )

    @Test
    fun `merge unions sources and providers for matching fingerprints`() {
        val merged = merge(listOf(
            issue(sources = listOf(IssueSource.STATIC),
                  providers = listOf(EngineProvider.STATIC),
                  confidence = 1.0),
            issue(sources = listOf(IssueSource.AI_CLOUD),
                  providers = listOf(EngineProvider.OPENAI),
                  confidence = 0.8)
        ))
        assertEquals(1, merged.size)
        assertTrue(IssueSource.STATIC in merged[0].sources)
        assertTrue(IssueSource.AI_CLOUD in merged[0].sources)
        assertTrue(EngineProvider.STATIC in merged[0].providers)
        assertTrue(EngineProvider.OPENAI in merged[0].providers)
    }

    @Test
    fun `merge takes max confidence across the group`() {
        val merged = merge(listOf(
            issue(confidence = 0.2),
            issue(confidence = 0.9),
            issue(confidence = null)
        ))
        assertEquals(0.9, merged[0].confidence)
    }

    @Test
    fun `merge keeps issues with different fingerprints separate`() {
        val merged = merge(listOf(
            issue(line = 5),
            issue(line = 9)
        ))
        assertEquals(2, merged.size)
    }

    @Test
    fun `merge with single issue returns it unchanged except for distinct lists`() {
        val single = issue()
        val merged = merge(listOf(single))
        assertEquals(1, merged.size)
        assertEquals(single.fingerprint(), merged[0].fingerprint())
    }
}
