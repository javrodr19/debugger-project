package com.ghostdebugger

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.store.DebugObserver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlin.test.assertTrue

/**
 * Covers the two background observers gated in 3.0.0. Both are genuinely broken rather than
 * merely unfinished, and both use the silent `skipIfGated` (not the dialog-raising
 * `blockIfGated` used on user-clicked surfaces), since a modal dialog raised from a background
 * listener callback would itself be a defect:
 *
 * - [ProblemsViewCoordinator.publishToProblemsView] (`PROBLEMS_VIEW_EMIT`): its
 *   `WolfTheProblemSolver.reportProblems` call runs inside `invokeLater`, i.e. on the EDT, which
 *   the 2024.3 platform implementation rejects by asserting a background thread.
 * - [DebugObserver] and `DebugSessionCoordinator.performDebugSessionCrossCheck`
 *   (`DEBUGGER_CROSS_CHECK`): two uncoordinated implementations of a debugger cross-check that
 *   cannot fire on IntelliJ IDEA Community at all.
 *
 * Both debugger guards are interaction-verified rather than behaviour-verified, and the reason is
 * worth stating plainly rather than glossing:
 *
 * `performDebugSessionCrossCheck` takes a non-null `XDebugSession`, and
 * [DebugObserver.evaluateRelevantFindingsAtCurrentFrame] returns immediately when there is no
 * current session. Neither can produce an observable difference between gated and ungated without
 * a live paused debugger, which no unit fixture provides — mocking the session, stack frame and
 * evaluator would test the mock rather than the guard. So these tests prove the guarded method
 * *consults* the gate; they do not prove the downstream evaluate-and-record pipeline is
 * suppressed, and no test in this suite does.
 *
 * The guard sits on [DebugObserver.evaluateRelevantFindingsAtCurrentFrame] and NOT on
 * [DebugObserver.start], which is the whole point of the test below. `start()` looks like the
 * natural gate site and is not: the message-bus subscription in `DebugObserver`'s `init` block
 * runs when the service is constructed by `getInstance(project)`, before `start()` is called, so a
 * guard there suppresses only a log line while `SessionWatcher` keeps firing on every paused
 * session. A review caught exactly that after the first implementation of this task shipped the
 * guard in `start()`. The test below would fail if the guard were moved back.
 */
class GatedObserversTest : BasePlatformTestCase() {

    override fun tearDown() {
        AegisCapabilityGate.resetForTest()
        super.tearDown()
    }

    fun `test starting the problems coordinator publishes nothing`() {
        val psiFile = myFixture.configureByText("Gated.kt", "val x = 1\n")
        val issue = Issue(
            id = "gated-problems-view",
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.WARNING,
            title = "would-be-published",
            description = "must not reach WolfTheProblemSolver while PROBLEMS_VIEW_EMIT is gated",
            filePath = psiFile.virtualFile.url
        )
        val service = GhostDebuggerService.getInstance(project)
        service.updateIssues(listOf(issue))

        val coordinator = ProblemsViewCoordinator.getInstance(project)
        try {
            coordinator.start()
            assertTrue(
                coordinator.publishedFilePathsForTest().isEmpty(),
                "PROBLEMS_VIEW_EMIT is gated; nothing may be published"
            )
        } finally {
            coordinator.dispose()
            service.updateIssues(emptyList())
        }
    }

    fun `test the debugger cross-check pipeline consults the gate, and start does not`() {
        mockkObject(AegisCapabilityGate)
        try {
            every { AegisCapabilityGate.skipIfGated(AegisCapability.DEBUGGER_CROSS_CHECK) } returns true
            val observer = DebugObserver.getInstance(project)

            // start() must NOT be the gate site: the init{} subscription already ran when
            // getInstance constructed the service above, so guarding here would suppress a log
            // line and nothing else.
            observer.start()
            verify(exactly = 0) { AegisCapabilityGate.skipIfGated(AegisCapability.DEBUGGER_CROSS_CHECK) }

            // The work method is the gate site. Every path into the evaluate-and-record pipeline
            // converges here, so this is the one place a guard actually suppresses the behaviour.
            observer.evaluateRelevantFindingsAtCurrentFrame()
            verify(exactly = 1) { AegisCapabilityGate.skipIfGated(AegisCapability.DEBUGGER_CROSS_CHECK) }
        } finally {
            unmockkObject(AegisCapabilityGate)
        }
    }
}
