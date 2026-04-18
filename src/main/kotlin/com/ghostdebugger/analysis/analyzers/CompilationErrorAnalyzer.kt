package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.EarlyAnalyzer
import com.ghostdebugger.model.*
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ProperTextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.function.Consumer

class CompilationErrorAnalyzer : EarlyAnalyzer {
    override val name = "CompilationErrorAnalyzer"
    override val ruleId = "AEG-COMPILE-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description = "Surfaces IDE-reported compilation errors (unresolved references, type mismatches, invalid declarations) harvested from the IntelliJ analysis daemon."

    private val log = Logger.getInstance(CompilationErrorAnalyzer::class.java)

    companion object {
        private const val DAEMON_CONCURRENCY = 4

        // HighlightingSessionImpl.runInsideHighlightingSession is @ApiStatus.Internal and its
        // signature gained a CodeInsightContext parameter in IDEA 2025.1 (the "multiverse" feature).
        // To stay compatible across the declared 232..261 range we resolve the static method
        // reflectively once and cache whichever signature the running IDE exposes.
        private const val HSI_CLASS = "com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl"
        private const val CIC_KT_CLASS = "com.intellij.codeInsight.multiverse.CodeInsightContextKt"
        private const val RUN_INSIDE = "runInsideHighlightingSession"

        private data class RunInsideDispatch(val method: Method, val codeInsightContext: Any?)

        private val dispatch: RunInsideDispatch? by lazy { resolveDispatch() }

        private fun resolveDispatch(): RunInsideDispatch? = try {
            val candidates = Class.forName(HSI_CLASS).declaredMethods.filter {
                it.name == RUN_INSIDE && Modifier.isStatic(it.modifiers)
            }
            val sixArg = candidates.firstOrNull { it.parameterCount == 6 }
            when {
                sixArg != null -> {
                    sixArg.isAccessible = true
                    val anyContext = Class.forName(CIC_KT_CLASS).getMethod("anyContext").invoke(null)
                    RunInsideDispatch(sixArg, anyContext)
                }
                else -> candidates.firstOrNull { it.parameterCount == 5 }
                    ?.also { it.isAccessible = true }
                    ?.let { RunInsideDispatch(it, null) }
            }
        } catch (_: Throwable) {
            null
        }
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

    // runMainPasses requires both (1) a DaemonProgressIndicator installed as the thread's current
    // progress via ProgressManager.runProcess, and (2) a HighlightingSession wrapping the call.
    // Without both, it throws and the outer catch silently returned empty — making the analyzer
    // invisibly no-op in production. HighlightingSessionImpl's signature varies across IDE
    // versions, so we route through the cached reflective `dispatch` above.
    @Suppress("UnstableApiUsage")
    private fun harvestFile(parsedFile: ParsedFile, project: Project): List<Issue> {
        val dispatch = dispatch ?: return emptyList()
        val virtualFile = parsedFile.virtualFile
        return try {
            runReadAction {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    ?: return@runReadAction emptyList()
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    ?: return@runReadAction emptyList()

                val indicator = DaemonProgressIndicator()
                var highlights: List<HighlightInfo> = emptyList()

                ProgressManager.getInstance().runProcess({
                    val consumer = Consumer<Any?> {
                        highlights = DaemonCodeAnalyzerImpl.getInstanceEx(project)
                            .runMainPasses(psiFile, document, indicator)
                    }
                    val range = ProperTextRange.create(0, document.textLength)
                    invokeRunInsideHighlightingSession(dispatch, psiFile, range, consumer)
                }, indicator)

                highlights
                    .filter { it.severity == HighlightSeverity.ERROR }
                    .map { buildIssue(it, document, parsedFile) }
            }
        } catch (e: InvocationTargetException) {
            log.warn("daemon harvest failed for ${parsedFile.path}: ${(e.cause ?: e).message}")
            emptyList()
        } catch (t: Throwable) {
            log.warn("daemon harvest failed for ${parsedFile.path}: ${t.message}")
            emptyList()
        }
    }

    private fun invokeRunInsideHighlightingSession(
        dispatch: RunInsideDispatch,
        psiFile: PsiFile,
        range: ProperTextRange,
        consumer: Consumer<*>,
    ) {
        if (dispatch.method.parameterCount == 6) {
            // 2025.1+ : (PsiFile, CodeInsightContext, EditorColorsScheme, ProperTextRange, boolean, Consumer)
            dispatch.method.invoke(null, psiFile, dispatch.codeInsightContext, null, range, false, consumer)
        } else {
            // 2024.3.x and earlier: (PsiFile, EditorColorsScheme, ProperTextRange, boolean, Consumer)
            dispatch.method.invoke(null, psiFile, null, range, false, consumer)
        }
    }

    private fun buildIssue(highlight: HighlightInfo, document: Document, parsedFile: ParsedFile): Issue {
        val line = document.getLineNumber(highlight.startOffset) + 1
        val column = highlight.startOffset - document.getLineStartOffset(line - 1) + 1

        val title = "Compilation error: ${highlight.description?.take(120) ?: "unspecified"}"
        val description = highlight.toolTip?.let(::stripHtml) ?: highlight.description ?: "IDE reported an error at this location."

        val lines = parsedFile.lines
        val snippet = lines.subList(
            (line - 3).coerceIn(0, lines.size),
            (line + 2).coerceAtMost(lines.size),
        ).joinToString("\n")

        return Issue(
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
            confidence = 1.0,
        )
    }

    private fun stripHtml(s: String): String = s.replace(Regex("<[^>]+>"), "").trim()
}
