package com.ghostdebugger.fix.engine

import com.ghostdebugger.analysis.AnalysisEngine
import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ParsedFile
import com.ghostdebugger.parser.FileScanner
import com.ghostdebugger.parser.SymbolExtractor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Default Tier-2 [reanalyze] provider: re-parse [virtualFile] from its **live (committed)** document
 * and run the non-shadowing static-only analysis pass (late analyzers execute even on files with compile
 * errors), returning the issues for that file. Mirrors `AnalysisOrchestrator.reanalyzeFile`'s reparse
 * idiom but scoped to a single file with an empty graph (single-file gate; cross-file graph rules are
 * out of scope for a deterministic fix). The non-shadowing pass ensures the no-regression gate sees
 * late-rule issues (e.g. null-safety) on files that also have compile errors.
 *
 * **Must be called off the EDT** — the Kotlin Analysis API throws from the EDT.
 */
class SingleFileStaticReanalysis(
    private val project: Project,
    private val engineFactory: () -> AnalysisEngine = { AnalysisEngine() },
) {
    suspend fun issuesFor(virtualFile: VirtualFile): List<Issue> {
        val parsed = ApplicationManager.getApplication().runReadAction<ParsedFile?> {
            FileScanner(project).parsedFiles(listOf(virtualFile)).firstOrNull()
        } ?: return emptyList()
        val extracted = SymbolExtractor(project).extract(parsed)
        val ctx = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(extracted),
        )
        val targetPath = virtualFile.path.replace("\\", "/")
        return engineFactory().analyzeStaticOnly(ctx, excludeBrokenFromLate = false).issues
            .filter { it.filePath.replace("\\", "/") == targetPath }
    }
}
