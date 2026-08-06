package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.ReportExporter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class ExportReportAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = GhostDebuggerService.getInstance(project)
        ReportExporter(project).export(service.currentGraph)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
