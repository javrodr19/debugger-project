package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.model.IssueSource
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class ConfirmDenyFindingAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = GhostDebuggerService.getInstance(project)

        val currentLine = editor.caretModel.logicalPosition.line + 1
        val issues = service.issuesByFile[virtualFile.path] ?: emptyList()

        val issue = issues.firstOrNull { it.line == currentLine } ?: issues.firstOrNull() ?: return

        val updatedSources = if (issue.sources.contains(IssueSource.RUNTIME_CONFIRMED)) {
            issue.sources.filterNot { it == IssueSource.RUNTIME_CONFIRMED }
        } else {
            issue.sources + IssueSource.RUNTIME_CONFIRMED
        }

        val updatedIssue = issue.copy(sources = updatedSources.distinct())
        val updatedList = service.currentIssues.map { if (it.id == issue.id) updatedIssue else it }
        service.updateIssues(updatedList)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = project != null && virtualFile != null
    }
}
