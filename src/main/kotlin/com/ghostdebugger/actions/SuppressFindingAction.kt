package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.store.SuppressionMemoryService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class SuppressFindingAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = GhostDebuggerService.getInstance(project)

        val currentLine = editor.caretModel.logicalPosition.line + 1
        val issues = service.issuesByFile[virtualFile.path] ?: emptyList()

        val issueToSuppress = issues.firstOrNull { it.line == currentLine } ?: issues.firstOrNull()
        if (issueToSuppress != null) {
            SuppressionMemoryService.getInstance(project).recordDismissal(issueToSuppress.fingerprint())
            service.analyzeProject()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = project != null && virtualFile != null
    }
}
