package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class AnalyzeProjectAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = GhostDebuggerService.getInstance(project)
        
        if (service.isAnalyzing) return

        // Open the tool window first
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("GhostDebugger")
        toolWindow?.show()

        // Trigger analysis
        GhostDebuggerService.getInstance(project).analyzeProject()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
