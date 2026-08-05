package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.fix.engine.FixPlanApplicator
import com.ghostdebugger.fix.engine.toFixPlan
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager

class ApplyAllFixesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = GhostDebuggerService.getInstance(project)

        val fileIssues = service.issuesByFile[virtualFile.path] ?: emptyList()
        val fixableIssues = fileIssues.filter { it.suggestedFix != null }
        if (fixableIssues.isEmpty()) return

        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        val applicator = FixPlanApplicator()

        for (issue in fixableIssues) {
            val codeFix = issue.suggestedFix ?: continue
            val plan = codeFix.toFixPlan(document.text) ?: continue
            runCatching {
                applicator.apply(plan, virtualFile, project)
            }
        }

        service.analyzeProject()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = project != null && virtualFile != null && !virtualFile.isDirectory
    }
}
