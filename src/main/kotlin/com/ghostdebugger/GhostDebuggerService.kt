package com.ghostdebugger

import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.ai.OpenAIService
import com.ghostdebugger.analysis.AnalysisEngine
import com.ghostdebugger.bridge.JcefBridge
import com.ghostdebugger.bridge.UIEvent
import com.ghostdebugger.graph.GraphBuilder
import com.ghostdebugger.model.*
import com.ghostdebugger.parser.DependencyResolver
import com.ghostdebugger.parser.FileScanner
import com.ghostdebugger.parser.SymbolExtractor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing

@Service(Service.Level.PROJECT)
class GhostDebuggerService(private val project: Project) {

    private val log = logger<GhostDebuggerService>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var bridge: JcefBridge? = null
    private var currentGraph: ProjectGraph? = null
    private var currentIssues: List<Issue> = emptyList()
    private var openAIService: OpenAIService? = null

    companion object {
        fun getInstance(project: Project): GhostDebuggerService =
            project.getService(GhostDebuggerService::class.java)
    }

    fun setBridge(bridge: JcefBridge) {
        this.bridge = bridge
        bridge.initialize()
    }

    fun handleUIEvent(event: UIEvent) {
        when (event) {
            is UIEvent.NodeClicked -> handleNodeClicked(event.nodeId)
            is UIEvent.NodeDoubleClicked -> handleNodeDoubleClicked(event.nodeId)
            is UIEvent.FixRequested -> handleFixRequested(event.issueId, event.nodeId)
            is UIEvent.SimulateRequested -> handleSimulateRequested(event.entryNodeId)
            is UIEvent.ImpactRequested -> handleImpactRequested(event.nodeId)
            is UIEvent.ExplainSystemRequested -> handleExplainSystem()
            is UIEvent.AnalyzeRequested -> analyzeProject()
            is UIEvent.BreakpointSet -> handleBreakpointSet(event.filePath, event.line)
            is UIEvent.BreakpointRemoved -> handleBreakpointRemoved(event.filePath, event.line)
            is UIEvent.Unknown -> log.warn("Unknown UI event: ${event.raw}")
        }
    }

    fun analyzeProject() {
        scope.launch {
            try {
                withContext(Dispatchers.Swing) {
                    bridge?.sendAnalysisStart()
                }

                log.info("Starting project analysis...")

                // 1. Scan files
                val virtualFiles = ApplicationManager.getApplication().runReadAction<List<com.intellij.openapi.vfs.VirtualFile>> {
                    FileScanner(project).scanFiles()
                }

                log.info("Found ${virtualFiles.size} files")

                // 2. Parse files
                val rawFiles = ApplicationManager.getApplication().runReadAction<List<ParsedFile>> {
                    FileScanner(project).parsedFiles(virtualFiles)
                }

                // 3. Extract symbols
                val extractor = SymbolExtractor()
                val parsedFiles = rawFiles.map { extractor.extract(it) }

                // 4. Resolve dependencies
                val resolver = DependencyResolver(project.basePath ?: "")
                val dependencies = resolver.resolve(parsedFiles)

                // 5. Build graph
                val graphBuilder = GraphBuilder()
                val inMemoryGraph = graphBuilder.build(parsedFiles, dependencies)

                // 6. Run analysis
                val analysisContext = AnalysisContext(
                    graph = inMemoryGraph,
                    project = project,
                    parsedFiles = parsedFiles
                )
                val analysisResult = AnalysisEngine().analyze(analysisContext)
                currentIssues = analysisResult.issues

                // 7. Apply issues to graph
                graphBuilder.applyIssues(inMemoryGraph, analysisResult.issues)

                // 8. Build serializable graph
                val projectGraph = inMemoryGraph.toProjectGraph(project.name)
                currentGraph = projectGraph

                log.info("Analysis complete: ${analysisResult.issues.size} issues found")

                // 9. Send to UI
                withContext(Dispatchers.Swing) {
                    bridge?.sendGraphData(projectGraph)
                    bridge?.sendAnalysisComplete(
                        analysisResult.metrics.errorCount,
                        analysisResult.metrics.warningCount,
                        analysisResult.metrics.healthScore
                    )
                }

                // 10. Pre-fetch AI explanations for critical issues
                val apiKey = ApiKeyManager.getApiKey()
                if (!apiKey.isNullOrBlank()) {
                    val aiService = OpenAIService(apiKey).also { openAIService = it }
                    val criticalIssues = analysisResult.issues
                        .filter { it.severity == IssueSeverity.ERROR }
                        .take(3)

                    for (issue in criticalIssues) {
                        try {
                            val explanation = aiService.explainIssue(issue, issue.codeSnippet)
                            issue.explanation = explanation
                            withContext(Dispatchers.Swing) {
                                bridge?.sendIssueExplanation(issue.id, explanation)
                            }
                        } catch (e: Exception) {
                            log.warn("Could not fetch explanation for issue ${issue.id}", e)
                        }
                    }
                }

            } catch (t: Throwable) {
                log.error("Analysis failed with Throwable", t)
                withContext(Dispatchers.Swing) {
                    bridge?.sendError("Analysis failed: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
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
                    val apiKey = ApiKeyManager.getApiKey() ?: return@launch
                    val aiService = openAIService ?: OpenAIService(apiKey).also { openAIService = it }
                    val explanation = aiService.explainIssue(issue, issue.codeSnippet)
                    issue.explanation = explanation
                    withContext(Dispatchers.Swing) {
                        bridge?.sendIssueExplanation(issue.id, explanation)
                    }
                } catch (e: Exception) {
                    log.error("Failed to explain issue", e)
                    withContext(Dispatchers.Swing) {
                        bridge?.sendIssueExplanation(
                            issue.id,
                            "Error al obtener explicación: ${e.message}"
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
                // Toggle removes it if it already exists
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

        scope.launch {
            try {
                val apiKey = ApiKeyManager.getApiKey() ?: run {
                    withContext(Dispatchers.Swing) {
                        bridge?.sendError("OpenAI API key not configured. Go to Settings → Tools → GhostDebugger")
                    }
                    return@launch
                }
                val aiService = openAIService ?: OpenAIService(apiKey).also { openAIService = it }
                val fix = aiService.suggestFix(issue, issue.codeSnippet)
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

    private fun handleSimulateRequested(entryNodeId: String) {
        val graph = currentGraph ?: return
        scope.launch {
            val startNode = graph.nodes.firstOrNull { it.id == entryNodeId } ?: return@launch
            val path = mutableListOf(startNode.id)
            var current = startNode

            repeat(5) {
                val edge = graph.edges.firstOrNull { it.source == current.id }
                val next = edge?.let { graph.nodes.firstOrNull { n -> n.id == it.target } }
                if (next != null && next.id !in path) {
                    path.add(next.id)
                    current = next
                }
            }

            for (nodeId in path) {
                withContext(Dispatchers.Swing) {
                    bridge?.sendImpactAnalysis(nodeId, path)
                }
                delay(500)
            }
        }
    }

    private fun handleExplainSystem() {
        val graph = currentGraph ?: run {
            scope.launch(Dispatchers.Swing) {
                bridge?.sendSystemExplanation("Por favor, analiza el proyecto primero con 'Analyze Project'.")
            }
            return
        }

        scope.launch {
            try {
                val apiKey = ApiKeyManager.getApiKey() ?: run {
                    withContext(Dispatchers.Swing) {
                        bridge?.sendSystemExplanation(buildLocalSystemSummary(graph))
                    }
                    return@launch
                }
                val aiService = openAIService ?: OpenAIService(apiKey).also { openAIService = it }
                val summary = aiService.explainSystem(graph)
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
        val graph = currentGraph ?: return
        val inMemoryGraph = com.ghostdebugger.graph.InMemoryGraph()
        graph.nodes.forEach { inMemoryGraph.addNode(it) }
        graph.edges.forEach { inMemoryGraph.addEdge(it) }

        val affectedNodes = inMemoryGraph.calculateImpact(nodeId)
        scope.launch(Dispatchers.Swing) {
            bridge?.sendImpactAnalysis(nodeId, affectedNodes)
        }
    }

    private fun buildLocalSystemSummary(graph: ProjectGraph): String {
        val errorFiles = graph.nodes.count { it.status == NodeStatus.ERROR }
        val warningFiles = graph.nodes.count { it.status == NodeStatus.WARNING }
        val totalIssues = graph.nodes.sumOf { it.issues.size }
        return """
            Resumen del Proyecto: ${graph.metadata.projectName}

            • Módulos analizados: ${graph.nodes.size}
            • Archivos con errores: $errorFiles
            • Archivos con advertencias: $warningFiles
            • Issues totales: $totalIssues
            • Dependencias: ${graph.edges.size}
            • Salud del proyecto: ${graph.metadata.healthScore.toInt()}%

            Configura tu API key de OpenAI en Settings → Tools → GhostDebugger para obtener análisis detallados con IA.
        """.trimIndent()
    }

    fun dispose() {
        scope.cancel()
    }
}
