package com.ghostdebugger.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import com.ghostdebugger.GhostDebuggerService

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

        // M18 / D15 (cancel on tool-window close)
        val disposable = Disposer.newDisposable("GhostDebuggerToolWindowListener")
        Disposer.register(content, disposable)

        project.messageBus.connect(disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            private var wasVisible = toolWindow.isVisible

            override fun stateChanged(toolWindowManager: ToolWindowManager) {
                val tw = toolWindowManager.getToolWindow("GhostDebugger") ?: return
                val isVisible = tw.isVisible
                if (wasVisible && !isVisible) {
                    GhostDebuggerService.getInstance(project).cancelAnalysis()
                }
                wasVisible = isVisible
            }
        })
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
