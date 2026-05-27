package com.ghostdebugger

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerServiceInstance
import io.mockk.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.UUID

@RunWith(JUnit4::class)
class ProblemsViewCoordinatorTest : BasePlatformTestCase() {

    private lateinit var mockWolf: WolfTheProblemSolver
    private lateinit var coordinator: ProblemsViewCoordinator
    private lateinit var service: GhostDebuggerService

    override fun setUp() {
        super.setUp()
        mockWolf = mockk(relaxed = true)
        
        // Override/Register mock WolfTheProblemSolver service
        project.registerServiceInstance(WolfTheProblemSolver::class.java, mockWolf)

        service = GhostDebuggerService.getInstance(project)
        coordinator = ProblemsViewCoordinator.getInstance(project)
        coordinator.start()
    }

    override fun tearDown() {
        if (::coordinator.isInitialized) {
            coordinator.dispose()
        }
        super.tearDown()
    }

    @Test
    fun testPublishIssuesToProblemsView() {
        val testFile = myFixture.addFileToProject("src/Foo.kt", "class Foo {}")
        val fakeIssue = Issue(
            id = UUID.randomUUID().toString(),
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.WARNING,
            title = "Nullable variable access",
            description = "Variable may be null",
            filePath = testFile.virtualFile.path,
            line = 1,
            codeSnippet = "class Foo {}",
            affectedNodes = emptyList()
        )

        // Trigger updates
        service.updateIssues(listOf(fakeIssue))

        // Wait/execute invokeLater EDT runnables
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // Verify reportProblems was called
        verify(exactly = 1) {
            mockWolf.reportProblems(testFile.virtualFile, any())
        }

        // Trigger clear updates
        service.updateIssues(emptyList())
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // Verify clearProblems was called
        verify(exactly = 1) {
            mockWolf.clearProblems(testFile.virtualFile)
        }
    }
}
