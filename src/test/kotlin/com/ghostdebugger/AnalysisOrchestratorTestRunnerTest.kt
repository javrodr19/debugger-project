package com.ghostdebugger

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.*
import java.util.UUID

class AnalysisOrchestratorTestRunnerTest : BasePlatformTestCase() {

    private fun issue(filePath: String, line: Int): Issue = Issue(
        id = UUID.randomUUID().toString(),
        type = IssueType.NULL_SAFETY,
        severity = IssueSeverity.ERROR,
        title = "Null reference",
        description = "desc",
        filePath = filePath,
        line = line,
        codeSnippet = "snippet",
        affectedNodes = emptyList()
    )

    fun testTestSuiteFinishedPromotesStaticIssues() {
        val orchestrator = AnalysisOrchestrator.getInstance(project)
        val service = GhostDebuggerService.getInstance(project)

        val filePath = "/src/bar.kt"
        val line = 42
        val fakeIssue = issue(filePath, line)
        service.updateIssues(listOf(fakeIssue))

        // Create mock failed leaf test proxy
        val mockLeaf = mockk<AbstractTestProxy>()
        every { mockLeaf.isLeaf } returns true
        every { mockLeaf.isPassed } returns false
        every { mockLeaf.stacktrace } returns "at com.foo.Bar.method(bar.kt:42)"
        every { mockLeaf.children } returns emptyList()

        // Create mock suite root proxy
        val mockRoot = mockk<AbstractTestProxy>()
        every { mockRoot.isLeaf } returns false
        every { mockRoot.isPassed } returns false
        every { mockRoot.children } returns listOf(mockLeaf)

        // Invoke testStatusListener hook directly
        orchestrator.handleTestSuiteFinished(mockRoot)

        // Assert issue is promoted
        val updatedIssues = service.currentIssues
        assertEquals(1, updatedIssues.size)
        val promoted = updatedIssues[0]
        assertTrue(promoted.sources.contains(IssueSource.RUNTIME_CONFIRMED))
        assertEquals(1.0, promoted.confidence)
    }
}
