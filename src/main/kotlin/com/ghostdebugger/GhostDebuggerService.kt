package com.ghostdebugger

import com.ghostdebugger.bridge.BridgeChannel
import com.ghostdebugger.bridge.JcefBridge
import com.ghostdebugger.bridge.UIEvent
import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ProjectGraph
import com.ghostdebugger.store.RuntimeEvidenceStore
import com.ghostdebugger.store.SuppressionMemoryService
import com.ghostdebugger.store.TestRunObserver
import com.ghostdebugger.store.DebugObserver
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

/**
 * Project-level facade for Aegis Debug. Post-V1.5 this is intentionally thin: it owns
 * shared project state (currentIssues, currentGraph, lastInMemoryGraph, suppressUntil)
 * as the single writer surface, exposes the public entry points that external callers
 * depend on (`getInstance(project)`, `setBridge`, `analyzeProject`, `cancelAnalysis`,
 * `handleUIEvent`), and delegates behavior to focused collaborators:
 *
 *   - AnalysisOrchestrator    — analysis lifecycle + cancellation + cascade
 *   - UIEventRouter           — UI event dispatch + AI service caching + handlers
 *   - FileChangeWatcher       — VFS auto-refresh listener
 *   - DebugSessionCoordinator — XDebugger session + step-action dispatch
 *
 * **State-sharing rule (see CLAUDE.md Conventions):** the facade is the only writer
 * surface for `currentIssues` / `issuesByFile`. Collaborators read via `service.X` and
 * mutate via `service.updateIssues(...)`. Direct writes from a collaborator
 * (`orchestrator.currentIssues = ...`) are forbidden — they re-introduce the scattered
 * mutation V1.5 was designed to eliminate.
 */
@Service(Service.Level.PROJECT)
class GhostDebuggerService(private val project: Project) : Disposable {

    private val log = logger<GhostDebuggerService>()

    init {
        Disposer.register(project, this)
    }

    // ── State (single writer surface) ──────────────────────────────────────
    private var bridge: JcefBridge? = null
    @Volatile private var testBridgeChannel: BridgeChannel? = null
    internal var currentGraph: ProjectGraph? = null
    internal var lastInMemoryGraph: InMemoryGraph? = null
    var currentIssues: List<Issue> = emptyList()
        private set
    @Volatile var issuesByFile: Map<String, List<Issue>> = emptyMap()
        private set
    @Volatile var suppressUntil: Long = 0L

    val isAnalyzing: Boolean get() = AnalysisOrchestrator.getInstance(project).isAnalyzing

    internal fun updateIssues(newIssues: List<Issue>) {
        currentIssues = newIssues
        issuesByFile = newIssues.groupBy { it.filePath.replace("\\", "/") }
        
        // V2.0 orphan GC
        val activeFingerprints = newIssues.map { it.fingerprint() }.toSet()
        RuntimeEvidenceStore.getInstance(project).clearOrphans(activeFingerprints)
    }

    // ── Bridge accessors ───────────────────────────────────────────────────

    fun runtimeEvidenceStore(): RuntimeEvidenceStore = RuntimeEvidenceStore.getInstance(project)
    fun suppressionMemory(): SuppressionMemoryService = SuppressionMemoryService.getInstance(project)

    /**
     * Returns the bridge surface that exposes only [BridgeChannel] methods. Tests can
     * substitute a recording stub via [setBridgeForTest].
     */
    internal fun bridgeChannel(): BridgeChannel? = testBridgeChannel ?: bridge

    /**
     * Returns the full [JcefBridge] when one is installed (i.e., the tool window has
     * been opened). Null in headless unit-test contexts where only a recording
     * BridgeChannel is installed via [setBridgeForTest].
     */
    internal fun jcefBridge(): JcefBridge? = bridge

    // ── Public entry points (preserve V1.4.1 API) ─────────────────────────

    fun setBridge(bridge: JcefBridge) {
        this.bridge = bridge
        bridge.initialize()
        FileChangeWatcher.getInstance(project).start()
        DebugSessionCoordinator.getInstance(project).start()
        
        // V2.0: Start observers
        TestRunObserver.getInstance(project).start()
        DebugObserver.getInstance(project).start()
    }

    fun handleUIEvent(event: UIEvent) = UIEventRouter.getInstance(project).handle(event)

    fun analyzeProject() = AnalysisOrchestrator.getInstance(project).analyzeProject()

    fun cancelAnalysis() {
        AnalysisOrchestrator.getInstance(project).cancelAnalysis()
        FileChangeWatcher.getInstance(project).cancelAutoRefresh()
    }

    fun refreshIssuesUI() {
        val bridge = jcefBridge() ?: return
        issuesByFile.forEach { (filePath, fileIssues) ->
            bridge.sendIssuesForFile(filePath, fileIssues)
        }
    }

    // ── Test seams (package-private; used only by BasePlatformTestCase tests) ──

    internal fun setBridgeForTest(channel: BridgeChannel) {
        this.testBridgeChannel = channel
    }

    internal fun installTestGraph(graph: InMemoryGraph) =
        AnalysisOrchestrator.getInstance(project).installTestGraph(graph)

    internal fun cascadeDependentsForTest(changedFilePath: String, cap: Int) =
        AnalysisOrchestrator.getInstance(project).cascadeDependentsForTest(changedFilePath, cap)

    override fun dispose() {
        // Each collaborator service registers its own Disposer.register(project, this) and
        // owns its CoroutineScope; the platform tears them down on project close.
    }

    companion object {
        fun getInstance(project: Project): GhostDebuggerService =
            project.getService(GhostDebuggerService::class.java)
    }
}
