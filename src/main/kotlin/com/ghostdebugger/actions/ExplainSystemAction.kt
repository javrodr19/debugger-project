package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.ghostdebugger.bridge.UIEvent
import com.intellij.openapi.wm.ToolWindowManager

class ExplainSystemAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("GhostDebugger")
        toolWindow?.show()
        GhostDebuggerService.getInstance(project).handleUIEvent(UIEvent.ExplainSystemRequested)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
