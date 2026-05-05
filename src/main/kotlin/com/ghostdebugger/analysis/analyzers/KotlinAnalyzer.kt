package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ParsedFile
import com.ghostdebugger.parser.withKtAnalysis
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.psi.KtFile

/**
 * Base class for Aegis Kotlin analyzers.
 *
 * - Filters `context.parsedFiles` to `.kt` files.
 * - Resolves each file to a project-bound `KtFile` via `PsiManager`.
 * - Wraps the analysis in a read action and a single [withKtAnalysis] block.
 * - Catches non-PCE failures per file and continues with the next file;
 *   re-raises [ProcessCanceledException] per the V1.2 hardening rule.
 */
abstract class KotlinAnalyzer : Analyzer {

    private val log = logger<KotlinAnalyzer>()

    final override fun analyze(context: AnalysisContext): List<Issue> {
        val out = mutableListOf<Issue>()
        for (file in context.parsedFiles) {
            if (file.extension != "kt") continue
            try {
                out.addAll(analyzeFileSafely(file, context))
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.warn("$name failed for ${file.path}", e)
            }
        }
        return out
    }

    private fun analyzeFileSafely(file: ParsedFile, context: AnalysisContext): List<Issue> {
        val ktFile = ApplicationManager.getApplication().runReadAction<KtFile?> {
            PsiManager.getInstance(context.project).findFile(file.virtualFile) as? KtFile
        }
        if (ktFile == null) {
            log.info("Kotlin PSI unavailable for ${file.path}; skipping $name")
            return emptyList()
        }
        return ApplicationManager.getApplication().runReadAction<List<Issue>> {
            withKtAnalysis(ktFile) { analyzeKtFile(it, file, context, this) } ?: emptyList()
        }
    }

    /**
     * Analyze a single Kotlin file. Called inside a read action with an open Analysis API session.
     *
     * @param ktFile the parsed Kotlin file (project-bound)
     * @param parsedFile the V1.x [ParsedFile] wrapper carrying path, content, and pre-extracted symbols
     * @param context the full analysis context (other files, project graph, etc.)
     * @param session the Kotlin Analysis API session — call its members via `this`
     */
    abstract fun analyzeKtFile(
        ktFile: KtFile,
        parsedFile: ParsedFile,
        context: AnalysisContext,
        session: KaSession
    ): List<Issue>
}
