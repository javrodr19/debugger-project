package com.ghostdebugger.actions

import com.ghostdebugger.AegisCapability
import com.ghostdebugger.AegisCapabilityGate
import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.bridge.UIEvent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class ExplainSystemAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Guard before showing the tool window: a gated capability must not also force a panel open.
        if (AegisCapabilityGate.blockIfGated(project, AegisCapability.AI_EXPLANATION)) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("GhostDebugger")
        toolWindow?.show()
        GhostDebuggerService.getInstance(project).handleUIEvent(UIEvent.ExplainSystemRequested)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
