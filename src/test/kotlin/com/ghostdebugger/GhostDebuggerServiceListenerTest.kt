package com.ghostdebugger

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.UUID

class GhostDebuggerServiceListenerTest : BasePlatformTestCase() {

    private fun issue(): Issue = Issue(
        id = UUID.randomUUID().toString(),
        type = IssueType.NULL_SAFETY,
        severity = IssueSeverity.ERROR,
        title = "test",
        description = "desc",
        filePath = "/x.ts",
        line = 1,
        codeSnippet = "snippet",
        affectedNodes = emptyList()
    )

    fun testListenerSubscriptionAndNotification() {
        val service = GhostDebuggerService.getInstance(project)
        val fakeIssue = issue()
        var receivedIssues: List<Issue>? = null
        var callCount = 0

        val listener: (List<Issue>) -> Unit = { issues ->
            receivedIssues = issues
            callCount++
        }

        service.onIssuesUpdated(listener)
        try {
            service.updateIssues(listOf(fakeIssue))
            assertEquals(1, callCount)
            assertNotNull(receivedIssues)
            assertEquals(1, receivedIssues!!.size)
            assertEquals(fakeIssue.id, receivedIssues!![0].id)

            // Remove listener and check no further updates are received
            service.removeIssuesListener(listener)
            service.updateIssues(emptyList())
            assertEquals(1, callCount) // remains 1
        } finally {
            service.removeIssuesListener(listener)
        }
    }

    fun testListenerExceptionIsolation() {
        val service = GhostDebuggerService.getInstance(project)
        val listenerThatThrows: (List<Issue>) -> Unit = { _ ->
            throw RuntimeException("Simulated error in listener")
        }

        service.onIssuesUpdated(listenerThatThrows)
        try {
            // updateIssues should complete normally even if listener throws exception
            service.updateIssues(listOf(issue()))
        } finally {
            service.removeIssuesListener(listenerThatThrows)
        }
    }
}
