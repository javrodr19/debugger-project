package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.EarlyAnalyzer
import com.ghostdebugger.model.*
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * Harvests IDE-reported compilation errors by running the highlighting daemon per file.
 *
 * The daemon pass is expensive (0.3-2s per Kotlin file), so it runs [DAEMON_CONCURRENCY]-way
 * parallel and is bounded by [DaemonHarvestBudget]. Per-file progress is reported through
 * [progress]: without it a multi-minute pass renders as a frozen progress bar, which is
 * indistinguishable from a hang.
 */
class CompilationErrorAnalyzer(
    private val progress: ProgressIndicator? = null,
    private val settingsProvider: () -> GhostDebuggerSettings.State =
        { GhostDebuggerSettings.getInstance().snapshot() },
    // Test seam: when set, replaces the real per-file daemon harvest (harvestFile), which needs a
    // live IDE fixture and is impractical to drive from a latch-based concurrency test. Left null
    // in production and in every existing call site, so behaviour is unchanged: harvestAll always
    // falls back to the real harvestFile below.
    private val harvestOverride: ((ParsedFile, Project) -> List<Issue>)? = null,
) : EarlyAnalyzer {
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
        } catch (t: Throwable) {
            if (t is com.intellij.openapi.progress.ProcessCanceledException) throw t
            null
        }
    }

    private val semaphore = Semaphore(DAEMON_CONCURRENCY)

    override fun analyze(context: AnalysisContext): List<Issue> = runBlocking {
        val settings = settingsProvider()
        val budget = DaemonHarvestBudget(settings.daemonFileBudget, settings.daemonTimeBudgetMs)
        val files = budget.select(context.parsedFiles)

        if (files.size < context.parsedFiles.size) {
            log.info(
                "Compile-error harvest limited to ${files.size} of ${context.parsedFiles.size} " +
                    "files by daemonFileBudget=${settings.daemonFileBudget}"
            )
        }
        harvestAll(files, budget, context.project)
    }

    /**
     * Fans the per-file daemon pass out across [DAEMON_CONCURRENCY] workers.
     *
     * Note the shape: `map { async { … } }.awaitAll()` genuinely runs in parallel, whereas
     * `flatMap { withContext(…) { … } }` awaits each element before starting the next and is
     * therefore fully sequential. That mistake is what made analysis appear to hang.
     */
    private suspend fun harvestAll(
        files: List<ParsedFile>,
        budget: DaemonHarvestBudget,
        project: Project,
    ): List<Issue> = coroutineScope {
        val deadline = budget.startDeadline()
        val total = files.size
        val done = AtomicInteger(0)
        val truncated = AtomicBoolean(false)

        val issues = files.map { file ->
            async(Dispatchers.Default) {
                semaphore.withPermit {
                    ProgressManager.checkCanceled()
                    if (budget.expired(deadline)) {
                        truncated.set(true)
                        emptyList()
                    } else {
                        harvest(file, project).also {
                            val n = done.incrementAndGet()
                            progress?.text2 = "Compile check: $n/$total — ${file.virtualFile.name}"
                        }
                    }
                }
            }
        }.awaitAll().flatten()

        if (truncated.get()) {
            val checked = done.get()
            // Surfaced rather than silent: the user should know a run was capped, otherwise a
            // partial harvest looks identical to a clean bill of health.
            log.info("Compile-error harvest hit its time budget after $checked/$total files")
            progress?.text2 = "Compile check: time budget reached after $checked/$total files"
        }
        issues
    }

    /** Single dispatch point for the per-file harvest: [harvestOverride] in tests, [harvestFile] in production. */
    private fun harvest(parsedFile: ParsedFile, project: Project): List<Issue> =
        harvestOverride?.invoke(parsedFile, project) ?: harvestFile(parsedFile, project)

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
            val cause = e.cause
            if (cause is com.intellij.openapi.progress.ProcessCanceledException) throw cause
            log.warn("daemon harvest failed for ${parsedFile.path}: ${(cause ?: e).message}")
            emptyList()
        } catch (t: Throwable) {
            if (t is com.intellij.openapi.progress.ProcessCanceledException) throw t
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
