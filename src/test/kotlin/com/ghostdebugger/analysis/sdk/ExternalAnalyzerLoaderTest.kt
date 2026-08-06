package com.ghostdebugger.analysis.sdk

import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ExternalAnalyzerLoaderTest : BasePlatformTestCase() {

    private class StubExternalAnalyzer(
        override val name: String = "StubExternalAnalyzer",
        override val ruleId: String = "AEG-EXT-001",
        override val defaultSeverity: IssueSeverity = IssueSeverity.WARNING,
        override val description: String = "Stub external analyzer for testing",
        private val behavior: (AnalysisContext) -> List<Issue>
    ) : Analyzer {
        override fun analyze(context: AnalysisContext): List<Issue> = behavior(context)
    }

    fun `test runExternalAnalyzer stamps IssueSource EXTERNAL_SDK`() {
        val loader = ExternalAnalyzerLoader.getInstance(project)
        val dummyIssue = Issue(
            id = "ext-1",
            type = IssueType.CUSTOM_RULE,
            severity = IssueSeverity.WARNING,
            title = "External issue",
            description = "Description",
            filePath = "Main.kt"
        )
        val analyzer = StubExternalAnalyzer { listOf(dummyIssue) }
        val ctx = AnalysisContext(com.ghostdebugger.graph.InMemoryGraph(), project, emptyList())

        val issues = loader.runExternalAnalyzer(analyzer, ctx)
        assertEquals(1, issues.size)
        assertTrue(issues[0].sources.contains(IssueSource.EXTERNAL_SDK))
    }

    fun `test runExternalAnalyzer isolates generic exception and returns empty list`() {
        val loader = ExternalAnalyzerLoader.getInstance(project)
        val analyzer = StubExternalAnalyzer { throw RuntimeException("Faulty third-party code") }
        val ctx = AnalysisContext(com.ghostdebugger.graph.InMemoryGraph(), project, emptyList())

        val issues = loader.runExternalAnalyzer(analyzer, ctx)
        assertTrue(issues.isEmpty())
    }

    fun `test runExternalAnalyzer rethrows ProcessCanceledException immediately`() {
        val loader = ExternalAnalyzerLoader.getInstance(project)
        val analyzer = StubExternalAnalyzer { throw ProcessCanceledException() }
        val ctx = AnalysisContext(com.ghostdebugger.graph.InMemoryGraph(), project, emptyList())

        try {
            loader.runExternalAnalyzer(analyzer, ctx)
            fail("Expected ProcessCanceledException to be rethrown")
        } catch (e: ProcessCanceledException) {
            // Expected
        }
    }
}
