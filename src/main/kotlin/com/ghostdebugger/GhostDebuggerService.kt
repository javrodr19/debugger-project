package com.ghostdebugger

import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.ai.AIService
import com.ghostdebugger.ai.AIServiceFactory
import com.ghostdebugger.analysis.AnalysisEngine
import com.ghostdebugger.bridge.JcefBridge
import com.ghostdebugger.bridge.UIEvent
import com.ghostdebugger.fix.FixApplicator
import com.ghostdebugger.fix.FixerRegistry
import com.ghostdebugger.graph.GraphBuilder
import com.ghostdebugger.model.*
import com.ghostdebugger.parser.DependencyResolver
import com.ghostdebugger.parser.FileScanner
import com.ghostdebugger.parser.SymbolExtractor
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
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
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.io.File

@Service(Service.Level.PROJECT)
class GhostDebuggerService(private val project: Project) {

    private val log = logger<GhostDebuggerService>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val analysisLock = Object()
    @Volatile private var activeAnalysisIndicator: com.intellij.openapi.progress.ProgressIndicator? = null

    private var bridge: JcefBridge? = null
    private var currentGraph: ProjectGraph? = null
    private var lastInMemoryGraph: com.ghostdebugger.graph.InMemoryGraph? = null
    var currentIssues: List<Issue> = emptyList()
        private set
    @Volatile var issuesByFile: Map<String, List<Issue>> = emptyMap()
        private set
    @Volatile private var lastIssueFingerprints: Set<String> = emptySet()
    @Volatile var suppressUntil: Long = 0L

    var isAnalyzing: Boolean = false
        private set

    private fun updateIssues(newIssues: List<Issue>) {
        currentIssues = newIssues
        issuesByFile = newIssues.groupBy { it.filePath.replace("\\", "/") }
    }
    private var aiService: AIService? = null
    private var fileWatcherRegistered = false
    private var autoRefreshJob: Job? = null
    private var debugSessionListener: XDebugSessionListener? = null
    private val fixApplicator = FixApplicator()

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
                    if (System.currentTimeMillis() < suppressUntil) return
                    
                    val projectBase = project.basePath ?: return
                    val fileIndex = ProjectFileIndex.getInstance(project)
                    
                    val hasRelevantChange = events.any { event ->
                        val file = event.file
                        val path = file?.path ?: ""
                        event is VFileContentChangeEvent &&
                        path.startsWith(projectBase) &&
                        FileScanner.SUPPORTED_EXTENSIONS.contains(file?.extension) &&
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
            delay(7000)
            withContext(Dispatchers.Swing) {
                bridge?.sendAutoRefreshStart()
            }
            analyzeProject()
        }
    }

    /**
     * Hooks into the IDE's debug sessions to provide visual debug overlays.
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
                val line = sourcePosition.line + 1

                val graph = currentGraph
                val nodeId = if (graph != null) {
                    graph.nodes.firstOrNull { node ->
                        val nodePath = node.filePath.replace("\\", "/")
                        filePath.endsWith(nodePath.substringAfterLast("/")) || nodePath == filePath
                    }?.id ?: filePath
                } else {
                    filePath
                }

                val variables = mutableListOf<DebugVariable>()
                try {
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
            is UIEvent.ApplyFixRequested -> handleApplyFixRequested(event.issueId, event.fixId)

            is UIEvent.ImpactRequested -> handleImpactRequested(event.nodeId)
            is UIEvent.ExplainSystemRequested -> handleExplainSystem()
            is UIEvent.AnalyzeRequested -> analyzeProject()
            is UIEvent.CancelAnalysisRequested -> cancelAnalysis()
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
        synchronized(analysisLock) {
            activeAnalysisIndicator?.cancel()
        }

        val task = object : com.intellij.openapi.progress.Task.Backgroundable(project, "Aegis Debug: Analyzing project", true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                synchronized(analysisLock) { activeAnalysisIndicator = indicator }
                isAnalyzing = true
                indicator.isIndeterminate = false
                indicator.fraction = 0.0

                try {
                    runBlocking {
                        performAnalysis(indicator)
                    }
                } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                    log.info("Analysis canceled by user")
                    scope.launch(Dispatchers.Swing) {
                        bridge?.sendError("Analysis canceled.")
                    }
                } catch (t: Throwable) {
                    log.error("Analysis failed", t)
                    scope.launch(Dispatchers.Swing) {
                        bridge?.sendError("Analysis failed: ${t.message ?: t.javaClass.simpleName}")
                    }
                } finally {
                    isAnalyzing = false
                    synchronized(analysisLock) {
                        if (activeAnalysisIndicator === indicator) activeAnalysisIndicator = null
                    }
                }
            }
        }
        com.intellij.openapi.progress.ProgressManager.getInstance().run(task)
    }

    fun cancelAnalysis() {
        synchronized(analysisLock) {
            activeAnalysisIndicator?.cancel()
        }
        autoRefreshJob?.cancel()
    }

    private suspend fun performAnalysis(indicator: com.intellij.openapi.progress.ProgressIndicator) {
        withContext(Dispatchers.Swing) {
            bridge?.sendAnalysisStart()
        }

        log.info("Starting project analysis...")
        indicator.text = "Scanning files..."
        withContext(Dispatchers.Swing) { bridge?.sendAnalysisProgress("Scanning files...", 0.0) }

        val virtualFiles = ApplicationManager.getApplication().runReadAction<List<com.intellij.openapi.vfs.VirtualFile>> {
            FileScanner(project).scanFiles()
        }

        indicator.checkCanceled()
        indicator.fraction = 0.10
        log.info("Found ${virtualFiles.size} files")
        indicator.text = "Parsing files..."
        withContext(Dispatchers.Swing) { bridge?.sendAnalysisProgress("Parsing files...", 0.10) }

        val rawFiles = ApplicationManager.getApplication().runReadAction<List<ParsedFile>> {
            FileScanner(project).parsedFiles(virtualFiles)
        }

        indicator.checkCanceled()
        indicator.fraction = 0.25
        indicator.text = "Extracting symbols..."
        withContext(Dispatchers.Swing) { bridge?.sendAnalysisProgress("Extracting symbols...", 0.25) }

        val extractor = SymbolExtractor()
        val parsedFiles = rawFiles.map { 
            indicator.checkCanceled()
            extractor.extract(it) 
        }

        indicator.fraction = 0.40
        indicator.text = "Resolving dependencies..."
        withContext(Dispatchers.Swing) { bridge?.sendAnalysisProgress("Resolving dependencies...", 0.40) }
        
        val resolver = DependencyResolver(project.basePath ?: "")
        val dependencies = resolver.resolve(parsedFiles)

        indicator.checkCanceled()
        indicator.fraction = 0.50
        indicator.text = "Building graph..."
        withContext(Dispatchers.Swing) { bridge?.sendAnalysisProgress("Building graph...", 0.50) }
        
        val graphBuilder = GraphBuilder()
        val inMemoryGraph = graphBuilder.build(parsedFiles, dependencies)
        lastInMemoryGraph = inMemoryGraph

        indicator.checkCanceled()
        indicator.fraction = 0.60
        indicator.text = "Running analyzers..."
        withContext(Dispatchers.Swing) { bridge?.sendAnalysisProgress("Running analyzers...", 0.60) }

        val analysisContext = AnalysisContext(
            graph = inMemoryGraph,
            project = project,
            parsedFiles = parsedFiles
        )
        
        val analysisResult = AnalysisEngine(progress = indicator).analyze(analysisContext, indicator)
        val newIssues = analysisResult.issues
        val newFingerprints = newIssues.map { it.fingerprint() }.toSet()
        val issuesChanged = newFingerprints != lastIssueFingerprints
        
        lastIssueFingerprints = newFingerprints
        updateIssues(newIssues)

        indicator.checkCanceled()
        indicator.fraction = 0.90
        indicator.text = "Publishing results..."
        withContext(Dispatchers.Swing) { bridge?.sendAnalysisProgress("Publishing results...", 0.90) }

        graphBuilder.applyIssues(inMemoryGraph, analysisResult.issues)

        val projectGraph = inMemoryGraph.toProjectGraph(project.name)
        currentGraph = projectGraph

        log.info("Analysis complete: ${analysisResult.issues.size} issues found")

        withContext(Dispatchers.Swing) {
            bridge?.sendGraphData(projectGraph)
            bridge?.sendAnalysisComplete(
                analysisResult.metrics.errorCount,
                analysisResult.metrics.warningCount,
                analysisResult.metrics.healthScore
            )
            bridge?.sendEngineStatus(analysisResult.engineStatus)
        }
        indicator.fraction = 1.0
        withContext(Dispatchers.Swing) { bridge?.sendAnalysisProgress("Complete", 1.0) }

        if (issuesChanged) {
            ApplicationManager.getApplication().invokeLater {
                try {
                    DaemonCodeAnalyzer.getInstance(project).restart()
                } catch (e: Exception) {
                    log.warn("Could not restart DaemonCodeAnalyzer: ${e.message}")
                }
            }
        }
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

        val fixer = FixerRegistry.forIssue(issue)
        if (fixer != null) {
            val fileContent = try {
                java.io.File(issue.filePath).readText()
            } catch (e: Exception) {
                log.warn("Could not read file for deterministic fix: ${issue.filePath}", e)
                null
            }
            val deterministicFix = fileContent?.let { fixer.generateFix(issue, it) }
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

        val fixer = FixerRegistry.forIssue(issue)
        val fix = if (fixer != null) {
            try {
                val content = java.io.File(issue.filePath).readText()
                fixer.generateFix(issue, content)
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
                reanalyzeFile(issue.filePath)
            } else {
                val msg = if (applied is com.ghostdebugger.fix.FixApplyResult.Rejected) applied.reason else "Fix application failed for issue $issueId."
                withContext(Dispatchers.Swing) {
                    bridge?.sendError(msg)
                }
            }
        }
    }

    private fun reanalyzeFile(filePath: String) {
        scope.launch {
            try {
                log.info("Starting targeted re-analysis for $filePath")
                val inMemoryGraph = lastInMemoryGraph ?: run {
                    analyzeProject()
                    return@launch
                }
                
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@launch
                
                val parsedFile = ApplicationManager.getApplication().runReadAction<ParsedFile?> {
                    FileScanner(project).parsedFiles(listOf(virtualFile)).firstOrNull()
                } ?: return@launch
                
                val extractor = SymbolExtractor()
                val updatedFile = extractor.extract(parsedFile)
                
                val ctx = AnalysisContext(
                    graph = inMemoryGraph,
                    project = project,
                    parsedFiles = listOf(updatedFile)
                )
                
                val engine = AnalysisEngine()
                val analysisResult = engine.analyze(ctx)
                
                val newFileIssues = analysisResult.issues
                updateIssues(currentIssues.filterNot { it.filePath == filePath } + newFileIssues)
                
                val graphBuilder = GraphBuilder()
                val nodeId = graphBuilder.normalizeId(filePath)
                val node = inMemoryGraph.getNode(nodeId)
                if (node != null) {
                    val status = when {
                        newFileIssues.any { it.severity == IssueSeverity.ERROR } -> NodeStatus.ERROR
                        newFileIssues.any { it.severity == IssueSeverity.WARNING } -> NodeStatus.WARNING
                        else -> NodeStatus.HEALTHY
                    }
                    inMemoryGraph.updateNode(node.copy(issues = newFileIssues, status = status))
                    
                    withContext(Dispatchers.Swing) {
                        bridge?.sendNodeUpdate(nodeId, status)
                        bridge?.sendIssuesForFile(filePath, newFileIssues)
                    }
                }
            } catch (e: Exception) {
                log.warn("Targeted re-analysis failed for $filePath", e)
                analyzeProject()
            }
        }
    }

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
        val graph = currentGraph
        if (graph == null) {
            scope.launch(Dispatchers.Swing) {
                bridge?.sendError("No analysis data available. Run 'Analyze Project' first.")
            }
            return
        }

        scope.launch {
            try {
                val reportGenerator = ReportGenerator()
                val htmlContent = reportGenerator.generateHTMLReport(graph)

                val basePath = project.basePath ?: return@launch
                val reportFile = File(basePath, "aegis-debug-report.html")
                reportFile.writeText(htmlContent)

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

    fun dispose() {
        autoRefreshJob?.cancel()
        scope.cancel()
    }
}
