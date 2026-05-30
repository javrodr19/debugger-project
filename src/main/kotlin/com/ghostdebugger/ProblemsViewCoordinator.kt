package com.ghostdebugger

import com.ghostdebugger.model.Issue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.problems.WolfTheProblemSolver

@Service(Service.Level.PROJECT)
internal class ProblemsViewCoordinator(private val project: Project) : Disposable {
    private val log = logger<ProblemsViewCoordinator>()
    private val service get() = GhostDebuggerService.getInstance(project)
    private val listener: (List<Issue>) -> Unit = { issues -> publishToProblemsView(issues) }
    
    @Volatile
    private var lastPublishedByFile: Map<String, Set<String>> = emptyMap()

    init {
        Disposer.register(project, this)
    }

    fun start() {
        service.onIssuesUpdated(listener)
        // Publish initial issues if any are already present
        publishToProblemsView(service.currentIssues)
    }

    override fun dispose() {
        service.removeIssuesListener(listener)
    }

    private fun publishToProblemsView(issues: List<Issue>) {
        val wolf = WolfTheProblemSolver.getInstance(project)
        val fileSystem = LocalFileSystem.getInstance()
        
        // Group new issues by filePath
        val newIssuesByFile = issues.groupBy { it.filePath.replace("\\", "/") }
        
        // Find all files that are affected (either in the new list or the old list)
        val allFilePaths = newIssuesByFile.keys + lastPublishedByFile.keys
        val nextPublishedByFile = mutableMapOf<String, Set<String>>()

        for (filePath in allFilePaths) {
            val newFileIssues = newIssuesByFile[filePath] ?: emptyList()
            val newIssueIds = newFileIssues.map { it.id }.toSet()
            val oldIssueIds = lastPublishedByFile[filePath] ?: emptySet()

            // If the set of issue IDs for this file didn't change, skip it
            if (newIssueIds == oldIssueIds) {
                if (newIssueIds.isNotEmpty()) {
                    nextPublishedByFile[filePath] = newIssueIds
                }
                continue
            }

            var virtualFile = fileSystem.findFileByPath(filePath)
            if (virtualFile == null) {
                virtualFile = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(filePath)
            }
            if (virtualFile == null) {
                virtualFile = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("file://$filePath")
            }
            if (virtualFile == null) {
                log.warn("Could not find VirtualFile for path: $filePath")
                continue
            }

            try {
                if (newFileIssues.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            wolf.clearProblems(virtualFile)
                        }
                    }
                } else {
                    nextPublishedByFile[filePath] = newIssueIds
                    val problems = newFileIssues.map { issue ->
                        wolf.convertToProblem(
                            virtualFile,
                            issue.line,
                            0, // column (0 is fine)
                            arrayOf(issue.title)
                        )
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            wolf.reportProblems(virtualFile, problems)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is ProcessCanceledException) throw e
                log.warn("Failed to publish problems to Wolf for file: $filePath", e)
            }
        }
        
        lastPublishedByFile = nextPublishedByFile
    }

    companion object {
        fun getInstance(project: Project): ProblemsViewCoordinator =
            project.getService(ProblemsViewCoordinator::class.java)
    }
}
