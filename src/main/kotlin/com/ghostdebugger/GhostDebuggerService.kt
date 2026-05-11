package com.ghostdebugger

import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.ai.AIService
import com.ghostdebugger.ai.AIServiceFactory
import com.ghostdebugger.bridge.BridgeChannel
import com.ghostdebugger.bridge.JcefBridge
import com.ghostdebugger.bridge.UIEvent
import com.ghostdebugger.fix.FixApplicator
import com.ghostdebugger.fix.FixDeriver
import com.ghostdebugger.fix.FixerRegistry
import com.ghostdebugger.model.*
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing

@Service(Service.Level.PROJECT)
class GhostDebuggerService(private val project: Project) : Disposable {

    private val log = logger<GhostDebuggerService>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        Disposer.register(project, this)
    }

    private var bridge: JcefBridge? = null
    @Volatile private var testBridgeChannel: BridgeChannel? = null
    internal var currentGraph: ProjectGraph? = null
    internal var lastInMemoryGraph: com.ghostdebugger.graph.InMemoryGraph? = null
    var currentIssues: List<Issue> = emptyList()
        private set
    @Volatile var issuesByFile: Map<String, List<Issue>> = emptyMap()
        private set
    @Volatile var suppressUntil: Long = 0L

    val isAnalyzing: Boolean get() = AnalysisOrchestrator.getInstance(project).isAnalyzing

    internal fun updateIssues(newIssues: List<Issue>) {
        currentIssues = newIssues
        issuesByFile = newIssues.groupBy { it.filePath.replace("\\", "/") }
    }
    private var aiService: AIService? = null
    private val fixApplicator = FixApplicator()

    /**
     * Accessor for collaborators that need to push BridgeChannel-shaped events
     * (sendNodeUpdate, sendIssuesForFile). Tests can override via setBridgeForTest.
     */
    internal fun bridgeChannel(): BridgeChannel? = testBridgeChannel ?: bridge

    /**
     * Accessor for collaborators that need the full JcefBridge surface (the BridgeChannel
     * methods plus the JcefBridge-only ones like sendAutoRefreshStart, sendAnalysisProgress).
     * Returns null in unit-test contexts where only a recording BridgeChannel is installed.
     */
    internal fun jcefBridge(): JcefBridge? = bridge

    private fun resolveAiService(): AIService? {
        val settings = GhostDebuggerSettings.getInstance().snapshot()
        val apiKey = if (settings.aiProvider == AIProvider.OPENAI) ApiKeyManager.getApiKey() else null
        return AIServiceFactory.create(settings, apiKey)?.also { aiService = it }
    }

    companion object {
        fun getInstance(project: Project): GhostDebuggerService =
            project.getService(GhostDebuggerService::class.java)
    }

    fun setBridge(bridge: JcefBridge) {
        this.bridge = bridge
        bridge.initialize()
        FileChangeWatcher.getInstance(project).start()
        DebugSessionCoordinator.getInstance(project).start()
    }

    fun handleUIEvent(event: UIEvent) {
        when (event) {
            is UIEvent.NodeClicked -> handleNodeClicked(event.nodeId)
            is UIEvent.NodeDoubleClicked -> handleNodeDoubleClicked(event.nodeId)
            is UIEvent.FixRequested -> handleFixRequested(event.issueId, event.nodeId)
            is UIEvent.ApplyFixRequested -> handleApplyFixRequested(event.issueId, event.fixId)

            is UIEvent.ImpactRequested -> handleImpactRequested(event.nodeId)
            is UIEvent.ExplainSystemRequested -> handleExplainSystem()
            is UIEvent.AnalyzeRequested -> analyzeProject()
            is UIEvent.CancelAnalysisRequested -> cancelAnalysis()
            is UIEvent.BreakpointSet -> handleBreakpointSet(event.filePath, event.line)
            is UIEvent.BreakpointRemoved -> handleBreakpointRemoved(event.filePath, event.line)
            is UIEvent.ExportReportRequested -> handleExportReportRequested()
            is UIEvent.DebugStepOver -> DebugSessionCoordinator.getInstance(project).stepOver()
            is UIEvent.DebugStepInto -> DebugSessionCoordinator.getInstance(project).stepInto()
            is UIEvent.DebugStepOut -> DebugSessionCoordinator.getInstance(project).stepOut()
            is UIEvent.DebugResume -> DebugSessionCoordinator.getInstance(project).resume()
            is UIEvent.DebugPause -> DebugSessionCoordinator.getInstance(project).pause()
            is UIEvent.Unknown -> log.warn("Unknown UI event: ${event.raw}")
        }
    }

    fun analyzeProject() = AnalysisOrchestrator.getInstance(project).analyzeProject()

    fun cancelAnalysis() {
        AnalysisOrchestrator.getInstance(project).cancelAnalysis()
        FileChangeWatcher.getInstance(project).cancelAutoRefresh()
    }

    private fun updateIssueExplanation(issueId: String, explanation: String) {
        updateIssues(currentIssues.map { 
            if (it.id == issueId) it.copy(explanation = explanation) else it
        })
    }

    private fun handleNodeClicked(nodeId: String) {
        val issue = currentIssues.firstOrNull { it.filePath.replace("\\", "/") == nodeId.replace("\\", "/") }
            ?: currentIssues.firstOrNull { nodeId.contains(it.filePath.substringAfterLast("/")) }

        if (issue != null) {
            val existingExplanation = issue.explanation
            if (existingExplanation != null) {
                scope.launch(Dispatchers.Swing) {
                    bridge?.sendIssueExplanation(issue.id, existingExplanation)
                }
                return
            }

            scope.launch {
                try {
                    val svc = aiService ?: resolveAiService() ?: return@launch
                    val explanation = svc.explainIssue(issue, issue.codeSnippet)
                    updateIssueExplanation(issue.id, explanation)
                    withContext(Dispatchers.Swing) {
                        bridge?.sendIssueExplanation(issue.id, explanation)
                    }
                } catch (e: Exception) {
                    log.error("Failed to explain issue", e)
                    withContext(Dispatchers.Swing) {
                        bridge?.sendIssueExplanation(
                            issue.id,
                            "Error fetching explanation: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    private fun handleNodeDoubleClicked(nodeId: String) {
        val graph = currentGraph ?: return
        val node = graph.nodes.firstOrNull { it.id == nodeId } ?: return
        if (node.filePath.startsWith("ext:")) return

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(node.filePath) ?: return

        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(virtualFile, true)
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
                com.intellij.xdebugger.XDebuggerUtil.getInstance()
                    .toggleLineBreakpoint(project, virtualFile, line - 1)
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
                com.intellij.xdebugger.XDebuggerUtil.getInstance()
                    .toggleLineBreakpoint(project, virtualFile, line - 1)
                log.info("Breakpoint removed at $filePath:$line")
            } catch (e: Exception) {
                log.warn("Could not remove breakpoint at $filePath:$line — ${e.message}")
            }
        }
    }

    private fun handleFixRequested(issueId: String, nodeId: String) {
        val issue = currentIssues.firstOrNull { it.id == issueId }
            ?: currentIssues.firstOrNull { nodeId.contains(it.filePath.substringAfterLast("/")) }
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
                    bridge?.sendFixSuggestion(deterministicFix)
                }
                
                val settings = GhostDebuggerSettings.getInstance().snapshot()
                if (settings.aiProvider != AIProvider.NONE) {
                    scope.launch {
                        try {
                            val svc = aiService ?: resolveAiService() ?: return@launch
                            val explanation = svc.explainIssue(issue, issue.codeSnippet)
                            updateIssueExplanation(issue.id, explanation)
                            withContext(Dispatchers.Swing) {
                                bridge?.sendIssueExplanation(issue.id, explanation)
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
                val svc = aiService ?: resolveAiService() ?: run {
                    withContext(Dispatchers.Swing) {
                        bridge?.sendError("AI provider not configured. Go to Settings → Tools → Aegis Debug")
                    }
                    return@launch
                }
                val fix = svc.suggestFix(issue, issue.codeSnippet)
                withContext(Dispatchers.Swing) {
                    bridge?.sendFixSuggestion(fix)
                }
            } catch (e: Exception) {
                log.error("Failed to generate fix suggestion", e)
                withContext(Dispatchers.Swing) {
                    bridge?.sendError("Error generating fix: ${e.message}")
                }
            }
        }
    }

    private fun handleApplyFixRequested(issueId: String, fixId: String) {
        val issue = currentIssues.firstOrNull { it.id == issueId } ?: run {
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
                bridge?.sendError("Could not apply fix: fix could not be derived for issue $issueId.")
            }
            return
        }

        suppressUntil = System.currentTimeMillis() + 3000
        scope.launch {
            val applied = fixApplicator.apply(fix, project)
            if (applied is com.ghostdebugger.fix.FixApplyResult.Success) {
                withContext(Dispatchers.Swing) {
                    bridge?.sendFixApplied(issueId)
                }
                AnalysisOrchestrator.getInstance(project).reanalyzeFile(issue.filePath)
            } else {
                val msg = if (applied is com.ghostdebugger.fix.FixApplyResult.Rejected) applied.reason else "Fix application failed for issue $issueId."
                withContext(Dispatchers.Swing) {
                    bridge?.sendError(msg)
                }
            }
        }
    }

    // ── Test hooks (package-private; used only by BasePlatformTestCase tests). ──

    internal fun setBridgeForTest(channel: BridgeChannel) {
        this.testBridgeChannel = channel
    }

    internal fun installTestGraph(graph: com.ghostdebugger.graph.InMemoryGraph) =
        AnalysisOrchestrator.getInstance(project).installTestGraph(graph)

    internal fun cascadeDependentsForTest(changedFilePath: String, cap: Int) =
        AnalysisOrchestrator.getInstance(project).cascadeDependentsForTest(changedFilePath, cap)

    private fun handleExplainSystem() {
        val graph = currentGraph ?: run {
            scope.launch(Dispatchers.Swing) {
                bridge?.sendSystemExplanation("Please analyze the project first with 'Analyze Project'.")
            }
            return
        }

        scope.launch {
            try {
                val svc = aiService ?: resolveAiService() ?: run {
                    withContext(Dispatchers.Swing) {
                        bridge?.sendSystemExplanation(buildLocalSystemSummary(graph))
                    }
                    return@launch
                }
                val summary = svc.explainSystem(graph)
                withContext(Dispatchers.Swing) {
                    bridge?.sendSystemExplanation(summary)
                }
            } catch (e: Exception) {
                log.error("System explanation failed", e)
                withContext(Dispatchers.Swing) {
                    bridge?.sendSystemExplanation(buildLocalSystemSummary(graph))
                }
            }
        }
    }

    private fun handleImpactRequested(nodeId: String) {
        val inMemoryGraph = lastInMemoryGraph ?: return
        val affectedNodes = inMemoryGraph.calculateImpact(nodeId)
        scope.launch(Dispatchers.Swing) {
            bridge?.sendImpactAnalysis(nodeId, affectedNodes)
        }
    }

    private fun handleExportReportRequested() {
        ReportExporter(project).export(currentGraph)
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

    override fun dispose() {
        scope.cancel()
    }
}
