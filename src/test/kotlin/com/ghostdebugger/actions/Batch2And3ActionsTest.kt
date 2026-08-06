package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.datatransfer.DataFlavor

class Batch2And3ActionsTest : BasePlatformTestCase() {

    fun testActionManagerRegistersBatch2And3Actions() {
        val am = ActionManager.getInstance()
        assertNotNull(am.getAction("GhostDebugger.ToggleRule"))
        assertNotNull(am.getAction("GhostDebugger.ShowInNeuroMap"))
        assertNotNull(am.getAction("GhostDebugger.ExportReport"))
        assertNotNull(am.getAction("GhostDebugger.CopyFindingForAI"))
        assertNotNull(am.getAction("GhostDebugger.ConfirmDenyFinding"))
    }

    fun testCopyFindingForAICopiesMarkdownToClipboard() {
        val file = myFixture.configureByText("Main.kt", "fun main() { println(\"hello\") }")
        val service = GhostDebuggerService.getInstance(project)

        val issue = Issue(
            id = "test-ai-copy-1",
            type = IssueType.CUSTOM_RULE,
            severity = IssueSeverity.WARNING,
            title = "Test AI Copy Title",
            description = "Dangerous call found",
            filePath = file.virtualFile.path,
            line = 1,
            ruleId = "pce-rethrow-missing",
            sources = listOf(IssueSource.STATIC)
        )
        service.updateIssues(listOf(issue))

        val action = CopyFindingForAIAction()
        myFixture.testAction(action)

        val contents = CopyPasteManager.getInstance().contents
        assertNotNull(contents)
        val text = contents!!.getTransferData(DataFlavor.stringFlavor) as String
        assertTrue(text.contains("### Finding: Test AI Copy Title"))
        assertTrue(text.contains("pce-rethrow-missing"))
    }

    fun testConfirmDenyFindingTogglesRuntimeConfirmedSource() {
        val file = myFixture.configureByText("Main.kt", "fun test() {}")
        val service = GhostDebuggerService.getInstance(project)

        val issue = Issue(
            id = "test-runtime-1",
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.ERROR,
            title = "Null Pointer Potential",
            description = "Null dereference",
            filePath = file.virtualFile.path,
            line = 1,
            sources = listOf(IssueSource.STATIC)
        )
        service.updateIssues(listOf(issue))

        val action = ConfirmDenyFindingAction()
        myFixture.testAction(action)

        val updated = service.currentIssues.first { it.id == "test-runtime-1" }
        assertTrue(updated.sources.contains(IssueSource.RUNTIME_CONFIRMED))

        // Toggle back off
        myFixture.testAction(action)
        val toggledBack = service.currentIssues.first { it.id == "test-runtime-1" }
        assertFalse(toggledBack.sources.contains(IssueSource.RUNTIME_CONFIRMED))
    }
}
