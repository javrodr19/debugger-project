package com.ghostdebugger.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class GhostDebuggerToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val contentFactory = ContentFactory.getInstance()
        
        // Use a placeholder for now, we'll configure it after creation
        val content = contentFactory.createContent(null, "NeuroMap", false)
        val neuroMapPanel = NeuroMapPanel(project, content)
        content.component = neuroMapPanel
        
        contentManager.addContent(content)
        toolWindow.setTitle("Aegis Debug")
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
