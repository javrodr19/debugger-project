package com.ghostdebugger.store

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.EvidenceSource
import com.ghostdebugger.model.RuntimeEvidence
import com.intellij.coverage.CoverageDataManager
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.Disposable
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

        val activeIssues = service.currentIssues
        if (activeIssues.isEmpty()) return

        for (frame in frames) {
            val matchingIssues = activeIssues.filter { issue ->
                issue.line == frame.line && 
                issue.filePath.replace("\\", "/").endsWith(frame.fileName)
            }
            for (issue in matchingIssues) {
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
            val virtualFile = localFileSystem.findFileByPath(issue.filePath)
            val keys = mutableListOf<String>()
            if (virtualFile != null) {
                val psiFile = psiManager.findFile(virtualFile)
                if (psiFile is PsiClassOwner) {
                    psiFile.classes.forEach { psiClass ->
                        val qName = psiClass.qualifiedName
                        if (!qName.isNullOrBlank()) {
                            keys.add(qName)
                        }
                    }
                }
            }
            if (keys.isEmpty()) {
                keys.add(issue.filePath)
            }

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

            if (classFound) {
                val outcome = if (isCovered) EvidenceOutcome.LIKELY else EvidenceOutcome.UNREACHED
                val text = if (isCovered) "Covered in suite: ${bundle.presentableName}" else "Unreached in suite: ${bundle.presentableName}"
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
