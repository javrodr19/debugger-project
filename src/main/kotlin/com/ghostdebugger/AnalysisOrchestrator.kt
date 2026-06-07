package com.ghostdebugger

import com.ghostdebugger.ai.AIService
import com.ghostdebugger.ai.AIServiceFactory
import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.analysis.AnalysisEngine
import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.fix.engine.FixEngine
import com.ghostdebugger.graph.GraphBuilder
import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.NodeStatus
import com.ghostdebugger.model.ParsedFile
import com.ghostdebugger.parser.DependencyResolver
import com.ghostdebugger.parser.FileScanner
import com.ghostdebugger.parser.SymbolExtractor
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.store.StackTraceParser
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestStatusListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * Owns the full analysis lifecycle: full-project analysis, targeted re-analysis after
 * a fix is applied, and the dependent-cascade pass that propagates updates to transitive
 * dependents. Lifted out of GhostDebuggerService in V1.5.
 *
 * State pattern: this service reads project-level state (`service.currentGraph`,
 * `service.lastInMemoryGraph`) directly via the facade and mutates it through the
 * facade's `internal fun updateIssues(...)` and `internal var currentGraph`/
 * `lastInMemoryGraph`. The facade is the single writer surface for shared issue/graph
 * state; this service is one of the writers but routes through facade-owned mutators.
 *
 * V2's test-runner cross-check (promote findings to RUNTIME_CONFIRMED based on executed
 * test lines) will land inside [performAnalysis] or a sibling method here.
 */
@Service(Service.Level.PROJECT)
internal class AnalysisOrchestrator(private val project: Project) : Disposable {

    private val log = logger<AnalysisOrchestrator>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val analysisLock = Object()
    @Volatile private var activeAnalysisIndicator: ProgressIndicator? = null
    @Volatile private var lastIssueFingerprints: Set<String> = emptySet()

    @Volatile var isAnalyzing: Boolean = false
        private set

    init {
        Disposer.register(project, this)
    }

    fun analyzeProject() {
        synchronized(analysisLock) {
            activeAnalysisIndicator?.cancel()
        }

        val task = object : Task.Backgroundable(project, "Aegis Debug: Analyzing project", true) {
            override fun run(indicator: ProgressIndicator) {
                synchronized(analysisLock) { activeAnalysisIndicator = indicator }
                isAnalyzing = true
                indicator.isIndeterminate = false
                indicator.fraction = 0.0

                try {
                    runBlocking {
                        performAnalysis(indicator)
                    }
                } catch (e: ProcessCanceledException) {
                    log.info("Analysis canceled by user")
                    scope.launch(Dispatchers.Swing) {
                        service().jcefBridge()?.sendError("Analysis canceled.")
                    }
                } catch (t: Throwable) {
                    log.error("Analysis failed", t)
                    scope.launch(Dispatchers.Swing) {
                        service().jcefBridge()?.sendError("Analysis failed: ${t.message ?: t.javaClass.simpleName}")
                    }
                } finally {
                    isAnalyzing = false
                    synchronized(analysisLock) {
                        if (activeAnalysisIndicator === indicator) activeAnalysisIndicator = null
                    }
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    fun cancelAnalysis() {
        synchronized(analysisLock) {
            activeAnalysisIndicator?.cancel()
        }
    }

    private suspend fun performAnalysis(indicator: ProgressIndicator) {
        val svc = service()
        withContext(Dispatchers.Swing) {
            svc.jcefBridge()?.sendAnalysisStart()
            // Commit all documents before starting analysis to sync PSI
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }

        log.info("Starting project analysis...")
        indicator.text = "Scanning files..."
        withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendAnalysisProgress("Scanning files...", 0.0) }

        val virtualFiles = ApplicationManager.getApplication().runReadAction<List<VirtualFile>> {
            FileScanner(project).scanFiles()
        }

        indicator.checkCanceled()
        indicator.fraction = 0.10
        log.info("Found ${virtualFiles.size} files")
        indicator.text = "Parsing files..."
        withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendAnalysisProgress("Parsing files...", 0.10) }

        val rawFiles = ApplicationManager.getApplication().runReadAction<List<ParsedFile>> {
            FileScanner(project).parsedFiles(virtualFiles)
        }

        indicator.checkCanceled()
        indicator.fraction = 0.25
        indicator.text = "Extracting symbols..."
        withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendAnalysisProgress("Extracting symbols...", 0.25) }

        val extractor = SymbolExtractor(project)
        val parsedFiles = rawFiles.map {
            indicator.checkCanceled()
            extractor.extract(it)
        }

        indicator.fraction = 0.40
        indicator.text = "Resolving dependencies..."
        withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendAnalysisProgress("Resolving dependencies...", 0.40) }

        val resolver = DependencyResolver(project.basePath ?: "")
        val dependencies = resolver.resolve(parsedFiles)

        indicator.checkCanceled()
        indicator.fraction = 0.50
        indicator.text = "Building graph..."
        withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendAnalysisProgress("Building graph...", 0.50) }

        val graphBuilder = GraphBuilder()
        val inMemoryGraph = graphBuilder.build(parsedFiles, dependencies)
        svc.lastInMemoryGraph = inMemoryGraph

        indicator.checkCanceled()
        indicator.fraction = 0.60
        indicator.text = "Running analyzers..."
        withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendAnalysisProgress("Running analyzers...", 0.60) }

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
        svc.updateIssues(newIssues)

        indicator.checkCanceled()
        indicator.fraction = 0.90
        indicator.text = "Publishing results..."
        withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendAnalysisProgress("Publishing results...", 0.90) }

        graphBuilder.applyIssues(inMemoryGraph, analysisResult.issues)

        val projectGraph = inMemoryGraph.toProjectGraph(project.name)
        svc.currentGraph = projectGraph

        log.info("Analysis complete: ${analysisResult.issues.size} issues found")

        withContext(Dispatchers.Swing) {
            val bridge = svc.jcefBridge()
            bridge?.sendGraphData(projectGraph)
            bridge?.sendAnalysisComplete(
                analysisResult.metrics.errorCount,
                analysisResult.metrics.warningCount,
                analysisResult.metrics.healthScore
            )
            bridge?.sendEngineStatus(analysisResult.engineStatus)
        }
        indicator.fraction = 1.0
        withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendAnalysisProgress("Complete", 1.0) }

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

    fun reanalyzeFile(filePath: String) {
        scope.launch {
            try {
                log.info("Starting targeted re-analysis for $filePath")
                val svc = service()

                withContext(Dispatchers.Swing) {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                }

                val inMemoryGraph = svc.lastInMemoryGraph ?: run {
                    analyzeProject()
                    return@launch
                }

                val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@launch

                val parsedFile = ApplicationManager.getApplication().runReadAction<ParsedFile?> {
                    FileScanner(project).parsedFiles(listOf(virtualFile)).firstOrNull()
                } ?: return@launch

                val extractor = SymbolExtractor(project)
                val updatedFile = extractor.extract(parsedFile)

                val ctx = AnalysisContext(
                    graph = inMemoryGraph,
                    project = project,
                    parsedFiles = listOf(updatedFile)
                )

                val engine = AnalysisEngine()
                val analysisResult = engine.analyze(ctx)

                val newFileIssues = analysisResult.issues
                svc.updateIssues(svc.currentIssues.filterNot { it.filePath == filePath } + newFileIssues)

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
                        val ch = svc.bridgeChannel()
                        ch?.sendNodeUpdate(nodeId, status)
                        ch?.sendIssuesForFile(filePath, newFileIssues)
                    }

                    cascadeDependents(inMemoryGraph, nodeId)
                }
            } catch (e: Exception) {
                if (e is ProcessCanceledException) throw e
                log.warn("Targeted re-analysis failed for $filePath", e)
                analyzeProject()
            }
        }
    }

    private suspend fun cascadeDependents(
        inMemoryGraph: InMemoryGraph,
        primaryNodeId: String
    ) {
        val settings = GhostDebuggerSettings.getInstance().snapshot()
        val cap = settings.maxDependentsToReanalyze
        if (cap <= 0) return

        val depIds = inMemoryGraph.calculateImpact(primaryNodeId).take(cap)
        if (depIds.isEmpty()) return

        log.info("Cascading static-only re-analysis to ${depIds.size} dependent(s) of $primaryNodeId")

        val vFiles = depIds.mapNotNull { id ->
            val node = inMemoryGraph.getNode(id) ?: return@mapNotNull null
            LocalFileSystem.getInstance().findFileByPath(node.filePath)?.let { vf -> id to vf }
        }
        if (vFiles.isEmpty()) return

        val parsedFiles = ApplicationManager.getApplication().runReadAction<List<ParsedFile>> {
            FileScanner(project).parsedFiles(vFiles.map { it.second })
        }
        val extractor = SymbolExtractor(project)
        val extracted = parsedFiles.map { extractor.extract(it) }

        val ctx = AnalysisContext(
            graph = inMemoryGraph,
            project = project,
            parsedFiles = extracted
        )

        val engine = AnalysisEngine()
        val result = try {
            engine.analyzeStaticOnly(ctx)
        } catch (e: Exception) {
            if (e is ProcessCanceledException) throw e
            log.warn("Dependent static-only pass failed; skipping cascade", e)
            return
        }

        val svc = service()
        val analyzedPaths = extracted.map { it.path.replace("\\", "/") }.toSet()
        val issuesByPath = result.issues.groupBy { it.filePath.replace("\\", "/") }

        svc.updateIssues(
            svc.currentIssues.filterNot { existing ->
                existing.filePath.replace("\\", "/") in analyzedPaths
            } + result.issues
        )

        for ((depId, _) in vFiles) {
            val node = inMemoryGraph.getNode(depId) ?: continue
            val depPath = node.filePath
            // Only update dependents that actually went through analyzeStaticOnly. A dep that
            // FileScanner couldn't read is silently absent from `extracted`; updating its
            // status from an empty issues list would falsely demote it to HEALTHY.
            if (depPath.replace("\\", "/") !in analyzedPaths) continue

            val depIssues = issuesByPath[depPath.replace("\\", "/")] ?: emptyList()
            val status = when {
                depIssues.any { it.severity == IssueSeverity.ERROR } -> NodeStatus.ERROR
                depIssues.any { it.severity == IssueSeverity.WARNING } -> NodeStatus.WARNING
                else -> NodeStatus.HEALTHY
            }
            inMemoryGraph.updateNode(node.copy(issues = depIssues, status = status))
            withContext(Dispatchers.Swing) {
                val ch = svc.bridgeChannel()
                ch?.sendNodeUpdate(depId, status)
                ch?.sendIssuesForFile(depPath, depIssues)
            }
        }
    }

    // ── Test seams (package-private; used only by BasePlatformTestCase tests). ──

    internal fun installTestGraph(graph: InMemoryGraph) {
        service().lastInMemoryGraph = graph
    }

    /**
     * Synchronous test-only mirror of [cascadeDependents] that skips re-parsing
     * and analysis; it walks the impact set, caps at [cap], and fires the bridge
     * for each dependent. Used to verify dispatch + cap semantics without
     * standing up a real PSI fixture for every dependent file.
     */
    internal fun cascadeDependentsForTest(changedFilePath: String, cap: Int) {
        val svc = service()
        val graph = svc.lastInMemoryGraph ?: return
        if (cap <= 0) return
        val normalized = changedFilePath.trimStart('/')
        val dependents = graph.calculateImpact(normalized).take(cap)
        for (depId in dependents) {
            val node = graph.getNode(depId) ?: continue
            val status = NodeStatus.HEALTHY
            graph.updateNode(node.copy(status = status))
            val ch = svc.bridgeChannel()
            ch?.sendNodeUpdate(depId, status)
            ch?.sendIssuesForFile(node.filePath, emptyList())
        }
    }

    internal fun handleTestSuiteFinished(root: AbstractTestProxy) {
        val failedLeaves = mutableListOf<AbstractTestProxy>()
        collectFailedLeafTests(root, failedLeaves)
        if (failedLeaves.isEmpty()) return

        val svc = service()
        val currentIssues = svc.currentIssues
        if (currentIssues.isEmpty()) return

        val toUpdate = currentIssues.toMutableList()
        var changed = false

        for (leaf in failedLeaves) {
            val stacktrace = leaf.stacktrace ?: continue
            val frames = StackTraceParser.parse(stacktrace)
            if (frames.isEmpty()) continue

            for (frame in frames) {
                for (i in toUpdate.indices) {
                    val issue = toUpdate[i]
                    if (issue.line == frame.line && 
                        issue.filePath.replace("\\", "/").endsWith(frame.fileName)
                    ) {
                        val isConfirmed = issue.sources.contains(IssueSource.RUNTIME_CONFIRMED)
                        if (!isConfirmed || issue.confidence != 1.0) {
                            val newSources = if (!isConfirmed) issue.sources + IssueSource.RUNTIME_CONFIRMED else issue.sources
                            toUpdate[i] = issue.copy(sources = newSources, confidence = 1.0)
                            changed = true
                        }
                    }
                }
            }
        }

        if (changed) {
            svc.updateIssues(toUpdate)
            
            // Also notify the JCEF bridge of the updated issues
            val jcef = svc.jcefBridge()
            if (jcef != null) {
                // Update graph and affected files
                val inMemoryGraph = svc.lastInMemoryGraph
                if (inMemoryGraph != null) {
                    for (issue in toUpdate) {
                        val nodeId = com.ghostdebugger.graph.GraphBuilder().normalizeId(issue.filePath)
                        val node = inMemoryGraph.getNode(nodeId)
                        if (node != null) {
                            val updatedNodeIssues = node.issues.map { old ->
                                toUpdate.find { it.id == old.id } ?: old
                            }
                            inMemoryGraph.updateNode(node.copy(issues = updatedNodeIssues))
                        }
                    }
                    val projectGraph = inMemoryGraph.toProjectGraph(project.name)
                    svc.currentGraph = projectGraph
                    jcef.sendGraphData(projectGraph)
                }
                
                // Group updated issues by file and push
                val grouped = toUpdate.groupBy { it.filePath }
                for ((filePath, fileIssues) in grouped) {
                    jcef.sendIssuesForFile(filePath, fileIssues)
                }
            }
        }
    }

    private fun collectFailedLeafTests(
        proxy: AbstractTestProxy,
        result: MutableList<AbstractTestProxy>
    ) {
        if (proxy.isLeaf) {
            if (!proxy.isPassed) {
                result.add(proxy)
            }
        } else {
            val children = proxy.children
            if (children != null) {
                for (child in children) {
                    collectFailedLeafTests(child, result)
                }
            }
        }
    }

    /**
     * Apply a deterministic fix for [issue] through the Phase-2b Tier-2 verify gate
     * ([FixEngine.fixVerified]) off the EDT, using the file's current findings as the baseline.
     * On success, re-analyzes the file so the resolved issue clears; on rejection, surfaces a
     * balloon notification. The [fixVerified] seam is injectable for tests. Returns the launched
     * [Job] so callers/tests can await completion.
     */
    internal fun applyVerifiedFix(
        issue: Issue,
        virtualFile: VirtualFile,
        content: String,
        baselineProvider: suspend (VirtualFile) -> List<Issue> =
            { com.ghostdebugger.fix.engine.SingleFileStaticReanalysis(project).issuesFor(it) },
        fixVerified: suspend (Issue, VirtualFile, String, List<Issue>) -> FixApplyResult =
            { i, v, c, b -> FixEngine(project).fixSupervised(i, v, c, b, resolveAiService()) },
    ): Job = scope.launch {
        try {
            val baseline = baselineProvider(virtualFile)
            when (val result = fixVerified(issue, virtualFile, content, baseline)) {
                is FixApplyResult.Success -> reanalyzeFile(virtualFile.path)
                is FixApplyResult.Rejected -> notifyFixRejected(issue, result.reason)
                is FixApplyResult.Failed ->
                    notifyFixRejected(issue, result.throwable.message ?: "Fix failed unexpectedly.")
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.warn("Verified fix failed for ${issue.filePath}", e)
            notifyFixRejected(issue, e.message ?: "Fix failed unexpectedly.")
        }
    }

    /** Surface a verify-gate rejection to the user via the existing GhostDebugger balloon group. */
    private fun notifyFixRejected(issue: Issue, reason: String) {
        log.warn("Verified fix rejected for ${issue.fingerprint()}: $reason")
        NotificationGroupManager.getInstance()
            .getNotificationGroup("GhostDebugger")
            .createNotification("Aegis Debug couldn't apply the fix", reason, NotificationType.WARNING)
            .notify(project)
    }

    /** Resolve the configured AIService, or null when AI is disabled / unconfigured. */
    private fun resolveAiService(): AIService? {
        val settings = GhostDebuggerSettings.getInstance().snapshot()
        if (settings.aiProvider == AIProvider.NONE) return null
        return AIServiceFactory.create(settings, ApiKeyManager.getApiKey())
    }

    private fun service(): GhostDebuggerService = GhostDebuggerService.getInstance(project)

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): AnalysisOrchestrator =
            project.getService(AnalysisOrchestrator::class.java)
    }
}
