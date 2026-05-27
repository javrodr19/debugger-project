package com.ghostdebugger

import com.ghostdebugger.model.Issue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.problems.Problem
import com.intellij.problems.WolfTheProblemSolver

@Service(Service.Level.PROJECT)
internal class ProblemsViewCoordinator(private val project: Project) : Disposable {
    private val log = logger<ProblemsViewCoordinator>()
    private val service get() = GhostDebuggerService.getInstance(project)
    private val listener: (List<Issue>) -> Unit = { issues -> publishToProblemsView(issues) }
    @Volatile private var lastPublishedByFile: Map<String, Set<String>> = emptyMap()

    init {
        Disposer.register(project, this)
    }

    fun start() {
        service.onIssuesUpdated(listener)
    }

    override fun dispose() {
        service.removeIssuesListener(listener)
    }

    private fun publishToProblemsView(issues: List<Issue>) {
        val app = ApplicationManager.getApplication() ?: return
        
        try {
            val currentIssuesByFile = issues.groupBy { it.filePath.replace("\\", "/") }
            val allFiles = (lastPublishedByFile.keys + currentIssuesByFile.keys).toSet()
            val newPublishedByFile = mutableMapOf<String, Set<String>>()

            for (filePath in allFiles) {
                val fileIssues = currentIssuesByFile[filePath] ?: emptyList()
                val newIssueIds = fileIssues.map { it.id }.toSet()
                val oldIssueIds = lastPublishedByFile[filePath] ?: emptySet()

                if (newIssueIds != oldIssueIds) {
                    val virtualFile = runCatching { LocalFileSystem.getInstance().findFileByPath(filePath) }.getOrNull()
                        ?: com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(filePath)
                        ?: com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("temp://$filePath")
                        ?: com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("temp:/$filePath")
                        ?: project.basePath?.let { basePath ->
                            val normalizedFile = filePath.removePrefix("/")
                            val normalizedBase = basePath.replace("\\", "/").removeSuffix("/")
                            runCatching { LocalFileSystem.getInstance().findFileByPath("$normalizedBase/$normalizedFile") }.getOrNull()
                        }
                    println("ProblemsViewCoordinator: resolved file = $virtualFile for path = $filePath")
                    if (virtualFile != null) {
                        app.invokeLater {
                            val wolf = WolfTheProblemSolver.getInstance(project)
                            if (fileIssues.isEmpty()) {
                                println("ProblemsViewCoordinator: clearing problems for $virtualFile")
                                wolf.clearProblems(virtualFile)
                            } else {
                                val problems = fileIssues.mapNotNull { issue ->
                                    wolf.convertToProblem(
                                        virtualFile,
                                        issue.line.coerceAtLeast(1),
                                        (issue.column ?: 1).coerceAtLeast(1),
                                        arrayOf(issue.title + ": " + issue.description)
                                    )
                                }
                                println("ProblemsViewCoordinator: reporting ${problems.size} problems for $virtualFile")
                                wolf.reportProblems(virtualFile, problems)
                            }
                        }
                    }
                }
                if (newIssueIds.isNotEmpty()) {
                    newPublishedByFile[filePath] = newIssueIds
                }
            }
            lastPublishedByFile = newPublishedByFile
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            log.warn("Error publishing to Problems tool window", e)
        }
    }

    companion object {
        fun getInstance(project: Project): ProblemsViewCoordinator =
            project.getService(ProblemsViewCoordinator::class.java)
    }
}
