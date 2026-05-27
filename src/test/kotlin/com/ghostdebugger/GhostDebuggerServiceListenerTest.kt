package com.ghostdebugger

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class GhostDebuggerServiceListenerTest {

    @Test
    fun testIssuesListenerRegistrationAndNotification() {
        val project = mockk<Project>(relaxed = true)
        val service = GhostDebuggerService(project)

        val received = mutableListOf<List<Issue>>()
        val listener: (List<Issue>) -> Unit = { issues ->
            received.add(issues)
        }

        service.onIssuesUpdated(listener)

        val fakeIssue = Issue(
            id = UUID.randomUUID().toString(),
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.WARNING,
            title = "Fake issue",
            description = "Fake issue desc",
            filePath = "src/Foo.kt",
            line = 10,
            codeSnippet = "val foo = null",
            affectedNodes = emptyList()
        )

        service.updateIssues(listOf(fakeIssue))

        assertEquals(1, received.size)
        assertEquals(fakeIssue.id, received[0][0].id)

        // Remove listener
        service.removeIssuesListener(listener)
        service.updateIssues(emptyList())

        // Size should still be 1 (didn't get notified of update to emptyList)
        assertEquals(1, received.size)
    }

    @Test
    fun testListenerThrowingExceptionIsSwallowed() {
        val project = mockk<Project>(relaxed = true)
        val service = GhostDebuggerService(project)

        val listener: (List<Issue>) -> Unit = { _ ->
            throw RuntimeException("Throwing listener error")
        }

        service.onIssuesUpdated(listener)

        // This call should complete without throwing RuntimeException
        service.updateIssues(emptyList())

        // If we reach here, it successfully swallowed the exception
        assertTrue(true)
    }
}
