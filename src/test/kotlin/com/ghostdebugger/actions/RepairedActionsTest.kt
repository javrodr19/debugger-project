package com.ghostdebugger.actions

import com.ghostdebugger.AnalysisOrchestrator
import com.ghostdebugger.ReportExporter
import com.ghostdebugger.store.SuppressionMemoryService
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify

class RepairedActionsTest : BasePlatformTestCase() {

    /**
     * BasePlatformTestCase's light test project never registers EP-declared tool windows --
     * ToolWindowManager.getInstance(project).toolWindowIds is empty here for every id,
     * including ones that are genuinely registered in plugin.xml and resolve fine in a real
     * running IDE (confirmed empirically; this is a harness limitation, not something specific
     * to this plugin). So the real ToolWindowManager can't be used to observe this fix, and the
     * brief's own "getToolWindow(id) resolves / doesn't resolve" assertions would fail here
     * unconditionally regardless of whether ShowInNeuroMapAction is correct -- they were never
     * actually run before being handed down. ToolWindowManager.getInstance is mocked instead,
     * standing in two different fakes for the two candidate ids, so the test can assert the one
     * thing that actually matters: which id the action queries, and that `.show` is reached on
     * *that* tool window rather than silently no-op'ing on a null safe-call.
     */
    fun `test Show in NeuroMap looks up and shows the tool window by its registered id, not its title`() {
        val file = myFixture.configureByText("Main.kt", "fun main() {}\n").virtualFile
        val toolWindowManager = mockk<ToolWindowManager>()
        val registeredToolWindow = mockk<ToolWindow>(relaxed = true)
        every { toolWindowManager.getToolWindow("GhostDebugger") } returns registeredToolWindow
        every { toolWindowManager.getToolWindow("Aegis Debug") } returns null

        mockkObject(ToolWindowManager.Companion)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        try {
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, file)
                .build()
            ActionManager.getInstance().getAction("GhostDebugger.ShowInNeuroMap")
                .actionPerformed(TestActionEvent.createTestEvent(context))

            verify(exactly = 1) {
                registeredToolWindow.show(any())
            }
        } finally {
            unmockkObject(ToolWindowManager.Companion)
        }
    }

    /**
     * The old code called `service.analyzeProject()` -- a full-project run -- and threw away
     * the `virtualFile` it had just resolved. Proving the fix means proving the *targeted*
     * entry point is what actually gets called, with the active file's path, not merely that
     * some analysis method runs. `AnalysisOrchestrator.getInstance(project)` is swapped for a
     * mock so the real (heavy) analysis pipeline never has to run for this assertion to hold.
     */
    fun `test Reanalyze Current File calls the targeted entry point with the active file`() {
        val file = myFixture.configureByText("Main.kt", "fun main() {}\n").virtualFile
        val orchestrator = mockk<AnalysisOrchestrator>(relaxed = true)
        mockkObject(AnalysisOrchestrator.Companion)
        every { AnalysisOrchestrator.getInstance(project) } returns orchestrator
        try {
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, file)
                .build()
            ActionManager.getInstance().getAction("GhostDebugger.ReanalyzeFile")
                .actionPerformed(TestActionEvent.createTestEvent(context))

            verify(exactly = 1) { orchestrator.reanalyzeFile(file.path) }
            verify(exactly = 0) { orchestrator.analyzeProject() }
        } finally {
            unmockkObject(AnalysisOrchestrator.Companion)
        }
    }

    fun `test explicit suppression hides a finding on the first invocation`() {
        val service = SuppressionMemoryService.getInstance(project)
        val fingerprint = "test-fingerprint-1"

        assertFalse(service.shouldAutoHide(fingerprint))
        service.suppressNow(fingerprint)
        assertTrue(
            "an explicit suppress command must take effect immediately, not on the third press",
            service.shouldAutoHide(fingerprint)
        )
    }

    /**
     * `suppressNow` must raise the count to the threshold, not replace or reset the counting
     * mechanism. Ordinary accumulated dismissals (the implicit "dismissed again" signal used
     * elsewhere, e.g. SuppressFindingAction's prior behavior) must still require hitting the
     * configured threshold (3, unchanged by this repair) on their own.
     */
    fun `test suppressNow does not disturb ordinary accumulated dismissal counting`() {
        val service = SuppressionMemoryService.getInstance(project)
        val fingerprint = "test-fingerprint-2"

        service.recordDismissal(fingerprint)
        service.recordDismissal(fingerprint)
        assertFalse(
            "two ordinary dismissals must not yet auto-hide when the threshold is 3",
            service.shouldAutoHide(fingerprint)
        )

        service.recordDismissal(fingerprint)
        assertTrue(
            "the third ordinary dismissal must cross the threshold on its own, unrelated to suppressNow",
            service.shouldAutoHide(fingerprint)
        )
    }

    /**
     * ReportExporter.export previously routed the null-graph message only through
     * `service.jcefBridge()?.sendError(...)`, which is null until the tool window's webview
     * has been created at least once -- so on first use (the exact moment a user needs the
     * message most) the export menu item was a silent no-op. The fix must reach the user
     * through a balloon regardless of whether jcefBridge() is attached.
     */
    fun `test export with no analysis data reaches the user via a balloon without jcefBridge`() {
        val notifications = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    notifications += notification
                }
            }
        )

        ReportExporter(project).export(null)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val balloon = notifications.find { it.content.contains("No analysis data available") }
        assertNotNull(
            "the no-analysis-data message must reach the user as a balloon even when jcefBridge() is null",
            balloon
        )
        assertEquals("GhostDebugger", balloon!!.groupId)
    }
}
