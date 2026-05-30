package com.ghostdebugger

import com.ghostdebugger.ai.AIService
import com.ghostdebugger.ai.AIServiceFactory
import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.bridge.UIEvent
import com.ghostdebugger.fix.FixApplicator
import com.ghostdebugger.fix.FixDeriver
import com.ghostdebugger.fix.FixerRegistry
import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.NodeStatus
import com.ghostdebugger.model.ProjectGraph
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * Routes incoming UIEvents from the JCEF tool window to the appropriate handler.
 * Lifted out of GhostDebuggerService in V1.5.
 *
 * Each handler reads project-level state via `service()` (currentIssues, currentGraph,
 * lastInMemoryGraph) and mutates through facade-owned methods (`service().updateIssues`).
 * The facade is the single writer surface; this router is a reader + delegating-writer.
 */
@Service(Service.Level.PROJECT)
internal class UIEventRouter(private val project: Project) {

    private val log = logger<UIEventRouter>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var aiService: AIService? = null
    private val fixApplicator = FixApplicator()

    fun handle(event: UIEvent) {
        when (event) {
            is UIEvent.NodeClicked -> handleNodeClicked(event.nodeId)
            is UIEvent.NodeDoubleClicked -> handleNodeDoubleClicked(event.nodeId)
            is UIEvent.FixRequested -> handleFixRequested(event.issueId, event.nodeId)
            is UIEvent.ApplyFixRequested -> handleApplyFixRequested(event.issueId, event.fixId)
            is UIEvent.ImpactRequested -> handleImpactRequested(event.nodeId)
            is UIEvent.ExplainSystemRequested -> handleExplainSystem()
            is UIEvent.AnalyzeRequested -> AnalysisOrchestrator.getInstance(project).analyzeProject()
            is UIEvent.CancelAnalysisRequested -> service().cancelAnalysis()
            is UIEvent.BreakpointSet -> handleBreakpointSet(event.filePath, event.line)
            is UIEvent.BreakpointRemoved -> handleBreakpointRemoved(event.filePath, event.line)
            is UIEvent.ExportReportRequested -> ReportExporter(project).export(service().currentGraph)
            is UIEvent.DismissIssue -> handleDismissIssue(event.issueId)
            is UIEvent.UnsuppressIssue -> handleUnsuppressIssue(event.issueId)
            is UIEvent.ToggleShowSuppressed -> handleToggleShowSuppressed()
            is UIEvent.DebugStepOver -> DebugSessionCoordinator.getInstance(project).stepOver()
            is UIEvent.DebugStepInto -> DebugSessionCoordinator.getInstance(project).stepInto()
            is UIEvent.DebugStepOut -> DebugSessionCoordinator.getInstance(project).stepOut()
            is UIEvent.DebugResume -> DebugSessionCoordinator.getInstance(project).resume()
            is UIEvent.DebugPause -> DebugSessionCoordinator.getInstance(project).pause()
            is UIEvent.Unknown -> log.warn("Unknown UI event: ${event.raw}")
        }
    }

    private fun service(): GhostDebuggerService = GhostDebuggerService.getInstance(project)

    private fun resolveAiService(): AIService? {
        val settings = GhostDebuggerSettings.getInstance().snapshot()
        val apiKey = if (settings.aiProvider == AIProvider.OPENAI) ApiKeyManager.getApiKey() else null
        return AIServiceFactory.create(settings, apiKey)?.also { aiService = it }
    }

    private fun updateIssueExplanation(issueId: String, explanation: String) {
        val svc = service()
        svc.updateIssues(svc.currentIssues.map {
            if (it.id == issueId) it.copy(explanation = explanation) else it
        })
    }

    private fun handleNodeClicked(nodeId: String) {
        val svc = service()
        val issue = svc.currentIssues.firstOrNull { it.filePath.replace("\\", "/") == nodeId.replace("\\", "/") }
            ?: svc.currentIssues.firstOrNull { nodeId.contains(it.filePath.substringAfterLast("/")) }
            ?: return

        val existingExplanation = issue.explanation
        if (existingExplanation != null) {
            scope.launch(Dispatchers.Swing) {
                svc.jcefBridge()?.sendIssueExplanation(issue.id, existingExplanation)
            }
            return
        }

        scope.launch {
            try {
                val ai = aiService ?: resolveAiService() ?: return@launch
                // Send an empty complete chunk to clear any stale UI explanation state
                withContext(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendIssueExplanationChunk(issue.id, "", isComplete = false)
                }
                val explanation = ai.explainIssueStreaming(issue, issue.codeSnippet) { token ->
                    scope.launch(Dispatchers.Swing) {
                        svc.jcefBridge()?.sendIssueExplanationChunk(issue.id, token, isComplete = false)
                    }
                }
                updateIssueExplanation(issue.id, explanation)
                withContext(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendIssueExplanationChunk(issue.id, "", isComplete = true)
                }
            } catch (e: Exception) {
                log.error("Failed to explain issue", e)
                withContext(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendIssueExplanationChunk(
                        issue.id,
                        "Error fetching explanation: ${e.message}",
                        isComplete = true
                    )
                }
            }
        }
    }

    private fun handleNodeDoubleClicked(nodeId: String) {
        val graph = service().currentGraph ?: return
        val node = graph.nodes.firstOrNull { it.id == nodeId } ?: return
        if (node.filePath.startsWith("ext:")) return

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(node.filePath) ?: return

        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    }

    private fun handleBreakpointSet(filePath: String, line: Int) {
        if (filePath.isBlank() || line < 1) return
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: run {
            log.warn("Breakpoint: file not found: $filePath")
            return
        }
        ApplicationManager.getApplication().invokeLater {
            try {
                XDebuggerUtil.getInstance().toggleLineBreakpoint(project, virtualFile, line - 1)
                log.info("Breakpoint set at $filePath:$line")
            } catch (e: Exception) {
                log.warn("Could not set breakpoint at $filePath:$line — ${e.message}")
            }
        }
    }

    private fun handleBreakpointRemoved(filePath: String, line: Int) {
        if (filePath.isBlank() || line < 1) return
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        ApplicationManager.getApplication().invokeLater {
            try {
                XDebuggerUtil.getInstance().toggleLineBreakpoint(project, virtualFile, line - 1)
                log.info("Breakpoint removed at $filePath:$line")
            } catch (e: Exception) {
                log.warn("Could not remove breakpoint at $filePath:$line — ${e.message}")
            }
        }
    }

    private fun handleFixRequested(issueId: String, nodeId: String) {
        val svc = service()
        val issue = svc.currentIssues.firstOrNull { it.id == issueId }
            ?: svc.currentIssues.firstOrNull { nodeId.contains(it.filePath.substringAfterLast("/")) }
            ?: return

        if (FixerRegistry.forIssue(issue) != null) {
            val fileContent = try {
                java.io.File(issue.filePath).readText()
            } catch (e: Exception) {
                log.warn("Could not read file for deterministic fix: ${issue.filePath}", e)
                null
            }
            val vf = LocalFileSystem.getInstance().findFileByPath(issue.filePath)
            val deterministicFix = if (vf != null && fileContent != null) {
                FixDeriver(project).derive(issue, vf, fileContent)
            } else null
            if (deterministicFix != null) {
                scope.launch(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendFixSuggestion(deterministicFix)
                }

                val settings = GhostDebuggerSettings.getInstance().snapshot()
                if (settings.aiProvider != AIProvider.NONE) {
                    scope.launch {
                        try {
                            val ai = aiService ?: resolveAiService() ?: return@launch
                            val explanation = ai.explainIssue(issue, issue.codeSnippet)
                            updateIssueExplanation(issue.id, explanation)
                            withContext(Dispatchers.Swing) {
                                svc.jcefBridge()?.sendIssueExplanation(issue.id, explanation)
                            }
                        } catch (e: Exception) {
                            log.warn("AI explanation enrichment failed for issue ${issue.id}", e)
                        }
                    }
                }
                return
            }
        }

        scope.launch {
            try {
                val ai = aiService ?: resolveAiService() ?: run {
                    withContext(Dispatchers.Swing) {
                        svc.jcefBridge()?.sendError("AI provider not configured. Go to Settings → Tools → Aegis Debug")
                    }
                    return@launch
                }
                val fix = ai.suggestFix(issue, issue.codeSnippet)
                withContext(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendFixSuggestion(fix)
                }
            } catch (e: Exception) {
                log.error("Failed to generate fix suggestion", e)
                withContext(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendError("Error generating fix: ${e.message}")
                }
            }
        }
    }

    private fun handleApplyFixRequested(issueId: String, fixId: String) {
        val svc = service()
        val issue = svc.currentIssues.firstOrNull { it.id == issueId } ?: run {
            log.warn("ApplyFix: no issue with id $issueId in currentIssues")
            return
        }

        val fix = if (FixerRegistry.forIssue(issue) != null) {
            try {
                val content = java.io.File(issue.filePath).readText()
                val vf = LocalFileSystem.getInstance().findFileByPath(issue.filePath)
                if (vf != null) FixDeriver(project).derive(issue, vf, content) else null
            } catch (e: Exception) {
                log.warn("Could not re-derive fix for issue $issueId: ${e.message}", e)
                null
            }
        } else {
            log.warn("ApplyFix requested for issue $issueId but no deterministic fixer registered.")
            null
        }

        if (fix == null) {
            scope.launch(Dispatchers.Swing) {
                svc.jcefBridge()?.sendError("Could not apply fix: fix could not be derived for issue $issueId.")
            }
            return
        }

        svc.suppressUntil = System.currentTimeMillis() + 3000
        scope.launch {
            val applied = fixApplicator.apply(fix, project)
            if (applied is FixApplyResult.Success) {
                withContext(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendFixApplied(issueId)
                }
                AnalysisOrchestrator.getInstance(project).reanalyzeFile(issue.filePath)
            } else {
                val msg = if (applied is FixApplyResult.Rejected) applied.reason else "Fix application failed for issue $issueId."
                withContext(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendError(msg)
                }
            }
        }
    }

    private fun handleExplainSystem() {
        val svc = service()
        val graph = svc.currentGraph ?: run {
            scope.launch(Dispatchers.Swing) {
                svc.jcefBridge()?.sendSystemExplanation("Please analyze the project first with 'Analyze Project'.")
            }
            return
        }

        scope.launch {
            try {
                val ai = aiService ?: resolveAiService() ?: run {
                    withContext(Dispatchers.Swing) {
                        svc.jcefBridge()?.sendSystemExplanation(buildLocalSystemSummary(graph))
                    }
                    return@launch
                }
                // Send an empty complete chunk to clear any stale UI explanation state
                withContext(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendSystemExplanationChunk("", isComplete = false)
                }
                val summary = ai.explainSystemStreaming(graph) { token ->
                    scope.launch(Dispatchers.Swing) {
                        svc.jcefBridge()?.sendSystemExplanationChunk(token, isComplete = false)
                    }
                }
                withContext(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendSystemExplanationChunk("", isComplete = true)
                }
            } catch (e: Exception) {
                log.error("System explanation failed", e)
                withContext(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendSystemExplanation(buildLocalSystemSummary(graph))
                }
            }
        }
    }

    private fun handleImpactRequested(nodeId: String) {
        val svc = service()
        val inMemoryGraph = svc.lastInMemoryGraph ?: return
        val affectedNodes = inMemoryGraph.calculateImpact(nodeId)
        scope.launch(Dispatchers.Swing) {
            svc.jcefBridge()?.sendImpactAnalysis(nodeId, affectedNodes)
        }
    }

    private fun buildLocalSystemSummary(graph: ProjectGraph): String {
        val errorFiles = graph.nodes.count { it.status == NodeStatus.ERROR }
        val warningFiles = graph.nodes.count { it.status == NodeStatus.WARNING }
        val totalIssues = graph.nodes.sumOf { it.issues.size }
        return """
            Project Overview: ${graph.metadata.projectName}

            • Analyzed modules: ${graph.nodes.size}
            • Files with errors: $errorFiles
            • Files with warnings: $warningFiles
            • Total issues: $totalIssues
            • Dependencies: ${graph.edges.size}
            • Project health: ${graph.metadata.healthScore.toInt()}%

            Configure an AI provider in Settings → Tools → Aegis Debug for deeper analysis.
        """.trimIndent()
    }

    private fun handleDismissIssue(issueId: String) {
        val svc = service()
        val issue = svc.currentIssues.firstOrNull { it.id == issueId } ?: return
        svc.suppressionMemory().recordDismissal(issue.fingerprint())
        svc.refreshIssuesUI()
        svc.currentGraph?.let { svc.jcefBridge()?.sendGraphData(it) }
    }

    private fun handleUnsuppressIssue(issueId: String) {
        val svc = service()
        val issue = svc.currentIssues.firstOrNull { it.id == issueId } ?: return
        svc.suppressionMemory().reset(issue.fingerprint())
        svc.refreshIssuesUI()
        svc.currentGraph?.let { svc.jcefBridge()?.sendGraphData(it) }
    }

    private fun handleToggleShowSuppressed() {
        GhostDebuggerSettings.getInstance().update {
            showSuppressed = !showSuppressed
        }
        val svc = service()
        svc.refreshIssuesUI()
        svc.currentGraph?.let { svc.jcefBridge()?.sendGraphData(it) }
    }

    companion object {
        fun getInstance(project: Project): UIEventRouter =
            project.getService(UIEventRouter::class.java)
    }
}
