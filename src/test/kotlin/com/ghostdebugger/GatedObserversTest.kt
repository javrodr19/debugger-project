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
 * `performDebugSessionCrossCheck` takes a non-null `XDebugSession`, so its guard cannot be
 * exercised here without mocking the platform's session/stack-frame/evaluator — which would test
 * the mock, not the guard. That guard's correctness is verified by inspection instead (see
 * task-6-report.md). [DebugObserver.start] needs no live session, so its guard is exercised
 * directly below: `start()`'s only other statement is a log call with no other externally
 * observable state, so the test spies on [AegisCapabilityGate] to prove `start()` actually
 * consults the gate rather than merely existing near it.
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

    fun `test DebugObserver start consults the DEBUGGER_CROSS_CHECK gate`() {
        mockkObject(AegisCapabilityGate)
        try {
            every { AegisCapabilityGate.skipIfGated(AegisCapability.DEBUGGER_CROSS_CHECK) } returns true

            DebugObserver.getInstance(project).start()

            verify(exactly = 1) { AegisCapabilityGate.skipIfGated(AegisCapability.DEBUGGER_CROSS_CHECK) }
        } finally {
            unmockkObject(AegisCapabilityGate)
        }
    }
}
