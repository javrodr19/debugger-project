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
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
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
                    if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                    log.debug("Could not extract debug variables: ${e.message}")
                }

                withContext(Dispatchers.Swing) {
                    val bridge = GhostDebuggerService.getInstance(project).jcefBridge()
                    bridge?.sendDebugFrame(nodeId, filePath, line, variables)
                    bridge?.sendDebugStateChanged("paused")
                }

                performDebugSessionCrossCheck(session, filePath, line)
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                log.warn("Failed to send debug frame: ${e.message}")
            }
        }
    }

    internal fun extractVariableName(title: String, description: String): String? {
        val varRegex = "Variable '([^']+)'|Field '([^']+)'|Property '([^']+)'|Nullable '([^']+)'|Expression '([^']+)'|Null reference: (\\w+)".toRegex()
        val match = varRegex.find(description) ?: varRegex.find(title) ?: return null
        for (i in 1 until match.groupValues.size) {
            val valStr = match.groupValues[i]
            if (valStr.isNotEmpty()) return valStr
        }
        return null
    }

    internal fun performDebugSessionCrossCheck(session: XDebugSession, filePath: String, line: Int) {
        val frame = session.currentStackFrame ?: return
        val evaluator = frame.evaluator ?: return
        val service = GhostDebuggerService.getInstance(project)
        val normalizedPath = filePath.replace("\\", "/")

        val relevantIssues = service.currentIssues.filter {
            it.filePath.replace("\\", "/") == normalizedPath &&
            it.line == line &&
            (it.type == com.ghostdebugger.model.IssueType.NULL_SAFETY || it.type == com.ghostdebugger.model.IssueType.STATE_BEFORE_INIT)
        }

        for (issue in relevantIssues) {
            val varName = extractVariableName(issue.title, issue.description) ?: continue
            log.info("Cross-checking variable '$varName' on line $line")

            evaluator.evaluate(varName, object : com.intellij.xdebugger.evaluation.XDebuggerEvaluator.XEvaluationCallback {
                override fun evaluated(result: com.intellij.xdebugger.frame.XValue) {
                    result.computePresentation(object : com.intellij.xdebugger.frame.XValueNode {
                        override fun setPresentation(icon: javax.swing.Icon?, type: String?, value: String, hasChildren: Boolean) {
                            val isConfirmed = if (issue.type == com.ghostdebugger.model.IssueType.STATE_BEFORE_INIT) {
                                value.contains("uninitialized", ignoreCase = true) || value.contains("not initialized", ignoreCase = true)
                            } else {
                                value == "null" || value == "undefined"
                            }
                            updateIssueWithDebuggerResult(issue, isConfirmed)
                        }

                        override fun setPresentation(icon: javax.swing.Icon?, presentation: com.intellij.xdebugger.frame.presentation.XValuePresentation, hasChildren: Boolean) {
                            val sb = StringBuilder()
                            presentation.renderValue(object : com.intellij.xdebugger.frame.presentation.XValuePresentation.XValueTextRenderer {
                                override fun renderKeywordValue(text: String) { sb.append(text) }
                                override fun renderValue(text: String) { sb.append(text) }
                                override fun renderValue(text: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) { sb.append(text) }
                                override fun renderStringValue(text: String) { sb.append(text) }
                                override fun renderStringValue(text: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) { sb.append(text) }
                                override fun renderComment(text: String) { sb.append(text) }
                                override fun renderSpecialSymbol(text: String) { sb.append(text) }
                                override fun renderError(text: String) { sb.append(text) }
                                override fun renderNumericValue(text: String) { sb.append(text) }
                            })
                            val textValue = sb.toString()
                            val isConfirmed = if (issue.type == com.ghostdebugger.model.IssueType.STATE_BEFORE_INIT) {
                                textValue.contains("uninitialized", ignoreCase = true) || textValue.contains("not initialized", ignoreCase = true)
                            } else {
                                textValue == "null" || textValue == "undefined"
                            }
                            updateIssueWithDebuggerResult(issue, isConfirmed)
                        }

                        override fun setFullValueEvaluator(fullValueEvaluator: com.intellij.xdebugger.frame.XFullValueEvaluator) {}
                        override fun isObsolete(): Boolean = false
                    }, com.intellij.xdebugger.frame.XValuePlace.TOOLTIP)
                }

                override fun errorOccurred(errorMessage: String) {
                    log.debug("Evaluation failed for variable $varName: $errorMessage")
                }
            }, frame.sourcePosition)
        }
    }

    internal fun updateIssueWithDebuggerResult(issue: com.ghostdebugger.model.Issue, isConfirmed: Boolean) {
        scope.launch {
            val service = GhostDebuggerService.getInstance(project)
            val currentList = service.currentIssues
            val existingIndex = currentList.indexOfFirst { it.id == issue.id }
            if (existingIndex < 0) return@launch

            val existingIssue = currentList[existingIndex]

            val newSources = if (isConfirmed) {
                if (existingIssue.sources.contains(com.ghostdebugger.model.IssueSource.RUNTIME_CONFIRMED)) {
                    existingIssue.sources
                } else {
                    existingIssue.sources + com.ghostdebugger.model.IssueSource.RUNTIME_CONFIRMED
                }
            } else {
                existingIssue.sources - com.ghostdebugger.model.IssueSource.RUNTIME_CONFIRMED
            }

            val newConfidence = if (isConfirmed) 1.0 else 0.1

            val updatedIssue = existingIssue.copy(
                sources = newSources,
                confidence = newConfidence
            )

            val newList = currentList.toMutableList()
            newList[existingIndex] = updatedIssue

            service.updateIssues(newList)

            val inMemoryGraph = service.lastInMemoryGraph
            if (inMemoryGraph != null) {
                val nodeId = com.ghostdebugger.graph.GraphBuilder().normalizeId(updatedIssue.filePath)
                val node = inMemoryGraph.getNode(nodeId)
                if (node != null) {
                    val updatedNodeIssues = node.issues.map { if (it.id == issue.id) updatedIssue else it }
                    inMemoryGraph.updateNode(node.copy(issues = updatedNodeIssues))
                }
            }

            withContext(Dispatchers.Swing) {
                val bridge = service.bridgeChannel()
                bridge?.sendIssuesForFile(updatedIssue.filePath, service.issuesByFile[updatedIssue.filePath] ?: emptyList())

                val projectGraph = inMemoryGraph?.toProjectGraph(project.name)
                if (projectGraph != null) {
                    service.currentGraph = projectGraph
                    service.jcefBridge()?.sendGraphData(projectGraph)
                }
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
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
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
