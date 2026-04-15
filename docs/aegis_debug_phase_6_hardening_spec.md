# 🛡️ Aegis Debug — Phase 6: Hardening & V1 GA Spec

**Version:** V1 — Phase 6 Canonical Build Spec
**Status:** Ready for implementation
**Depends on:** Phases 1–5 merged, `aegis_debug_v1_gap_analysis.md`
**Target:** V1 General Availability
**Previous phases:** Foundations · Core Engine · Fix Pipeline · UI Refresh · AI Augmentation

---

## 0. Executive Summary

Phase 6 closes the V1 release. It implements the four P0 release-blockers identified in the gap analysis — **cancellation & progress**, **PSI/syntax safety on fix apply**, **Phase 5 service-migration completion**, and **fixture-based integration tests** — followed by the P1 GA-quality items (**cache toggle wiring**, **cloud-vs-local provenance split**, **targeted post-fix re-analysis**, **fallback message polish**) and the P2 release-asset pass (**marketplace copy**, **logo pass**, **`DATA_HANDLING.md`**, **`CHANGELOG.md`**, **`plugin.xml` pinning**).

**Design principles for this phase:**
1. **No new user-visible features.** Every change is correctness, safety, UX clarity, or release readiness.
2. **Platform compliance first.** IntelliJ requires cancellation + progress for long work; Phase 6 makes the plugin a good IDE citizen.
3. **Trust is structural, not nominal.** "Engine Verified" must be gated on PSI validity, not just a boolean flag.
4. **No silent behavior change.** Phase 5 spec drift (Ollama never consulted for explain/fix/system) is resolved explicitly, with tests to prove it.

On completion, V1 §18 acceptance criteria move from 14 ✅ / 6 🟡 / 2 ❌ to **22 ✅**.

---

## 1. Goals & Scope

### Goals
- Plugin supports cancellation and shows progress for all long-running analysis.
- `FixApplicator` never commits a fix that produces PSI errors.
- `GhostDebuggerService` and `AIAnalyzer` route every AI call through `AIServiceFactory`, so Ollama users get parity with OpenAI users.
- User-facing text is 100% English.
- `cacheEnabled` setting is honored.
- The UI visually distinguishes `AI_LOCAL` from `AI_CLOUD` issues.
- Integration tests run the full pipeline against fixture repos.
- Marketplace-ready plugin metadata, data-handling doc, and changelog.

### Non-Goals
- New analyzers, new fixers, new languages.
- UI redesign beyond the provenance-badge split.
- Telemetry or analytics.
- Remote config or auto-update.
- Multi-file "joint fix" flow (remains post-V1).

---

## 2. File Inventory

### 2.1 Modified Kotlin files
- `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` — add `ProgressIndicator` wiring, replace `openAIService` with `aiService`, add `resolveAiService()`, migrate 4 call sites, translate Spanish strings, targeted re-analysis.
- `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt` — accept optional `ProgressIndicator`, call `checkCanceled()` between analyzer passes and per-file in AI passes, polish fallback messages.
- `src/main/kotlin/com/ghostdebugger/analysis/analyzers/AIAnalyzer.kt` — accept an `AIService` via constructor instead of hardcoding `OpenAIService`.
- `src/main/kotlin/com/ghostdebugger/fix/FixApplicator.kt` — add post-commit PSI validity check with rollback.
- `src/main/kotlin/com/ghostdebugger/ai/OpenAIService.kt` — accept `cacheEnabled: Boolean` constructor param; bypass cache when false.
- `src/main/kotlin/com/ghostdebugger/ai/OllamaService.kt` — same treatment.
- `src/main/kotlin/com/ghostdebugger/ai/AIServiceFactory.kt` — pass `cacheEnabled` from settings to each service.
- `src/main/resources/META-INF/plugin.xml` — marketplace description, `since-build`/`until-build`, version bump.

### 2.2 New Kotlin files
- `src/main/kotlin/com/ghostdebugger/analysis/PartialReanalyzer.kt` — runs the static pass on a single file and merges into an existing issue set.
- `src/main/kotlin/com/ghostdebugger/observability/ErrorCategory.kt` — sealed class for V1 §16 categories (deferred to P3 in gap analysis but cheap to land here; see §12).

### 2.3 New test files
- `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineCancellationTest.kt`
- `src/test/kotlin/com/ghostdebugger/fix/FixApplicatorValidityTest.kt`
- `src/test/kotlin/com/ghostdebugger/integration/FullPipelineIntegrationTest.kt`
- `src/test/kotlin/com/ghostdebugger/integration/OllamaUserJourneyTest.kt`
- `src/test/kotlin/com/ghostdebugger/ai/OpenAIServiceCacheToggleTest.kt`
- `src/test/kotlin/com/ghostdebugger/analysis/PartialReanalyzerTest.kt`

### 2.4 New test resources
- `src/test/resources/fixtures/small/` — 5–10 Kotlin/Java files with known issues.
- `src/test/resources/fixtures/medium/` — ~50 files.
- `src/test/resources/fixtures/large/` — ~200 files (can be synthesized).
- `src/test/resources/fixtures/expected/*.json` — golden issue sets per fixture.

### 2.5 Modified webview files
- `webview/src/components/detail-panel/DetailPanel.tsx` — `ProvenanceBadge` splits `AI_LOCAL` vs `AI_CLOUD`.
- `webview/src/components/layout/StatusBar.tsx` — add cancel button wired to `cancelAnalysis()` bridge call; progress text when analysis is running.
- `webview/src/bridge/pluginBridge.ts` — expose `cancelAnalysis()`.
- `webview/src/stores/appStore.ts` — add `analysisProgress: { text: string; fraction: number } | null` and `SET_ANALYSIS_PROGRESS` action.
- `webview/src/types/index.ts` — `AnalysisProgressPayload` type.

### 2.6 New root docs
- `DATA_HANDLING.md` — V1 §14 data-handling disclosure.
- `CHANGELOG.md` — V1 GA changelog.

### 2.7 Removed assets
- `src/main/resources/icons/ghost.svg` — legacy, unused.

---

## 3. P0 — Cancellation & Progress

**V1 clauses closed:** §11 "long scans must support cancellation", §11 "scan progress must be visible", §15 "user can cancel analysis safely", §18.5 "Long-running scans can be canceled".

### 3.1 `GhostDebuggerService.analyzeProject` — wrap in `Task.Backgroundable`

The current implementation at `GhostDebuggerService.kt:264` launches on a coroutine scope with no platform progress handle. Replace with a `Task.Backgroundable` that owns the `ProgressIndicator` and threads it into the coroutine.

```kotlin
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import kotlinx.coroutines.runBlocking

private val analysisLock = Object()
@Volatile private var activeAnalysisIndicator: ProgressIndicator? = null

fun analyzeProject() {
    // Guard against overlapping scans (auto-refresh + user click).
    synchronized(analysisLock) {
        activeAnalysisIndicator?.cancel()
    }

    val task = object : Task.Backgroundable(
        project,
        "Aegis Debug: analyzing project",
        /* canBeCancelled = */ true
    ) {
        override fun run(indicator: ProgressIndicator) {
            synchronized(analysisLock) { activeAnalysisIndicator = indicator }
            indicator.isIndeterminate = false
            indicator.fraction = 0.0

            try {
                runBlocking { analyzeWithProgress(indicator) }
            } catch (e: ProcessCanceledException) {
                log.info("Analysis cancelled by user")
                scope.launch(Dispatchers.Swing) {
                    bridge?.sendError("Analysis cancelled.")
                }
            } catch (t: Throwable) {
                log.error("Analysis failed", t)
                scope.launch(Dispatchers.Swing) {
                    bridge?.sendError("Analysis failed: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                synchronized(analysisLock) {
                    if (activeAnalysisIndicator === indicator) activeAnalysisIndicator = null
                }
            }
        }
    }

    ProgressManager.getInstance().run(task)
}

fun cancelAnalysis() {
    synchronized(analysisLock) {
        activeAnalysisIndicator?.cancel()
    }
}

private suspend fun analyzeWithProgress(indicator: ProgressIndicator) {
    withContext(Dispatchers.Swing) { bridge?.sendAnalysisStart() }

    indicator.text = "Scanning files…"
    indicator.checkCanceled()
    val virtualFiles = ApplicationManager.getApplication().runReadAction<List<VirtualFile>> {
        FileScanner(project).scanFiles()
    }
    indicator.fraction = 0.10

    indicator.text = "Parsing files…"
    indicator.checkCanceled()
    val rawFiles = ApplicationManager.getApplication().runReadAction<List<ParsedFile>> {
        FileScanner(project).parsedFiles(virtualFiles)
    }
    indicator.fraction = 0.25

    indicator.text = "Extracting symbols…"
    indicator.checkCanceled()
    val parsedFiles = rawFiles.map { SymbolExtractor().extract(it) }
    indicator.fraction = 0.40

    indicator.text = "Resolving dependencies…"
    indicator.checkCanceled()
    val dependencies = DependencyResolver(project.basePath ?: "").resolve(parsedFiles)
    indicator.fraction = 0.50

    indicator.text = "Building graph…"
    indicator.checkCanceled()
    val graphBuilder = GraphBuilder()
    val inMemoryGraph = graphBuilder.build(parsedFiles, dependencies)
    indicator.fraction = 0.60

    indicator.text = "Running analyzers…"
    indicator.checkCanceled()
    val analysisContext = AnalysisContext(
        graph = inMemoryGraph,
        project = project,
        parsedFiles = parsedFiles
    )
    val analysisResult = AnalysisEngine(progress = indicator).analyze(analysisContext)
    currentIssues = analysisResult.issues
    indicator.fraction = 0.90

    indicator.text = "Publishing results…"
    indicator.checkCanceled()
    graphBuilder.applyIssues(inMemoryGraph, analysisResult.issues)
    val projectGraph = inMemoryGraph.toProjectGraph(project.name)
    currentGraph = projectGraph

    withContext(Dispatchers.Swing) {
        bridge?.sendGraphData(projectGraph)
        bridge?.sendAnalysisComplete(
            analysisResult.metrics.errorCount,
            analysisResult.metrics.warningCount,
            analysisResult.metrics.healthScore
        )
        bridge?.sendEngineStatus(analysisResult.engineStatus)
    }
    indicator.fraction = 1.0

    ApplicationManager.getApplication().invokeLater {
        runCatching { DaemonCodeAnalyzer.getInstance(project).restart() }
            .onFailure { log.warn("Could not restart DaemonCodeAnalyzer: ${it.message}") }
    }

    // Pre-fetch critical issue explanations (off the progress path).
    prefetchCriticalExplanations(analysisResult)
}
```

### 3.2 `AnalysisEngine` accepts an optional `ProgressIndicator`

Current signature: `class AnalysisEngine(private val settingsProvider..., private val apiKeyProvider..., private val aiPassRunner...)`.

Add an optional `progress: ProgressIndicator?` parameter (default `null` so existing unit tests are unaffected).

```kotlin
class AnalysisEngine(
    private val settingsProvider: () -> GhostDebuggerSettings.State =
        { GhostDebuggerSettings.getInstance().snapshot() },
    private val apiKeyProvider: () -> String? = { ApiKeyManager.getApiKey() },
    private val aiPassRunner: AiPassRunner = AiPassRunner { ctx, key ->
        AIAnalyzer(AIServiceFactory.create(settingsProvider(), key)!!).analyze(ctx)
    },
    private val progress: ProgressIndicator? = null
) {
    // …

    private fun runStaticPass(context: AnalysisContext): List<Issue> {
        val collected = mutableListOf<Issue>()
        for ((idx, analyzer) in analyzers.withIndex()) {
            progress?.checkCanceled()
            progress?.text2 = "Analyzer: ${analyzer.name}"
            try {
                val produced = analyzer.analyze(context).map { /* existing copy-tag */ }
                collected.addAll(produced)
            } catch (e: Exception) {
                log.warn("Analyzer ${analyzer.name} failed; continuing", e)
            }
        }
        return collected
    }

    private suspend fun runOllamaPass(/* … */): Pair<List<Issue>, EngineStatusPayload> {
        // existing gates …
        val aiContext = context.limitAiFilesTo(settings.maxAiFiles)
        val ollamaService = OllamaService(
            endpoint = settings.ollamaEndpoint,
            model = settings.ollamaModel,
            timeoutMs = settings.aiTimeoutMs,
            cacheTtlSeconds = settings.cacheTtlSeconds,
            cacheEnabled = settings.cacheEnabled
        )
        val started = System.currentTimeMillis()
        val result = runCatching {
            aiContext.parsedFiles.flatMap { file ->
                progress?.checkCanceled()
                progress?.text2 = "Ollama: ${file.path.substringAfterLast('/')}"
                ollamaService.detectIssues(file.path, file.content)
            }
        }
        // existing fold() …
    }
}
```

The `runOpenAiPass` mirror is already delegated through `aiPassRunner`; the progress plumbing happens inside `AIAnalyzer` below.

### 3.3 `AIAnalyzer` receives an `AIService` and honors progress

Convert `AIAnalyzer` from a hardcoded `OpenAIService` consumer to a generic `AIService` consumer. This is the Phase 5 drift fix (§7.2 in the gap analysis) **and** the progress hook point for the OpenAI pass.

```kotlin
class AIAnalyzer(
    private val service: AIService,
    private val progress: ProgressIndicator? = null,
    private val concurrency: Int = 3
) {
    private val log = logger<AIAnalyzer>()
    private val semaphore = Semaphore(concurrency)

    suspend fun analyze(context: AnalysisContext): List<Issue> {
        val analyzableFiles = context.parsedFiles.filter {
            it.extension in setOf("ts", "tsx", "js", "jsx", "kt", "java") &&
            it.content.lines().size < 2000
        }
        val results = mutableListOf<Issue>()
        coroutineScope {
            val deferred = analyzableFiles.map { file ->
                async {
                    semaphore.withPermit {
                        progress?.checkCanceled()
                        progress?.text2 = "AI: ${file.path.substringAfterLast('/')}"
                        try {
                            service.detectIssues(file.path, file.content)
                        } catch (e: Exception) {
                            log.warn("AI pass failed for ${file.path}", e)
                            emptyList()
                        }
                    }
                }
            }
            deferred.awaitAll().forEach { results.addAll(it) }
        }
        return results
    }
}
```

The `aiPassRunner` default in `AnalysisEngine` is updated to build an `AIService` via the factory:

```kotlin
private val aiPassRunner: AiPassRunner = AiPassRunner { ctx, key ->
    val settings = settingsProvider()
    val service = AIServiceFactory.create(settings, key)
        ?: return@AiPassRunner emptyList()
    AIAnalyzer(service, progress).analyze(ctx)
}
```

### 3.4 Webview cancel affordance

Add a `cancelAnalysis()` method to `pluginBridge.ts`:

```ts
cancelAnalysis(): void {
  this.queryBackend({ type: 'CANCEL_ANALYSIS' })
}
```

Register a new `UIEvent.CancelAnalysis` in `UIEventParser.kt` and route it to `GhostDebuggerService.cancelAnalysis()`.

In `StatusBar.tsx`, render a Cancel button next to the `EngineStatusPill` when `analysisProgress != null`:

```tsx
{analysisProgress && (
  <button
    type="button"
    onClick={() => bridge.cancelAnalysis()}
    className="text-xs px-2 py-1 border border-[var(--border-default)] hover:bg-[var(--bg-tertiary)]"
  >
    Cancel
  </button>
)}
```

`appStore.ts` gains `analysisProgress` in state and handles a `SET_ANALYSIS_PROGRESS` action dispatched by a new bridge event `onAnalysisProgress(payload)`.

### 3.5 Bridge methods for progress events

Add `sendAnalysisProgress(text: String, fraction: Double)` to `JcefBridge.kt`:

```kotlin
fun sendAnalysisProgress(text: String, fraction: Double) {
    val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
    val payload = """{"text":"$escaped","fraction":$fraction}"""
    executeJS("window.__aegis_debug__ && window.__aegis_debug__.onAnalysisProgress($payload)")
}
```

Wire it inside `analyzeWithProgress` after each `indicator.fraction = X` assignment.

---

## 4. P0 — PSI Safety in `FixApplicator`

**V1 clauses closed:** §8 "PSI tree remained valid", §8 "resulting code is syntactically valid", §18.3 "Engine Verified labeling is only used when deterministic validation passes".

### 4.1 Post-commit re-parse with rollback

Replace the current `FixWriter.Default` body in `FixApplicator.kt` with a version that commits the document through `PsiDocumentManager`, walks the resulting PSI for `PsiErrorElement`, and rolls back on any error.

```kotlin
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil

val Default = FixWriter { fix, project ->
    val log = logger<FixWriter>()
    try {
        val vf = ApplicationManager.getApplication()
            .runReadAction<com.intellij.openapi.vfs.VirtualFile?> {
                LocalFileSystem.getInstance().findFileByPath(fix.filePath)
            } ?: return@FixWriter false

        val fdm = FileDocumentManager.getInstance()
        val document = ApplicationManager.getApplication()
            .runReadAction<com.intellij.openapi.editor.Document?> {
                fdm.getDocument(vf)
            } ?: return@FixWriter false

        var succeeded = false
        WriteCommandAction.runWriteCommandAction(project, "Apply Aegis Debug Fix", null, Runnable {
            val startOffset = document.getLineStartOffset(fix.lineStart - 1)
            val endOffset = document.getLineEndOffset(fix.lineEnd - 1)
            val originalText = document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))

            document.replaceString(startOffset, endOffset, fix.fixedCode)

            val psiDocMgr = PsiDocumentManager.getInstance(project)
            psiDocMgr.commitDocument(document)

            val psiFile = psiDocMgr.getPsiFile(document)
            val firstError = psiFile?.let { PsiTreeUtil.findChildOfType(it, PsiErrorElement::class.java) }

            if (firstError != null) {
                log.warn(
                    "Fix rejected: PSI error after apply for issue ${fix.issueId} " +
                    "at offset ${firstError.textOffset}: ${firstError.errorDescription}"
                )
                val newEnd = startOffset + fix.fixedCode.length
                document.replaceString(startOffset, newEnd, originalText)
                psiDocMgr.commitDocument(document)
                succeeded = false
            } else {
                fdm.saveDocument(document)
                succeeded = true
            }
        })
        succeeded
    } catch (e: Exception) {
        logger<FixWriter>().warn("FixWriter.Default failed for issue ${fix.issueId}: ${e.message}", e)
        false
    }
}
```

### 4.2 User-facing rejection message

When `FixApplicator.apply` returns `false` specifically due to PSI invalidity, `GhostDebuggerService.handleApplyFixRequested` must tell the user why. Capture the reason via a small sealed result type:

```kotlin
sealed class FixApplyResult {
    data object Success : FixApplyResult()
    data class Rejected(val reason: String) : FixApplyResult()
    data class Failed(val throwable: Throwable) : FixApplyResult()
}

class FixApplicator(private val writer: FixWriter = FixWriter.Default) {
    fun apply(fix: CodeFix, project: Project): FixApplyResult {
        return try {
            if (writer.write(fix, project)) FixApplyResult.Success
            else FixApplyResult.Rejected("The proposed fix would produce invalid code and was not applied.")
        } catch (t: Throwable) {
            FixApplyResult.Failed(t)
        }
    }
}
```

Callers update accordingly. The webview receives the rejection text via `bridge?.sendError(...)`.

### 4.3 Tests

See §9 `FixApplicatorValidityTest` — must include at minimum: a positive path fixture, a negative path where `fixedCode` is syntactically broken, and an assertion that the file on disk is unchanged after rejection.

---

## 5. P0 — Phase 5 Service Migration Completion

**V1 clauses closed:** §9 "Provider Modes" (Ollama parity), Phase 5 spec drift (gap analysis §7.1, §7.2, §7.3).

### 5.1 Replace `openAIService` field and add `resolveAiService()`

In `GhostDebuggerService.kt`:

```kotlin
// DELETE: private var openAIService: OpenAIService? = null
// ADD:
@Volatile private var aiService: AIService? = null

private fun resolveAiService(): AIService? {
    // Return cached instance if settings haven't changed meaningfully.
    // Settings changes during a session are rare; we rebuild lazily each request for safety.
    val settings = GhostDebuggerSettings.getInstance().snapshot()
    val key = ApiKeyManager.getApiKey()
    return AIServiceFactory.create(settings, key).also { aiService = it }
}
```

### 5.2 Migrate the four call sites

All four locations (gap analysis §7.1 lines 338, 381, 475, 550) change to the same pattern:

```kotlin
// handleNodeClicked — replacement for lines 378–396
scope.launch {
    try {
        val service = resolveAiService() ?: run {
            withContext(Dispatchers.Swing) {
                bridge?.sendIssueExplanation(
                    issue.id,
                    "AI is disabled. Enable Ollama or OpenAI in Settings → Tools → Aegis Debug for a detailed explanation."
                )
            }
            return@launch
        }
        val explanation = service.explainIssue(issue, issue.codeSnippet)
        issue.explanation = explanation
        withContext(Dispatchers.Swing) {
            bridge?.sendIssueExplanation(issue.id, explanation)
        }
    } catch (e: Exception) {
        log.error("Failed to explain issue", e)
        withContext(Dispatchers.Swing) {
            bridge?.sendIssueExplanation(
                issue.id,
                "Could not fetch explanation: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }
}
```

```kotlin
// handleFixRequested — AI fallback replacement (lines 467–486)
scope.launch {
    try {
        val service = resolveAiService() ?: run {
            withContext(Dispatchers.Swing) {
                bridge?.sendError(
                    "No AI provider configured. Enable Ollama or OpenAI in Settings → Tools → Aegis Debug."
                )
            }
            return@launch
        }
        val fix = service.suggestFix(issue, issue.codeSnippet)
        withContext(Dispatchers.Swing) {
            bridge?.sendFixSuggestion(fix)
        }
    } catch (e: Exception) {
        log.error("Failed to generate fix suggestion", e)
        withContext(Dispatchers.Swing) {
            bridge?.sendError("Error generating fix: ${e.message}")
        }
    }
}
```

```kotlin
// handleExplainSystem — replacement for lines 542–561
scope.launch {
    try {
        val service = resolveAiService() ?: run {
            withContext(Dispatchers.Swing) {
                bridge?.sendSystemExplanation(buildLocalSystemSummary(graph))
            }
            return@launch
        }
        val summary = service.explainSystem(graph)
        withContext(Dispatchers.Swing) {
            bridge?.sendSystemExplanation(summary)
        }
    } catch (e: Exception) {
        log.error("System explanation failed", e)
        withContext(Dispatchers.Swing) {
            bridge?.sendSystemExplanation(buildLocalSystemSummary(graph))
        }
    }
}
```

```kotlin
// prefetchCriticalExplanations — extracted from analyzeProject lines 335–354
private suspend fun prefetchCriticalExplanations(result: AnalysisResult) {
    val service = resolveAiService() ?: return
    val critical = result.issues
        .filter { it.severity == IssueSeverity.ERROR }
        .take(3)
    for (issue in critical) {
        try {
            val explanation = service.explainIssue(issue, issue.codeSnippet)
            issue.explanation = explanation
            withContext(Dispatchers.Swing) {
                bridge?.sendIssueExplanation(issue.id, explanation)
            }
        } catch (e: Exception) {
            log.warn("Could not fetch explanation for issue ${issue.id}", e)
        }
    }
}
```

### 5.3 Translate Spanish strings

Replace three remaining blocks:

**Line 537** (`handleExplainSystem` no-graph branch):
```kotlin
bridge?.sendSystemExplanation(
    "Run 'Analyze Project' first to build the project graph."
)
```

**Lines 615–630** (`buildLocalSystemSummary`):
```kotlin
private fun buildLocalSystemSummary(graph: ProjectGraph): String {
    val errorFiles = graph.nodes.count { it.status == NodeStatus.ERROR }
    val warningFiles = graph.nodes.count { it.status == NodeStatus.WARNING }
    val totalIssues = graph.nodes.sumOf { it.issues.size }
    return """
        Project summary: ${graph.metadata.projectName}

        • Modules analyzed: ${graph.nodes.size}
        • Files with errors: $errorFiles
        • Files with warnings: $warningFiles
        • Total issues: $totalIssues
        • Dependencies: ${graph.edges.size}
        • Project health: ${graph.metadata.healthScore.toInt()}%

        Enable Ollama (local) or configure an OpenAI API key in Settings → Tools → Aegis Debug
        for detailed AI-powered explanations.
    """.trimIndent()
}
```

`handleNodeClicked` catch block (line 392) already migrated in §5.2.

---

## 6. P0 — Fixture Repositories & Integration Tests

**V1 clauses closed:** §17.3 "Integration Testing", §17.5 "Fixture Repositories".

### 6.1 Fixture layout

```
src/test/resources/fixtures/
├── small/
│   ├── NullSafetyBug.kt
│   ├── StateInitBug.kt
│   ├── AsyncFlowBug.kt
│   ├── CircularA.kt
│   ├── CircularB.kt
│   ├── ComplexFunction.kt
│   └── CleanCode.kt
├── medium/
│   └── … (~50 files; half with issues, half clean)
├── large/
│   └── … (~200 files; generated by a helper if needed)
└── expected/
    ├── small.json   // golden: { issues: [ {ruleId, filePath, line}, … ] }
    ├── medium.json
    └── large.json
```

Each `.kt` file begins with a comment marker (`// FIXTURE: AEG-NULL-001 @ 12`) so fixtures double as human-readable test cases.

### 6.2 `FullPipelineIntegrationTest`

```kotlin
@ExtendWith(ProjectExtension::class)
class FullPipelineIntegrationTest {
    @Test fun `small fixture produces expected issues`() {
        val fixtureDir = File("src/test/resources/fixtures/small")
        val ctx = TestContextBuilder().fromDirectory(fixtureDir).build()
        val engine = AnalysisEngine(
            settingsProvider = { defaultSettings().copy(aiProvider = AIProvider.NONE) }
        )
        val result = runBlocking { engine.analyze(ctx) }

        val expected = loadGolden("src/test/resources/fixtures/expected/small.json")
        assertEquals(expected.map { it.ruleId to it.filePath }, result.issues.map { it.ruleId to it.filePath })
    }

    @Test fun `AI unavailable falls back to static`() {
        val ctx = TestContextBuilder().fromDirectory(File("src/test/resources/fixtures/small")).build()
        val engine = AnalysisEngine(
            settingsProvider = {
                defaultSettings().copy(aiProvider = AIProvider.OPENAI, allowCloudUpload = true)
            },
            apiKeyProvider = { "fake-key" },
            aiPassRunner = AiPassRunner { _, _ -> throw java.io.IOException("simulated 503") }
        )
        val result = runBlocking { engine.analyze(ctx) }
        assertTrue(result.issues.isNotEmpty(), "Static issues must ship even when AI fails")
        assertEquals(EngineStatus.FALLBACK_TO_STATIC, result.engineStatus.status)
    }
}
```

### 6.3 `OllamaUserJourneyTest`

A boundary-layer test that **does not** require a live Ollama instance — uses an in-memory fake `AIService`:

```kotlin
class OllamaUserJourneyTest {
    @Test fun `explainSystem uses configured Ollama provider not OpenAI`() {
        val fake = object : AIService {
            val calls = mutableListOf<String>()
            override suspend fun detectIssues(filePath: String, fileContent: String) = emptyList<Issue>()
            override suspend fun explainIssue(issue: Issue, codeSnippet: String) = ""
            override suspend fun suggestFix(issue: Issue, codeSnippet: String): CodeFix = error("nope")
            override suspend fun explainSystem(graph: ProjectGraph): String {
                calls += "explainSystem"
                return "summary from ollama"
            }
        }
        // Dispatcher indirection — `GhostDebuggerService` exposes a seam for tests:
        val svc = GhostDebuggerServiceTestBuilder()
            .withAiServiceResolver { fake }
            .withSettings { aiProvider = AIProvider.OLLAMA }
            .build()

        svc.handleExplainSystem()
        // Awaits a Swing dispatch in the builder helper.
        assertEquals(listOf("explainSystem"), fake.calls)
    }
}
```

### 6.4 Performance smoke

Not a pass/fail test, but a `@Tag("perf")` suite that logs timings per fixture size so regressions surface in CI reports.

```kotlin
@Tag("perf")
class AnalysisEnginePerfSmoke {
    @Test fun `medium fixture completes within 15s on CI baseline`() {
        val ctx = TestContextBuilder().fromDirectory(File("src/test/resources/fixtures/medium")).build()
        val elapsed = measureTimeMillis { runBlocking { AnalysisEngine().analyze(ctx) } }
        println("PERF medium=${elapsed}ms")
        assertTrue(elapsed < 15_000, "medium fixture exceeded 15s budget: ${elapsed}ms")
    }
}
```

---

## 7. P1 — `cacheEnabled` wiring

**V1 clauses closed:** §14 "whether prompts/responses are cached" + "how users disable cache".

### 7.1 Service constructors

```kotlin
class OpenAIService(
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val timeoutMs: Long = 60_000,
    cacheTtlSeconds: Long = 3600,
    private val cacheEnabled: Boolean = true
) : AIService {
    private val cache = AICache(cacheTtlSeconds)
    // …

    override suspend fun explainIssue(issue: Issue, codeSnippet: String): String {
        val cacheKey = cache.computeKey(codeSnippet + issue.type.name, "explain")
        if (cacheEnabled) cache.get(cacheKey)?.let { return it }
        val response = callOpenAI(PromptTemplates.explainIssue(issue, codeSnippet), SystemPrompts.DEBUGGER)
        if (cacheEnabled) cache.put(cacheKey, response)
        return response
    }
    // Repeat gate for suggestFix & explainSystem.
}
```

`OllamaService` receives the same `cacheEnabled` parameter with identical gating. `AIServiceFactory.create` threads `settings.cacheEnabled` into both constructors.

### 7.2 Settings UI

In `GhostDebuggerConfigurable.kt`, expose a checkbox bound to `settings.cacheEnabled` with label "Cache AI responses locally (in-memory, session-scoped)".

### 7.3 Test

`OpenAIServiceCacheToggleTest` verifies: with `cacheEnabled=false`, two consecutive `explainIssue` calls hit the transport twice; with `cacheEnabled=true`, the second call is served from cache.

---

## 8. P1 — Provenance Badge Split (AI_LOCAL vs AI_CLOUD)

**V1 clauses closed:** §14 "The UI clearly indicates when cloud AI is active" at issue-card granularity.

In `webview/src/components/detail-panel/DetailPanel.tsx`, replace the unified `ProvenanceBadge` AI branch with two variants:

```tsx
function ProvenanceBadge({ sources, providers }: { sources: IssueSource[]; providers: EngineProvider[] }) {
  if (sources.includes('AI_CLOUD')) {
    return (
      <span
        className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium"
        style={{
          background: 'var(--status-info)',
          color: 'var(--bg-primary)',
          borderRadius: 'var(--radius-sm)',
        }}
        title={`Finding sent to cloud provider: ${providers.filter(p => p === 'OPENAI').join(', ')}`}
      >
        ☁ AI (Cloud)
      </span>
    )
  }
  if (sources.includes('AI_LOCAL')) {
    return (
      <span
        className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium"
        style={{
          background: 'var(--bg-tertiary)',
          color: 'var(--text-primary)',
          borderRadius: 'var(--radius-sm)',
        }}
        title={`Finding from local AI: ${providers.filter(p => p === 'OLLAMA').join(', ')}`}
      >
        ⌂ AI (Local)
      </span>
    )
  }
  return (
    <span
      className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium"
      style={{
        background: 'var(--status-success)',
        color: 'var(--bg-primary)',
        borderRadius: 'var(--radius-sm)',
      }}
    >
      ✓ Engine
    </span>
  )
}
```

Add a unit test `webview/src/__tests__/provenanceBadge.test.tsx` that renders each source combination and asserts the right label.

---

## 9. P1 — Targeted Post-Fix Re-analysis

**V1 clauses closed:** §18.3 "targeted re-analysis occurs" (currently full-project).

### 9.1 `PartialReanalyzer.kt`

```kotlin
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
```

### 9.2 Hook into `handleApplyFixRequested`

Replace the full `analyzeProject()` call with:

```kotlin
if (applied is FixApplyResult.Success) {
    withContext(Dispatchers.Swing) { bridge?.sendFixApplied(issueId) }
    currentGraph?.let { graph ->
        val ctx = rebuildContextFrom(graph) // cheap: reuse parsedFiles from currentGraph
        currentIssues = PartialReanalyzer().reanalyzeFile(project, issue.filePath, currentIssues, ctx)
        withContext(Dispatchers.Swing) {
            bridge?.sendGraphData(graph.withUpdatedIssues(currentIssues))
        }
    }
}
```

If `currentGraph` is null (no prior scan), fall back to `analyzeProject()`.

---

## 10. P1 — Fallback Message Polish

**V1 clauses closed:** §16 "user-facing messages should be concise and actionable", §20 item 23.

Update `runOpenAiPass` / `runOllamaPass` failure branches in `AnalysisEngine.kt`:

```kotlin
onFailure = { e ->
    log.warn("OpenAI pass failed; static results will ship", e)
    val reason = when (e) {
        is java.net.UnknownHostException -> "Cannot reach api.openai.com. Check your network."
        is java.net.SocketTimeoutException -> "OpenAI timed out after ${settings.aiTimeoutMs}ms. Try Ollama for local analysis."
        is java.io.IOException -> "OpenAI is unreachable. Static results were returned."
        else -> "OpenAI call failed (${e.javaClass.simpleName}). Static results were returned."
    }
    emptyList<Issue>() to EngineStatusPayload(
        provider = "OPENAI",
        status = EngineStatus.FALLBACK_TO_STATIC,
        message = reason,
        latencyMs = latency
    )
}
```

Mirror for Ollama with Ollama-specific copy ("Cannot reach ${settings.ollamaEndpoint}. Is `ollama serve` running?").

---

## 11. P2 — Release Assets

### 11.1 `plugin.xml`

```xml
<idea-plugin>
    <id>com.ghostdebugger</id>
    <name>Aegis Debug</name>
    <version>1.0.0</version>
    <vendor email="team@aegisdebug.dev" url="https://aegisdebug.dev">Aegis Debug</vendor>

    <idea-version since-build="232.0" until-build="243.*"/>

    <description><![CDATA[
    <h2>Aegis Debug — privacy-first debugging for IntelliJ</h2>

    <p><strong>Static-first analysis. Deterministic fixes. Optional local or cloud AI.</strong></p>

    <p>
        Aegis Debug finds real bugs in your Kotlin and Java code without sending
        anything to the cloud by default. Every finding is labeled with its
        source — engine-verified, local AI, or cloud AI — so you always know
        what you are trusting.
    </p>

    <h3>What's inside</h3>
    <ul>
        <li><strong>Five deterministic analyzers</strong> —
            null safety, state-before-init, async flow, circular dependencies, complexity.</li>
        <li><strong>Three PSI-based fixers</strong> with diff preview and native undo.</li>
        <li><strong>NeuroMap</strong> — visual project graph with per-file issue overlay.</li>
        <li><strong>Engine status pill</strong> — know at a glance whether you're on static, local AI, or cloud AI.</li>
        <li><strong>Ollama (local)</strong> or <strong>OpenAI (cloud)</strong> — both optional, both off by default.</li>
        <li><strong>Secure key storage</strong> via IntelliJ PasswordSafe.</li>
    </ul>

    <h3>Privacy by default</h3>
    <ul>
        <li>No telemetry.</li>
        <li>No cloud uploads unless you configure them explicitly.</li>
        <li>Local mode works offline.</li>
    </ul>
]]></description>

    <change-notes><![CDATA[
    <h3>1.0.0 — V1 GA (2026-04-XX)</h3>
    <ul>
        <li>Initial public release.</li>
        <li>Five static analyzers with documented rule IDs.</li>
        <li>Deterministic fixes with PSI-validity safety check.</li>
        <li>Ollama and OpenAI backends with fallback-to-static on failure.</li>
        <li>Cancellable analysis with progress indicator.</li>
    </ul>
]]></change-notes>

    <depends>com.intellij.modules.platform</depends>
    <!-- …existing extensions… -->
</idea-plugin>
```

### 11.2 `DATA_HANDLING.md`

```markdown
# Aegis Debug — Data Handling

**Last updated:** 2026-04-XX
**Product version:** 1.0.0

## What data leaves your machine

| Event | Data sent | Destination | Triggered by |
|-------|-----------|-------------|--------------|
| Static analysis | Nothing | — | Always local |
| Ollama AI pass | File path + file contents (up to 2000 lines) | Your configured Ollama endpoint (default: http://localhost:11434) | `aiProvider = OLLAMA` |
| OpenAI AI pass | File path + file contents (up to 2000 lines) | api.openai.com/v1 | `aiProvider = OPENAI` **AND** `allowCloudUpload = true` |
| OpenAI explain / fix / system | Issue metadata + code snippet (up to 800 chars) | api.openai.com/v1 | Same as above |
| Telemetry | None | — | Never |

## What is NOT sent

- Your project name (never included in prompts).
- Files outside the scanned set.
- API keys (stored locally in IntelliJ PasswordSafe, never transmitted except to OpenAI in the `Authorization` header when you invoke OpenAI).
- Debug session data (stack frames, variable values) — never sent to any AI provider.

## Caching

- AI responses are cached **in memory** per IDE session.
- Cache TTL defaults to 1 hour, configurable in Settings.
- Cache is **never written to disk**.
- Disable via Settings → Tools → Aegis Debug → "Cache AI responses locally".

## Key storage

- OpenAI API keys are stored via `PasswordSafe` (OS-native: Keychain on macOS, Credential Manager on Windows, Secret Service on Linux).
- Keys are never persisted to `ghostdebugger.xml` or any other plugin config file.

## Your controls

- Default provider is `NONE` — no AI, no cloud.
- `allowCloudUpload` must be **explicitly set to true** before any OpenAI request is made.
- `cacheEnabled = false` disables response caching completely.
```

### 11.3 `CHANGELOG.md`

```markdown
# Changelog

All notable changes to Aegis Debug are documented here.

## [1.0.0] — 2026-04-XX — V1 General Availability

### Added
- Five deterministic static analyzers: null safety, state-before-init, async flow, circular dependencies, complexity.
- Three PSI-based deterministic fixers with diff preview and native undo.
- NeuroMap visual project graph with per-file issue overlay.
- Enterprise Dark Navy + Cream UI with locally-bundled IBM Plex fonts.
- Engine status pill (ONLINE, OFFLINE, DEGRADED, FALLBACK_TO_STATIC, DISABLED).
- Provenance badges distinguishing engine, local-AI, and cloud-AI findings.
- Ollama (local) and OpenAI (cloud) AI backends with graceful fallback.
- API key storage via IntelliJ PasswordSafe.
- Cancellable background analysis with progress indicator.
- PSI-validity check on deterministic fix application with automatic rollback on parse error.

### Security & Privacy
- Default provider is `NONE`; no cloud access until explicitly configured.
- API keys never stored in plugin XML.
- No telemetry.
- AI response cache is in-memory only, disableable from Settings.

### Known Limitations
- Primary language support: Kotlin. Secondary: Java.
- Targeted post-fix re-analysis covers the modified file only.
- Very large repos (>500 files) are subject to the `maxFilesToAnalyze` cap.
```

### 11.4 Logo audit checklist

- [ ] Verify `aegis.svg` is a single vector mark, works at 16×16 (toolbar) and 512×512 (marketplace).
- [ ] Verify cream fill on deep navy background (V1 §13).
- [ ] Produce a light-mode variant if the IDE theme system exposes one (recommended: cream-on-navy remains acceptable on both).
- [ ] Remove `src/main/resources/icons/ghost.svg`.

### 11.5 Marketplace screenshots

Capture at `1280×800`:
1. NeuroMap with ERROR/WARNING overlays.
2. Issue detail panel showing `ProvenanceBadge` + `TrustBadge` + Apply Fix CTA.
3. Diff preview modal for a deterministic fix.
4. Settings panel (AI provider dropdown, cache toggle).
5. Engine status pill in each of the 5 states.

Save under `docs/marketplace/screenshots/`.

---

## 12. P3 (Optional) — Observability Polish

Not release-blocking, but cheap to land in the same phase if time permits.

### 12.1 `ErrorCategory.kt`

```kotlin
package com.ghostdebugger.observability

sealed class ErrorCategory(val tag: String) {
    data object AnalyzerFailure : ErrorCategory("analyzer")
    data object ParsingFailure : ErrorCategory("parse")
    data object ProviderUnavailable : ErrorCategory("provider_unavailable")
    data object ProviderTimeout : ErrorCategory("provider_timeout")
    data object BridgeFailure : ErrorCategory("bridge")
    data object FixGenerationFailure : ErrorCategory("fix_generate")
    data object FixApplicationFailure : ErrorCategory("fix_apply")
    data object ValidationFailure : ErrorCategory("validation")
}

fun com.intellij.openapi.diagnostic.Logger.warn(
    category: ErrorCategory,
    message: String,
    t: Throwable? = null
) = this.warn("[${category.tag}] $message", t)
```

Update the 8 call sites that currently do ad-hoc `log.warn("…", e)` to use the categorized overload. Non-breaking: all logs continue to work with the old API.

---

## 13. Test Plan

### 13.1 New tests summary
| File | Purpose |
|------|---------|
| `AnalysisEngineCancellationTest.kt` | Assert `checkCanceled()` propagates cancellation through analyzer loop and AI pass |
| `FixApplicatorValidityTest.kt` | Positive path + syntax-break rollback path |
| `FullPipelineIntegrationTest.kt` | End-to-end on small/medium fixtures; golden-file comparison |
| `OllamaUserJourneyTest.kt` | Verify Ollama is consulted for explain/fix/system flows |
| `OpenAIServiceCacheToggleTest.kt` | Assert transport called N=2 when cacheEnabled=false, N=1 when true |
| `PartialReanalyzerTest.kt` | Re-analyzed file issues replace stale, others preserved |
| `provenanceBadge.test.tsx` | Each `IssueSource` combination renders the right badge |
| `AnalysisEnginePerfSmoke.kt` | `@Tag("perf")` — budget regression alarm |

### 13.2 Existing tests — regressions to watch
- `AnalysisEngineProviderFallbackTest.kt` — ensure `cacheEnabled` thread-through doesn't change observed behavior.
- `AIServiceFactoryTest.kt` — add a case for `cacheEnabled=false`.
- `engineStatusPill.test.tsx` — no change expected.
- `EngineStatusBridgeTest.kt` — no change.

### 13.3 Manual QA checklist
- [ ] Start a full scan on a real medium repo; observe progress bar advances.
- [ ] Click Cancel mid-scan; analysis stops within 2s; no partial graph emitted.
- [ ] Trigger a deterministic fix that produces a valid result → applied, file saved.
- [ ] Hand-craft a fixer that emits `val x = ` (syntactically invalid) via a test seam → apply → observe rollback toast + file unchanged.
- [ ] Configure `aiProvider = OLLAMA` with a local ollama running → click issue node → observe Ollama-generated explanation (not OpenAI key prompt).
- [ ] Disable cache → two identical explanation requests observe two network calls in Ollama logs.

---

## 14. Acceptance Criteria

Phase 6 is considered complete when:

1. **Cancellation & progress**
   - [ ] `analyzeProject` runs inside a `Task.Backgroundable` with a visible progress indicator.
   - [ ] `ProgressIndicator.checkCanceled()` is called at least once per analyzer and per AI file.
   - [ ] Webview renders a Cancel button while progress is active.
   - [ ] Integration test proves cancellation terminates within 2 seconds.

2. **PSI fix safety**
   - [ ] `FixApplicator` commits the document, re-parses, and rolls back on any `PsiErrorElement`.
   - [ ] Negative-path test passes: broken fix → file unchanged on disk → user sees rejection message.

3. **Phase 5 migration**
   - [ ] Zero remaining references to `OpenAIService` as a field type in `GhostDebuggerService`.
   - [ ] `AIAnalyzer` accepts an `AIService` parameter.
   - [ ] `OllamaUserJourneyTest` passes for explain / fix / system.
   - [ ] No Spanish strings remain in `GhostDebuggerService.kt` (grep `[áéíóúñ¿¡]`).

4. **Fixture integration tests**
   - [ ] `src/test/resources/fixtures/{small,medium,large}` populated.
   - [ ] `FullPipelineIntegrationTest` green in CI.
   - [ ] AI-fallback integration case green.

5. **Cache toggle**
   - [ ] `cacheEnabled` flag gates `cache.get`/`cache.put` in both services.
   - [ ] Settings UI exposes the toggle.
   - [ ] `OpenAIServiceCacheToggleTest` green.

6. **Provenance split**
   - [ ] `ProvenanceBadge` renders distinct AI (Cloud) vs AI (Local) treatments.
   - [ ] Test covers all three source classes.

7. **Targeted re-analysis**
   - [ ] `handleApplyFixRequested` success path runs `PartialReanalyzer` on the single file.
   - [ ] `PartialReanalyzerTest` green.

8. **Fallback polish**
   - [ ] OpenAI and Ollama failure paths produce actionable messages.
   - [ ] Message content reviewed for copy quality.

9. **Release assets**
   - [ ] `plugin.xml` version bumped to `1.0.0`, `since-build`/`until-build` set, description rewritten.
   - [ ] `DATA_HANDLING.md` and `CHANGELOG.md` committed at repo root.
   - [ ] `ghost.svg` deleted.
   - [ ] Five marketplace screenshots captured.

10. **V1 §18 scorecard**
    - [ ] 22 ✅ / 0 🟡 / 0 ❌.

---

## 15. V1 GA Gate

V1 GA is ready when:
- All Phase 6 §14 acceptance criteria pass.
- Full test suite (unit + integration + vitest) green on CI.
- Manual QA checklist from §13.3 cleared on a representative medium repo.
- At least one external reviewer signs off on the marketplace description + DATA_HANDLING.md.

**Release candidate cut:** tag `v1.0.0-rc.1` once the above is green. If no critical bugs within 7 days, promote to `v1.0.0`.

---

## 16. Out of Scope (Deferred to 1.x)

- Multi-file joint fix (`PromptTemplates.jointFix` stub exists but is not exposed in V1).
- WhatIfChat / conversational graph chat.
- Continuous background analysis beyond the current file-save trigger.
- New analyzers beyond the five V1 rules.
- Framework-specific deep analysis (Spring, Ktor, Android lifecycle).
- Language support beyond Kotlin/Java.
- Telemetry / usage analytics.
- Auto-update / remote config.

These remain explicitly post-V1 per V1 §2.2.

---

## 17. File-by-File Change Summary

| File | Change type | Purpose |
|------|-------------|---------|
| `GhostDebuggerService.kt` | Modified | `Task.Backgroundable`, `aiService` field, `resolveAiService()`, translate strings, targeted re-analysis |
| `AnalysisEngine.kt` | Modified | `ProgressIndicator` param, `checkCanceled()` hooks, fallback message polish |
| `AIAnalyzer.kt` | Modified | Accept `AIService` constructor param |
| `FixApplicator.kt` | Modified | PSI validity check + rollback, `FixApplyResult` sealed class |
| `OpenAIService.kt` | Modified | `cacheEnabled` constructor param |
| `OllamaService.kt` | Modified | `cacheEnabled` constructor param |
| `AIServiceFactory.kt` | Modified | Thread `cacheEnabled` through |
| `JcefBridge.kt` | Modified | `sendAnalysisProgress` method |
| `UIEventParser.kt` | Modified | `CancelAnalysis` event |
| `plugin.xml` | Modified | Version bump, description, build range |
| `PartialReanalyzer.kt` | New | Single-file re-analysis |
| `ErrorCategory.kt` | New (optional) | Categorized logging |
| `DetailPanel.tsx` | Modified | `ProvenanceBadge` split |
| `StatusBar.tsx` | Modified | Cancel button + progress text |
| `pluginBridge.ts` | Modified | `cancelAnalysis()` |
| `appStore.ts` | Modified | `analysisProgress` state |
| `types/index.ts` | Modified | `AnalysisProgressPayload` |
| `DATA_HANDLING.md` | New | Privacy disclosure |
| `CHANGELOG.md` | New | V1 GA notes |
| `ghost.svg` | Removed | Legacy |
| `src/test/resources/fixtures/**` | New | Integration test corpus |
| 6 new test files (§2.3) | New | §13 coverage |

---

**End of Phase 6 spec.** Implementation order: §3 → §4 → §5 → §6 → §7 → §8 → §9 → §10 → §11 → §12. Ship each section with its tests green before starting the next.
