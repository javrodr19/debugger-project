package com.ghostdebugger.analysis

import com.ghostdebugger.model.*
import com.intellij.openapi.project.Project

class PartialReanalyzer(
    private val engineFactory: () -> AnalysisEngine = { AnalysisEngine() }
) {
    /**
     * Re-run only the static pass for [filePath] against the existing [previous]
     * issues. Returns a new list with stale issues from [filePath] removed and
     * freshly produced ones merged in.
     */
    suspend fun reanalyzeFile(
        project: Project,
        filePath: String,
        previous: List<Issue>,
        fullGraphContext: AnalysisContext
    ): List<Issue> {
        val singleFileContext = fullGraphContext.copy(
            parsedFiles = fullGraphContext.parsedFiles.filter { it.path == filePath }
        )
        val freshResult = engineFactory().analyze(singleFileContext)
        return previous.filterNot { it.filePath == filePath } + freshResult.issues
    }
}
