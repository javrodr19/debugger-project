package com.ghostdebugger

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

/**
 * FIX_APPLICATION is gated in 3.0.0 (Task 3's `AnalysisOrchestrator.applyVerifiedFix` guard).
 * Without lifting the gate, no test can reach the coroutine body these tests exercise —
 * baseline threading and the Success/Rejected/Failed `when` dispatch — because
 * `AegisCapabilityGate.enabled` is a hardcoded `emptySet()` with no production seam to lift it.
 * [AegisCapabilityGate.setEnabledForTest] lifts the gate for just this capability, for just this
 * test class, without touching what actually ships; [AegisCapabilityGate.resetForTest] restores
 * the shipped (disabled) state in `tearDown` so no override leaks into other tests, including
 * `AegisCapabilityGateTest`'s "no capability is enabled in this release" assertion.
 */
class AnalysisOrchestratorApplyVerifiedFixTest : BasePlatformTestCase() {

    private fun issue(id: String, path: String) = Issue(
        id = id, type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = path, line = 1, ruleId = "AEG-CAST-KT-001"
    )

    override fun setUp() {
        super.setUp()
        AegisCapabilityGate.setEnabledForTest(setOf(AegisCapability.FIX_APPLICATION))
    }

    override fun tearDown() {
        AegisCapabilityGate.resetForTest()
        super.tearDown()
    }

    fun testThreadsBaselineProviderResultIntoFixVerified() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val here = issue("t", vf.path)

        var receivedBaseline: List<Issue>? = null
        val content = runReadAction { myFixture.getDocument(psi).text }
        val orch = AnalysisOrchestrator.getInstance(project)

        runBlocking {
            orch.applyVerifiedFix(
                here, vf, content,
                baselineProvider = { listOf(here) },   // stubbed single-file baseline
                fixVerified = { _, _, _, baseline ->
                    receivedBaseline = baseline
                    FixApplyResult.Rejected("verification declined (test)")
                },
            ).join()
        }

        assertEquals(listOf(here), receivedBaseline)
    }

    fun testFailedBranchThreadsBaseline() {
        val psi = myFixture.configureByText("B.kt", "fun g(): Int { return 2 }\n")
        val vf = psi.virtualFile
        val here = issue("f", vf.path)

        var receivedBaseline: List<Issue>? = null
        val content = runReadAction { myFixture.getDocument(psi).text }
        val orch = AnalysisOrchestrator.getInstance(project)

        runBlocking {
            orch.applyVerifiedFix(
                here, vf, content,
                baselineProvider = { listOf(here) },
                fixVerified = { _, _, _, baseline ->
                    receivedBaseline = baseline
                    FixApplyResult.Failed(RuntimeException("verification exploded (test)"))
                },
            ).join()
        }

        assertEquals(
            "the Failed branch must still receive the threaded baseline",
            listOf(here), receivedBaseline
        )
    }

    /**
     * Bounds the coverage claim honestly: this proves the `Success` arm is chosen (baseline
     * threaded, no rejection notification fired) — it does NOT wait for the fire-and-forget
     * `reanalyzeFile` re-analysis that `Success` schedules, because that call launches a
     * *sibling* coroutine on the same `scope`, not a child of the coroutine `job.join()` awaits,
     * so nothing in the returned `Job` marks its completion. Making that inner completion
     * deterministically observable would need either a novel delay/poll pattern (no precedent in
     * this suite) or changing `reanalyzeFile` to return its `Job` — a production-structure
     * change for test convenience, which is exactly what the round-1 review ruled out. An empty
     * `InMemoryGraph` is installed first so the scheduled re-analysis takes the bounded per-file
     * path instead of falling back to a full `analyzeProject()` in the background during the
     * rest of the suite.
     */
    fun testSuccessBranchThreadsBaselineAndDoesNotReject() {
        val orch = AnalysisOrchestrator.getInstance(project)
        orch.installTestGraph(InMemoryGraph())

        val psi = myFixture.configureByText("C.kt", "fun h(): Int { return 3 }\n")
        val vf = psi.virtualFile
        val here = issue("s", vf.path)

        var receivedBaseline: List<Issue>? = null
        val rejectionNotifications = mutableListOf<String>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    rejectionNotifications += notification.content
                }
            }
        )

        val content = runReadAction { myFixture.getDocument(psi).text }

        runBlocking {
            orch.applyVerifiedFix(
                here, vf, content,
                baselineProvider = { listOf(here) },
                fixVerified = { _, _, _, baseline ->
                    receivedBaseline = baseline
                    FixApplyResult.Success
                },
            ).join()
        }

        assertEquals(
            "the Success branch must still receive the threaded baseline",
            listOf(here), receivedBaseline
        )
        assertTrue(
            "the Success branch must never surface a rejection notification",
            rejectionNotifications.isEmpty()
        )
    }
}
