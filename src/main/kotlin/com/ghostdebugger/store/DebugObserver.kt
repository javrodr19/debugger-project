package com.ghostdebugger.store

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzer
import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.EvidenceSource
import com.ghostdebugger.model.RuntimeEvidence
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.util.concurrent.CompletableFuture
import javax.swing.Icon

@Service(Service.Level.PROJECT)
class DebugObserver(private val project: Project) : Disposable {

    private val log = logger<DebugObserver>()

    init {
        project.messageBus.connect(this).subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: XDebugProcess) {
                    debugProcess.session.addSessionListener(SessionWatcher(debugProcess.session), this@DebugObserver)
                }
            }
        )
    }

    fun start() {
        log.info("DebugObserver initialized and active.")
    }

    private inner class SessionWatcher(private val session: XDebugSession) : XDebugSessionListener {
        override fun sessionPaused() {
            try {
                evaluateRelevantFindingsAtCurrentFrame()
            } catch (e: Exception) {
                if (e is ProcessCanceledException) throw e
                log.warn("Error during session paused evaluation", e)
            }
        }
    }

    private fun evaluateRelevantFindingsAtCurrentFrame() {
        val session = XDebuggerManager.getInstance(project).currentSession ?: return
        val frame = session.currentStackFrame ?: return
        val sourcePosition = frame.sourcePosition ?: return
        val evaluator = session.debugProcess.evaluator ?: return

        val filePath = sourcePosition.file.path.replace("\\", "/")
        val currentLine = sourcePosition.line + 1

        val service = GhostDebuggerService.getInstance(project)
        val activeIssues = service.currentIssues
        if (activeIssues.isEmpty()) return

        // Locate findings whose file matches and line matches current pause location
        val matchingIssues = activeIssues.filter { issue ->
            issue.filePath.replace("\\", "/") == filePath && issue.line == currentLine
        }

        val nullSafetyAnalyzer = NullSafetyAnalyzer()

        for (issue in matchingIssues) {
            val expression = if (issue.ruleId == "AEG-NULL-001") {
                nullSafetyAnalyzer.debugProbe(issue)
            } else {
                null
            } ?: continue

            try {
                evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
                    override fun evaluated(result: XValue) {
                        fetchValueText(result).thenAccept { valueText ->
                            val isNullish = valueText == "null" || valueText == "undefined"
                            val outcome = if (isNullish) EvidenceOutcome.CONFIRMED else EvidenceOutcome.DEMOTED
                            
                            val store = RuntimeEvidenceStore.getInstance(project)
                            store.record(
                                RuntimeEvidence(
                                    fingerprint = issue.fingerprint(),
                                    source = EvidenceSource.DEBUG_OBSERVATION,
                                    outcome = outcome,
                                    timestamp = System.currentTimeMillis(),
                                    context = "Observed '$expression' at frame: $valueText"
                                )
                            )
                        }
                    }

                    override fun errorOccurred(errorMessage: String) {
                        log.debug("Evaluation error for expression '$expression': $errorMessage")
                    }
                }, null)
            } catch (e: Exception) {
                if (e is ProcessCanceledException) throw e
                log.warn("Failed to evaluate probe expression '$expression'", e)
            }
        }
    }

    private fun fetchValueText(xValue: XValue): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        xValue.computePresentation(object : XValueNode {
            override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                future.complete(value)
            }

            override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                var text = ""
                presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                    override fun renderError(error: String) {
                        text = error
                    }
                    override fun renderValue(value: String) {
                        text = value
                    }
                    override fun renderValue(value: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) {
                        text = value
                    }
                    override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
                        text = value
                    }
                    override fun renderStringValue(value: String) {
                        text = value
                    }
                    override fun renderNumericValue(value: String) {
                        text = value
                    }
                    override fun renderKeywordValue(value: String) {
                        text = value
                    }
                    override fun renderComment(comment: String) {
                        text = comment
                    }
                    override fun renderSpecialSymbol(symbol: String) {
                        text = symbol
                    }
                })
                future.complete(text)
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
            override fun isObsolete(): Boolean = false
        }, com.intellij.xdebugger.frame.XValuePlace.TREE)
        return future
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): DebugObserver =
            project.getService(DebugObserver::class.java)
    }
}
