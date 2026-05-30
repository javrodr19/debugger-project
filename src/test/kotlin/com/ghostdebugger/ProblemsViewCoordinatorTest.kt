package com.ghostdebugger

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.Problem
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.*
import java.util.UUID

class ProblemsViewCoordinatorTest : BasePlatformTestCase() {

    private lateinit var mockWolf: WolfTheProblemSolver
    private lateinit var fakeFile: VirtualFile

    override fun setUp() {
        super.setUp()
        mockWolf = mockk(relaxed = true)
        mockkStatic(WolfTheProblemSolver::class)
        every { WolfTheProblemSolver.getInstance(project) } returns mockWolf
        val psiFile = myFixture.configureByText("x.ts", "const x = 1;")
        fakeFile = psiFile.virtualFile
    }

    override fun tearDown() {
        unmockkStatic(WolfTheProblemSolver::class)
        super.tearDown()
    }

    private fun issue(filePath: String): Issue = Issue(
        id = UUID.randomUUID().toString(),
        type = IssueType.NULL_SAFETY,
        severity = IssueSeverity.ERROR,
        title = "test title",
        description = "desc",
        filePath = filePath,
        line = 1,
        codeSnippet = "snippet",
        affectedNodes = emptyList()
    )

    fun testProblemsViewCoordinatorReportsAndClears() {
        val coordinator = ProblemsViewCoordinator.getInstance(project)
        val service = GhostDebuggerService.getInstance(project)
        
        val issue = issue(fakeFile.url)
        val mockProblem = mockk<Problem>()
        every { mockWolf.convertToProblem(fakeFile, 1, 0, arrayOf(issue.title)) } returns mockProblem

        coordinator.start()
        try {
            // Update issues list to trigger Wolf report
            service.updateIssues(listOf(issue))
            
            // Pump EDT to run the invokeLater callback
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            verify {
                mockWolf.convertToProblem(fakeFile, 1, 0, arrayOf(issue.title))
                mockWolf.reportProblems(fakeFile, listOf(mockProblem))
            }

            // Clear issues
            service.updateIssues(emptyList())
            
            // Pump EDT to run the clear invokeLater callback
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            verify {
                mockWolf.clearProblems(fakeFile)
            }
        } finally {
            coordinator.dispose()
        }
    }
}
