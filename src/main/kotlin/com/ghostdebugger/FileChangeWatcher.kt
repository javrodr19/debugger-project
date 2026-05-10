package com.ghostdebugger

import com.ghostdebugger.parser.FileScanner
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * Watches the project's VFS for content changes to supported source files and triggers
 * a debounced re-analysis. Lifted out of GhostDebuggerService in V1.5; lifecycle and
 * listener registration semantics are preserved verbatim.
 *
 * Reads `GhostDebuggerService.suppressUntil` to skip events during fix-application
 * windows (set by `handleApplyFixRequested`).
 */
@Service(Service.Level.PROJECT)
internal class FileChangeWatcher(private val project: Project) : Disposable {

    private val log = logger<FileChangeWatcher>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var autoRefreshJob: Job? = null
    @Volatile private var registered = false

    init {
        Disposer.register(project, this)
    }

    /** Idempotent. Safe to call multiple times. */
    fun start() {
        if (registered) return
        registered = true

        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val service = GhostDebuggerService.getInstance(project)
                    if (System.currentTimeMillis() < service.suppressUntil) return

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
                    if (hasRelevantChange && service.currentGraph != null) {
                        scheduleAutoRefresh()
                    }
                }
            }
        )
    }

    fun cancelAutoRefresh() {
        autoRefreshJob?.cancel()
    }

    private fun scheduleAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = scope.launch {
            delay(7000)
            withContext(Dispatchers.Swing) {
                GhostDebuggerService.getInstance(project).jcefBridge()?.sendAutoRefreshStart()
            }
            // V1.5 plan note: this calls the facade today; Commit 5 swaps it to call the
            // AnalysisOrchestrator directly once that service exists.
            GhostDebuggerService.getInstance(project).analyzeProject()
        }
    }

    override fun dispose() {
        autoRefreshJob?.cancel()
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): FileChangeWatcher =
            project.getService(FileChangeWatcher::class.java)
    }
}
