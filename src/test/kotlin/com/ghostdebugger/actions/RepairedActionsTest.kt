package com.ghostdebugger.actions

import com.ghostdebugger.AnalysisOrchestrator
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
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
}
