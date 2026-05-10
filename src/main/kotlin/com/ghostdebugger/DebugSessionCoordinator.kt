package com.ghostdebugger

import com.ghostdebugger.model.DebugVariable
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * Owns the IntelliJ XDebugger plumbing — session subscription, pause/resume listener,
 * stack-frame extraction, and the five debug-action methods invoked from the JCEF
 * tool window. Lifted out of GhostDebuggerService in V1.5.
 *
 * V2's debug-session cross-check (observe variable values at breakpoints to
 * promote/demote null-safety findings) will land inside [sendCurrentDebugFrame].
 */
@Service(Service.Level.PROJECT)
internal class DebugSessionCoordinator(private val project: Project) : Disposable {

    private val log = logger<DebugSessionCoordinator>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var registered = false
    private var debugSessionListener: XDebugSessionListener? = null

    init {
        Disposer.register(project, this)
    }

    /** Idempotent. Safe to call multiple times. */
    fun start() {
        if (registered) return
        registered = true
        try {
            project.messageBus.connect(this).subscribe(
                XDebuggerManager.TOPIC,
                object : XDebuggerManagerListener {
                    override fun processStarted(debugProcess: XDebugProcess) {
                        log.info("Debug session started")
                        attachToDebugSession(debugProcess.session)
                    }

                    override fun processStopped(debugProcess: XDebugProcess) {
                        log.info("Debug session stopped")
                        scope.launch(Dispatchers.Swing) {
                            GhostDebuggerService.getInstance(project).jcefBridge()?.sendDebugSessionEnded()
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
                    GhostDebuggerService.getInstance(project).jcefBridge()?.sendDebugStateChanged("running")
                }
            }

            override fun sessionStopped() {
                log.info("Debug session stopped (listener)")
                scope.launch(Dispatchers.Swing) {
                    GhostDebuggerService.getInstance(project).jcefBridge()?.sendDebugSessionEnded()
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

                val graph = GhostDebuggerService.getInstance(project).currentGraph
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
                    val bridge = GhostDebuggerService.getInstance(project).jcefBridge()
                    bridge?.sendDebugFrame(nodeId, filePath, line, variables)
                    bridge?.sendDebugStateChanged("paused")
                }
            } catch (e: Exception) {
                log.warn("Failed to send debug frame: ${e.message}")
            }
        }
    }

    fun stepOver() = withCurrentSession { it.stepOver(false) }
    fun stepInto() = withCurrentSession { it.stepInto() }
    fun stepOut() = withCurrentSession { it.stepOut() }
    fun resume() = withCurrentSession { it.resume() }
    fun pause() = withCurrentSession { it.pause() }

    private fun withCurrentSession(action: (XDebugSession) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val session = XDebuggerManager.getInstance(project).currentSession
                if (session != null) {
                    action(session)
                } else {
                    scope.launch(Dispatchers.Swing) {
                        GhostDebuggerService.getInstance(project).jcefBridge()
                            ?.sendError("No active debug session. Start debugging first (Run → Debug).")
                    }
                }
            } catch (e: Exception) {
                log.warn("Debug action failed: ${e.message}")
                scope.launch(Dispatchers.Swing) {
                    GhostDebuggerService.getInstance(project).jcefBridge()
                        ?.sendError("Debug action failed: ${e.message}")
                }
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): DebugSessionCoordinator =
            project.getService(DebugSessionCoordinator::class.java)
    }
}
