package com.ghostdebugger.store

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.EvidenceSource
import com.ghostdebugger.model.RuntimeEvidence
import com.intellij.coverage.CoverageDataManager
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager

@Service(Service.Level.PROJECT)
class TestRunObserver(private val project: Project) : Disposable {

    private val log = logger<TestRunObserver>()

    init {
        project.messageBus.connect(this).subscribe(
            SMTRunnerEventsListener.TEST_STATUS,
            object : com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter() {
                override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}

                override fun onTestFailed(test: SMTestProxy) {
                    try {
                        recordFromFailure(test)
                    } catch (e: Exception) {
                        if (e is ProcessCanceledException) throw e
                        log.warn("Error processing test failure event", e)
                    }
                }

                override fun onTestFinished(test: SMTestProxy) {
                    // Option to record successful outcome
                }

                override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                    try {
                        harvestCoverage()
                    } catch (e: Exception) {
                        if (e is ProcessCanceledException) throw e
                        log.warn("Error harvesting test coverage", e)
                    }
                }
            }
        )
    }

    fun start() {
        log.info("TestRunObserver initialized and active.")
    }

    private fun getContextName(test: SMTestProxy): String {
        val path = mutableListOf<String>()
        var current: SMTestProxy? = test
        while (current != null && current.name != "ROOT" && current.name.isNotBlank()) {
            path.add(0, current.name)
            current = current.parent
        }
        return path.joinToString(".")
    }

    private fun recordFromFailure(test: SMTestProxy) {
        val stacktrace = test.stacktrace ?: return
        val frames = StackTraceParser.parse(stacktrace)
        if (frames.isEmpty()) return

        val service = GhostDebuggerService.getInstance(project)
        val store = RuntimeEvidenceStore.getInstance(project)
        val context = getContextName(test)

        for (issue in TestRunCorrelation.failureMatches(frames, service.currentIssues)) {
            store.record(
                RuntimeEvidence(
                    fingerprint = issue.fingerprint(),
                    source = EvidenceSource.TEST_FAILURE,
                    outcome = EvidenceOutcome.CONFIRMED,
                    timestamp = System.currentTimeMillis(),
                    context = "Failed in test: $context"
                )
            )
        }
    }

    private fun harvestCoverage() {
        val coverageManager = CoverageDataManager.getInstance(project) ?: return
        val bundle = coverageManager.currentSuitesBundle ?: return
        val projectData = bundle.coverageData ?: return

        val service = GhostDebuggerService.getInstance(project)
        val store = RuntimeEvidenceStore.getInstance(project)
        val activeIssues = service.currentIssues
        if (activeIssues.isEmpty()) return

        val psiManager = PsiManager.getInstance(project)
        val localFileSystem = LocalFileSystem.getInstance()

        for (issue in activeIssues) {
            // PSI access (findFile + psiFile.classes + qualifiedName) requires a read action.
            // onTestingFinished() runs on a background thread without one, so this threw
            // ReadAccessException and silently disabled coverage harvesting entirely. See BUG-19.
            val keys = ApplicationManager.getApplication().runReadAction<List<String>> {
                val virtualFile = localFileSystem.findFileByPath(issue.filePath)
                val psiFile = virtualFile?.let { psiManager.findFile(it) }
                if (psiFile is PsiClassOwner) {
                    psiFile.classes.mapNotNull { it.qualifiedName?.takeIf(String::isNotBlank) }
                } else {
                    emptyList()
                }
            }.ifEmpty { listOf(issue.filePath) }

            var isCovered = false
            var classFound = false

            for (key in keys) {
                val classData = projectData.getClassData(key)
                if (classData != null) {
                    classFound = true
                    val lineData = classData.getLineData(issue.line)
                    if (lineData != null && lineData.hits > 0) {
                        isCovered = true
                        break
                    }
                }
            }

            val outcome = TestRunCorrelation.coverageEvidence(classFound, isCovered)
            if (outcome != null) {
                val text = if (isCovered) "Covered in suite: ${bundle.presentableName}"
                           else "Unreached in suite: ${bundle.presentableName}"
                store.record(
                    RuntimeEvidence(
                        fingerprint = issue.fingerprint(),
                        source = EvidenceSource.TEST_COVERAGE,
                        outcome = outcome,
                        timestamp = System.currentTimeMillis(),
                        context = text
                    )
                )
            }
        }
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): TestRunObserver =
            project.getService(TestRunObserver::class.java)
    }
}
