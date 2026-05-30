package com.ghostdebugger

import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestStatusListener
import com.intellij.openapi.project.ProjectManager

class AegisTestStatusListener : TestStatusListener() {
    override fun testSuiteFinished(root: AbstractTestProxy?) {
        if (root != null) {
            try {
                val projects = ProjectManager.getInstance().openProjects
                for (project in projects) {
                    if (project.isInitialized && !project.isDisposed) {
                        try {
                            AnalysisOrchestrator.getInstance(project).handleTestSuiteFinished(root)
                        } catch (e: Exception) {
                            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                            // Thread-safe and isolated per project
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            }
        }
    }
}
