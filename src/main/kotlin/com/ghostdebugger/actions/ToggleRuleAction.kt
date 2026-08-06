package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.rules.RulePackService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class ToggleRuleAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = GhostDebuggerService.getInstance(project)

        val currentLine = editor.caretModel.logicalPosition.line + 1
        val issues = service.issuesByFile[virtualFile.path] ?: emptyList()

        val issue = issues.firstOrNull { it.line == currentLine } ?: issues.firstOrNull() ?: return
        val ruleId = issue.ruleId ?: return

        val packService = RulePackService.getInstance(project)
        val pack = packService.availablePacks().firstOrNull { p -> p.rules.any { it.id == ruleId } }
        if (pack != null) {
            val currentlyEnabled = packService.isPackEnabled(pack.id)
            packService.setPackEnabled(pack.id, !currentlyEnabled)
            service.analyzeProject()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = project != null && virtualFile != null
    }
}
