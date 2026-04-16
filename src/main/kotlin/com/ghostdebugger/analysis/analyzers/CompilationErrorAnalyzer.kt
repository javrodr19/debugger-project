package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.EarlyAnalyzer
import com.ghostdebugger.model.*
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

class CompilationErrorAnalyzer : EarlyAnalyzer {
    override val name = "CompilationErrorAnalyzer"
    override val ruleId = "AEG-COMPILE-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description = "Surfaces IDE-reported compilation errors (unresolved references, type mismatches, invalid declarations) harvested from the IntelliJ analysis daemon."

    private val log = Logger.getInstance(CompilationErrorAnalyzer::class.java)

    companion object {
        private const val DAEMON_CONCURRENCY = 4
    }

    private val semaphore = Semaphore(DAEMON_CONCURRENCY)

    override fun analyze(context: AnalysisContext): List<Issue> = runBlocking {
        coroutineScope {
            context.parsedFiles.map { file ->
                async(Dispatchers.Default) {
                    semaphore.withPermit {
                        ProgressManager.checkCanceled()
                        harvestFile(file, context.project)
                    }
                }
            }.awaitAll().flatten()
        }
    }

    @Suppress("UnstableApiUsage")
    private fun harvestFile(parsedFile: ParsedFile, project: Project): List<Issue> {
        val virtualFile = parsedFile.virtualFile
        return try {
            ApplicationManager.getApplication().runReadAction<List<Issue>> {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@runReadAction emptyList()
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@runReadAction emptyList()

                val highlights = DaemonCodeAnalyzerImpl
                    .getInstanceEx(project)
                    .runMainPasses(psiFile, document, EmptyProgressIndicator())

                highlights.filter { it.severity == HighlightSeverity.ERROR }.map { highlight ->
                    val line = document.getLineNumber(highlight.startOffset) + 1
                    val column = highlight.startOffset - document.getLineStartOffset(line - 1) + 1
                    
                    val title = "Compilation error: ${highlight.description?.take(120) ?: "unspecified"}"
                    val description = highlight.toolTip?.let(::stripHtml) ?: highlight.description ?: "IDE reported an error at this location."
                    
                    val lines = parsedFile.lines
                    val snippet = lines.subList(
                        (line - 3).coerceIn(0, lines.size),
                        (line + 2).coerceAtMost(lines.size)
                    ).joinToString("\n")

                    Issue(
                        id = UUID.randomUUID().toString(),
                        type = IssueType.COMPILATION_ERROR,
                        severity = defaultSeverity,
                        title = title,
                        description = description,
                        filePath = parsedFile.path,
                        line = line,
                        column = column,
                        codeSnippet = snippet,
                        affectedNodes = listOf(parsedFile.path),
                        ruleId = ruleId,
                        sources = listOf(IssueSource.STATIC),
                        providers = listOf(EngineProvider.STATIC),
                        confidence = 1.0
                    )
                }
            }
        } catch (t: Throwable) {
            log.warn("daemon harvest failed for ${parsedFile.path}: ${t.message}")
            emptyList()
        }
    }

    private fun stripHtml(s: String): String = s.replace(Regex("<[^>]+>"), "").trim()
}
