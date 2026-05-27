package com.ghostdebugger

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

@RunWith(JUnit4::class)
class AnalysisOrchestratorTestRunnerTest : BasePlatformTestCase() {

    private lateinit var service: GhostDebuggerService
    private lateinit var orchestrator: AnalysisOrchestrator

    override fun setUp() {
        super.setUp()
        service = GhostDebuggerService.getInstance(project)
        orchestrator = AnalysisOrchestrator.getInstance(project)
    }

    @Test
    fun testTestSuiteFinishedPromotesStaticIssues() {
        val testFile = myFixture.addFileToProject("src/Bar.kt", "class Bar {}")
        val fakeIssue = Issue(
            id = UUID.randomUUID().toString(),
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.WARNING,
            title = "Nullable variable access",
            description = "Variable may be null",
            filePath = testFile.virtualFile.path,
            line = 42,
            codeSnippet = "val x: String? = null\nprintln(x!!)",
            affectedNodes = emptyList()
        )

        // Pre-populate service with the issue
        service.updateIssues(listOf(fakeIssue))

        // Create mock AbstractTestProxy
        val mockRoot = mockk<AbstractTestProxy>()
        val mockLeaf = mockk<AbstractTestProxy>()

        // Stack trace containing our file and line
        val stacktrace = """
            java.lang.NullPointerException: Null safety violation
                at com.foo.Bar.method(Bar.kt:42)
                at com.foo.BarTest.testSomething(BarTest.kt:12)
        """.trimIndent()

        every { mockRoot.isLeaf } returns false
        every { mockRoot.children } returns listOf(mockLeaf)
        every { mockRoot.getChildren() } returns listOf(mockLeaf)
        every { mockRoot.stacktrace } returns null
        every { mockRoot.getStacktrace() } returns null

        every { mockLeaf.isLeaf } returns true
        every { mockLeaf.isDefect } returns true
        every { mockLeaf.stacktrace } returns stacktrace
        every { mockLeaf.getStacktrace() } returns stacktrace
        every { mockLeaf.children } returns emptyList()
        every { mockLeaf.getChildren() } returns emptyList()

        // Call the internal handleTestSuiteFinished
        val method = AnalysisOrchestrator::class.java.getDeclaredMethod(
            "handleTestSuiteFinished", 
            AbstractTestProxy::class.java
        )
        method.isAccessible = true
        method.invoke(orchestrator, mockRoot)

        // Assert the issue was updated
        val updatedIssues = service.currentIssues
        assertEquals(1, updatedIssues.size)
        val updatedIssue = updatedIssues.first()

        assertTrue(updatedIssue.sources.contains(IssueSource.RUNTIME_CONFIRMED))
        assertEquals(1.0, updatedIssue.confidence)
    }
}
