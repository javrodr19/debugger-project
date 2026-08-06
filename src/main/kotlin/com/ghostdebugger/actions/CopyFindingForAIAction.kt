package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

class CopyFindingForAIAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = GhostDebuggerService.getInstance(project)

        val currentLine = editor.caretModel.logicalPosition.line + 1
        val issues = service.issuesByFile[virtualFile.path] ?: emptyList()

        val issue = issues.firstOrNull { it.line == currentLine } ?: issues.firstOrNull() ?: return

        val markdownText = buildString {
            appendLine("### Finding: ${issue.title}")
            appendLine("- **Rule ID**: ${issue.ruleId ?: "N/A"}")
            appendLine("- **Severity**: ${issue.severity}")
            appendLine("- **File**: ${issue.filePath}")
            appendLine("- **Line**: ${issue.line ?: 1}")
            appendLine("- **Sources**: ${issue.sources.joinToString(", ")}")
            appendLine()
            appendLine("```")
            appendLine(issue.description)
            appendLine("```")
        }

        CopyPasteManager.getInstance().setContents(StringSelection(markdownText))
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = project != null && virtualFile != null
    }
}
