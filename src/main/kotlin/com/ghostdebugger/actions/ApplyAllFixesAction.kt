package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.fix.engine.FixPlanApplicator
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class ApplyAllFixesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = GhostDebuggerService.getInstance(project)

        val fileIssues = service.issuesByFile[virtualFile.path] ?: emptyList()
        val fixableIssues = fileIssues.filter { it.suggestedFix != null }

        for (issue in fixableIssues) {
            val fix = issue.suggestedFix ?: continue
            val applicator = FixPlanApplicator(project)
            runCatching { applicator.applyFix(virtualFile, fix) }
        }

        service.analyzeFile(virtualFile)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = project != null && virtualFile != null && !virtualFile.isDirectory
    }
}
