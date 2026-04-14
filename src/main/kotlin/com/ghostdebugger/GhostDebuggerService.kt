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
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XValueChildrenList
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.io.File

@Service(Service.Level.PROJECT)
class GhostDebuggerService(private val project: Project) {

    private val log = logger<GhostDebuggerService>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var bridge: JcefBridge? = null
    private var currentGraph: ProjectGraph? = null
    var currentIssues: List<Issue> = emptyList()
        private set
    private var openAIService: OpenAIService? = null
    private var fileWatcherRegistered = false
    private var autoRefreshJob: Job? = null
    private var debugSessionListener: XDebugSessionListener? = null

    companion object {
        fun getInstance(project: Project): GhostDebuggerService =
            project.getService(GhostDebuggerService::class.java)
    }

    fun setBridge(bridge: JcefBridge) {
        this.bridge = bridge
        bridge.initialize()
        registerFileWatcher()
        registerDebugSessionListener()
    }

    /**
     * Registers a bulk file listener that triggers auto-refresh on file saves.
     */
    private fun registerFileWatcher() {
        if (fileWatcherRegistered) return
        fileWatcherRegistered = true

        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val projectBase = project.basePath ?: return
                    val fileIndex = ProjectFileIndex.getInstance(project)
                    val ignoredDirs = setOf("node_modules", ".git", "build", ".gradle", ".idea", "target", "dist", "out")
                    
                    val hasRelevantChange = events.any { event ->
                        val file = event.file
                        val path = file?.path ?: ""
                        event is VFileContentChangeEvent &&
                        path.startsWith(projectBase) &&
                        !ignoredDirs.any { path.contains("/$it/") } &&
                        !fileIndex.isExcluded(file!!)
                    }
                    if (hasRelevantChange && currentGraph != null) {
                        scheduleAutoRefresh()
                    }
                }
            }
        )
    }

    private fun scheduleAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = scope.launch {
            delay(2000)
            withContext(Dispatchers.Swing) {
                bridge?.sendAutoRefreshStart()
            }
            analyzeProject()
        }
    }

    /**
     * Hooks into the IDE's debug sessions to provide visual debug overlays.
     * When the user starts debugging normally (Run → Debug), Aegis Debug
     * listens for frame changes and sends debug frame data to the webview.
     */
    private fun registerDebugSessionListener() {
        try {
            val debuggerManager = XDebuggerManager.getInstance(project)
            project.messageBus.connect().subscribe(
                XDebuggerManager.TOPIC,
                object : XDebuggerManagerListener {
                    override fun processStarted(debugProcess: XDebugProcess) {
                        log.info("Debug session started")
                        val session = debugProcess.session
                        attachToDebugSession(session)
                    }

                    override fun processStopped(debugProcess: XDebugProcess) {
                        log.info("Debug session stopped")
                        scope.launch(Dispatchers.Swing) {
                            bridge?.sendDebugSessionEnded()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            log.warn("Could not register XDebugger listener: ${e.message}")
        }
    }

    private fun attachToDebugSession(session: XDebugSession) {
        val listener = object : XDebugSessionListener {
            override fun sessionPaused() {
                log.info("Debug session paused")
                sendCurrentDebugFrame(session)
            }

            override fun sessionResumed() {
                log.info("Debug session resumed")
                scope.launch(Dispatchers.Swing) {
                    bridge?.sendDebugStateChanged("running")
                }
            }

            override fun sessionStopped() {
                log.info("Debug session stopped (listener)")
                scope.launch(Dispatchers.Swing) {
                    bridge?.sendDebugSessionEnded()
                }
            }

            override fun stackFrameChanged() {
                sendCurrentDebugFrame(session)
            }
        }

        debugSessionListener = listener
        session.addSessionListener(listener)
    }

    private fun sendCurrentDebugFrame(session: XDebugSession) {
        scope.launch {
            try {
                val frame = session.currentStackFrame ?: return@launch
                val sourcePosition = frame.sourcePosition ?: return@launch
                val filePath = sourcePosition.file.path.replace("\\", "/")
                val line = sourcePosition.line + 1 // 0-indexed to 1-indexed

                // Find the matching graph node
                val graph = currentGraph
                val nodeId = if (graph != null) {
                    graph.nodes.firstOrNull { node ->
                        val nodePath = node.filePath.replace("\\", "/")
                        filePath.endsWith(nodePath.substringAfterLast("/")) || nodePath == filePath
                    }?.id ?: filePath
                } else {
                    filePath
                }

                // Extract variable values from the stack frame
                val variables = mutableListOf<DebugVariable>()
                try {
                    // Use evaluator to get local variables — this is a simplified approach
                    // The full implementation would walk the XValueContainer children
                    val evaluator = frame.evaluator
                    // We'll send what we can — the frame itself is the main value
                    variables.add(DebugVariable(
                        name = "frame",
                        value = frame.toString().take(60),
                        type = "StackFrame"
                    ))
                } catch (e: Exception) {
                    log.debug("Could not extract debug variables: ${e.message}")
                }

                withContext(Dispatchers.Swing) {
                    bridge?.sendDebugFrame(nodeId, filePath, line, variables)
                    bridge?.sendDebugStateChanged("paused")
                }
            } catch (e: Exception) {
                log.warn("Failed to send debug frame: ${e.message}")
            }
        }
    }

    fun handleUIEvent(event: UIEvent) {
        when (event) {
            is UIEvent.NodeClicked -> handleNodeClicked(event.nodeId)
            is UIEvent.NodeDoubleClicked -> handleNodeDoubleClicked(event.nodeId)
            is UIEvent.FixRequested -> handleFixRequested(event.issueId, event.nodeId)

            is UIEvent.ImpactRequested -> handleImpactRequested(event.nodeId)
            is UIEvent.ExplainSystemRequested -> handleExplainSystem()
            is UIEvent.AnalyzeRequested -> analyzeProject()
            is UIEvent.BreakpointSet -> handleBreakpointSet(event.filePath, event.line)
            is UIEvent.BreakpointRemoved -> handleBreakpointRemoved(event.filePath, event.line)
            is UIEvent.ExportReportRequested -> handleExportReportRequested()
            is UIEvent.DebugStepOver -> handleDebugAction { it.stepOver(false) }
            is UIEvent.DebugStepInto -> handleDebugAction { it.stepInto() }
            is UIEvent.DebugStepOut -> handleDebugAction { it.stepOut() }
            is UIEvent.DebugResume -> handleDebugAction { it.resume() }
            is UIEvent.DebugPause -> handleDebugAction { it.pause() }
            is UIEvent.Unknown -> log.warn("Unknown UI event: ${event.raw}")
        }
    }

    /**
     * Executes an action on the current debug session if one exists.
     */
    private fun handleDebugAction(action: (XDebugSession) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val session = XDebuggerManager.getInstance(project).currentSession
                if (session != null) {
                    action(session)
                } else {
                    scope.launch(Dispatchers.Swing) {
                        bridge?.sendError("No active debug session. Start debugging first (Run → Debug).")
                    }
                }
            } catch (e: Exception) {
                log.warn("Debug action failed: ${e.message}")
                scope.launch(Dispatchers.Swing) {
                    bridge?.sendError("Debug action failed: ${e.message}")
                }
            }
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

                // 10. Refresh annotator
                ApplicationManager.getApplication().invokeLater {
                    try {
                        DaemonCodeAnalyzer.getInstance(project).restart()
                    } catch (e: Exception) {
                        log.warn("Could not restart DaemonCodeAnalyzer: ${e.message}")
                    }
                }

                // 11. Pre-fetch AI explanations for critical issues
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
                        bridge?.sendError("OpenAI API key not configured. Go to Settings → Tools → Aegis Debug")
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

    private fun handleExportReportRequested() {
        val graph = currentGraph
        if (graph == null) {
            scope.launch(Dispatchers.Swing) {
                bridge?.sendError("No analysis data available. Run 'Analyze Project' first.")
            }
            return
        }

        scope.launch {
            try {
                log.info("Generating HTML report...")
                val reportGenerator = ReportGenerator()
                val htmlContent = reportGenerator.generateHTMLReport(graph)

                val basePath = project.basePath ?: return@launch
                val reportFile = File(basePath, "aegis-debug-report.html")
                reportFile.writeText(htmlContent)

                log.info("Report saved to: ${reportFile.absolutePath}")

                try {
                    java.awt.Desktop.getDesktop().browse(reportFile.toURI())
                } catch (e: Exception) {
                    log.warn("Could not open browser: ${e.message}")
                }

                withContext(Dispatchers.Swing) {
                    bridge?.sendError("Report exported to: ${reportFile.name}")
                }
            } catch (e: Exception) {
                log.error("Failed to export report", e)
                withContext(Dispatchers.Swing) {
                    bridge?.sendError("Export failed: ${e.message}")
                }
            }
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

            Configura tu API key de OpenAI en Settings → Tools → Aegis Debug para obtener análisis detallados con IA.
        """.trimIndent()
    }

    fun dispose() {
        autoRefreshJob?.cancel()
        scope.cancel()
    }
}
