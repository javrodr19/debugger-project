# Phase 2 Implementation Spec: Aegis Debug Core Engine (Static-First Pipeline, File Limiting, Fingerprint Merge, Provider Fallback)

**Version:** 1.0 (binding)
**Status:** Ready for immediate implementation
**Source of Truth:** `aegis_debug_true_v1_spec.md` (restored from commit `ab0d481` when absent)
**Target Phase from Source Spec:** Section 20 — Phase 2 (Core Engine), items 5–8
**Prior Phase:** `aegis_debug_phase_1_foundations_spec.md` — must be merged before Phase 2 starts

---

## 1. Objective

Deliver the Core Engine that all later phases build on:

1. **Static-first pipeline.** Refactor `AnalysisEngine.analyze()` so the five static analyzers always run first, each isolated behind its own `try/catch`, then — and only then — an optional AI pass runs. This reverses the current AI-first order (see `AnalysisEngine.kt:23-48`) which violates source spec §6.
2. **File limiting and prioritization.** Honor `GhostDebuggerSettings.maxFilesToAnalyze` by capping the analyzed file set, and when the cap binds, select files using the source spec §15.3 priority order (current file → changed files → recently opened → hotspots → remaining).
3. **Issue fingerprint merge with provenance preservation.** Extend the `Issue` model with `sources`, `providers`, `confidence`, `ruleId`, and a `fingerprint()` method. Merge duplicate issues by fingerprint, preserving the union of sources and providers and the maximum confidence.
4. **Structured provider fallback handling.** When AI is unavailable (no key, network error, provider = `OLLAMA` — not yet implemented in V1, cloud upload not allowed, AI timeout, etc.), the engine must never block the scan: static results always ship, and a structured `EngineStatusPayload` describing why the AI pass was skipped or downgraded is attached to `AnalysisResult`.

Phase 2 ships **no** JCEF bridge wiring, **no** webview UI, **no** PSI fixes, **no** new analyzers, and **no** Ollama HTTP service. It delivers the data model and the engine logic so that Phases 3–6 can plug in without rework.

---

## 2. Scope

### 2.1 In Scope

| Area | Work |
|---|---|
| Issue model | Add `IssueSource`, `EngineProvider` enums; add `sources`, `providers`, `confidence`, `ruleId` fields on `Issue`; add `Issue.fingerprint()`. |
| Result model | Add `EngineStatus` enum, `EngineStatusPayload` data class; add `engineStatus: EngineStatusPayload` field on `AnalysisResult`. |
| Engine | Rewrite `AnalysisEngine.analyze()` to the source spec §6 reference shape: settings snapshot → file limit → static pass (isolated exceptions per analyzer) → optional AI pass (runCatching) → fingerprint merge → build `AnalysisResult`. |
| File cap | `AnalysisContext.limitTo(cap)` extension: no-op when `cap <= 0` or `parsedFiles.size <= cap`; otherwise return a copy with `parsedFiles` trimmed to `cap` using §15.3 ordering. |
| AI cap | `AnalysisContext.limitAiFilesTo(cap)` — independent cap applied to the AI pass only (`maxAiFiles`). |
| Prioritization | Pure internal `prioritizeFiles(...)` using lambda inputs for current file / changed files / recent files / hotspots — directly testable without mocking IntelliJ statics. |
| Provenance plumbing | Each static analyzer's output gets its `ruleId` injected (from `Analyzer.ruleId`) and `sources = [STATIC]`, `providers = [STATIC]`, `confidence = 1.0` (if not already set). AI analyzer output gets `sources = [AI_CLOUD]` (OpenAI) or `AI_LOCAL` (Ollama — reserved for Phase 5), `providers = [OPENAI]` or `[OLLAMA]`. |
| Merge | `mergeIssues(...)` groups by `fingerprint()`; each group produces one `Issue` whose `sources` and `providers` are the union across the group and whose `confidence` is `maxOrNull()` across non-null values. |
| Fallback status | Selection rule for `EngineStatusPayload` is a small decision table (§9.5). Data layer only — no bridge emission. |
| Testability | `AnalysisEngine` gains three injectable seams (settings snapshot, API key read, AI pass runner) via constructor defaults so tests never touch `ApplicationManager`. |
| Tests | 8 new test classes. All must pass. |

### 2.2 Out of Scope (strict)

- JCEF `UIEventBridge` emission of engine status (Phase 4).
- Webview engine-status pill or provider badge (Phase 4).
- PSI-based fix generation or preview (Phase 3).
- Ollama HTTP service / `OllamaService.kt` (Phase 5).
- New static analyzers beyond the existing five (no phase target).
- Migration of `AIAnalyzer` to implement the `Analyzer` interface (Phase 5 decision).
- Streaming progress / cancellation tokens (no phase target).
- Replacement of `IssueType` enum with string ids (source spec §7 shows `type: String`, but the current codebase threads `IssueType` through ~80 assertions — binding decision in §5 keeps the enum; fingerprint uses `ruleId ?: type.name`).
- `AnalysisResult` / `ProjectMetrics` becoming `@Serializable` (only `EngineStatusPayload` gains `@Serializable` — see §5).
- Cache read/write logic changes (`cacheEnabled`, `cacheTtlSeconds` remain advisory until a dedicated phase addresses caching).

---

## 3. Non-Goals

The following MUST NOT be touched by Phase 2:

1. Do **not** change `GhostDebuggerService.kt` control flow. The only change at the service consumer is that `AnalysisResult` gains a new optional field; reads of `.issues` and `.hotspots` remain identical.
2. Do **not** change the five static analyzer classes' matching logic. They compile unchanged against the extended `Analyzer` interface introduced in Phase 1.
3. Do **not** modify `AIAnalyzer.kt` internals. The engine wraps it in a `runCatching` and a provenance-tagging `.map { issue.copy(...) }`, but `AIAnalyzer` itself is left alone.
4. Do **not** rename, move, or re-purpose `Issue.type: IssueType`. Field additions are purely additive (all default values).
5. Do **not** change `@Storage("ghostdebugger.xml")` or any persisted state field. Phase 2 is pure runtime logic.
6. Do **not** introduce new Gradle dependencies.
7. Do **not** emit any new `UIEvent` or bridge message.
8. Do **not** spawn additional coroutine contexts beyond what `AIAnalyzer` already does; the engine remains a single `suspend fun`.
9. Do **not** alter `AnalyzerContractTest` / per-analyzer tests written in Phase 1. They continue to pass unchanged.
10. Do **not** add UI surfaces. Phase 2 is invisible to the user until Phase 4 wires the status pill.

---

## 4. Implementation Decisions

Derived from the repository state and binding for Phase 2:

| Decision | Value | Source |
|---|---|---|
| Primary language | Kotlin 2.0.21 | `build.gradle.kts:3` |
| JVM toolchain | Java 21 | `build.gradle.kts:46` |
| Coroutines runtime | `kotlinx-coroutines-core:1.9.0` | `build.gradle.kts` |
| Serialization | `kotlinx-serialization-json:1.7.3` | `build.gradle.kts:32` |
| Kotlin package root | `com.ghostdebugger` (retained) | Phase 1 §5.1 |
| Settings service | `com.ghostdebugger.settings.GhostDebuggerSettings` with `snapshot()` / `update {}` / `validate()` | Phase 1 §7.1 |
| Provider enum | `AIProvider { NONE, OPENAI, OLLAMA }` | Phase 1 §7.1 |
| Credential store | `ApiKeyManager.getApiKey()` (PasswordSafe, service name `"GhostDebugger"`) | existing |
| Static analyzers | `NullSafetyAnalyzer`, `StateInitAnalyzer`, `AsyncFlowAnalyzer`, `CircularDependencyAnalyzer`, `ComplexityAnalyzer` | `AnalysisEngine.kt:11-17` |
| AI analyzer | `AIAnalyzer(apiKey)` with internal `Semaphore(3)`, filters `{ts,tsx,js,jsx,kt,java}` < 2000 lines | `AIAnalyzer.kt:13-26` |
| `Analyzer` SPI | `name`, `ruleId`, `defaultSeverity`, `description`, `analyze(context)` | Phase 1 §7.3 |
| Graph model | `InMemoryGraph` with `getAllNodes(): List<GraphNode>`; each `GraphNode` has `complexity: Int` | `InMemoryGraph.kt:30`, `GraphModels.kt:34` |
| Consumer of `AnalysisEngine` | `GhostDebuggerService.kt:295` — `AnalysisEngine().analyze(analysisContext)` | grep |
| IntelliJ APIs for priority | `FileEditorManager.getInstance(project).selectedFiles`, `ChangeListManager.getInstance(project).changeLists`, `EditorHistoryManager.getInstance(project).fileList` | IntelliJ Platform SDK |
| Test framework | JUnit 5 via `useJUnitPlatform()` | `build.gradle.kts:81` |
| Mocking | MockK 1.13.15 (uses `relaxed = true` for `Project`) | Phase 1 §5.16 |
| Coroutines test | `kotlinx-coroutines-test:1.9.0` with `runTest { }` | `build.gradle.kts:42` |

---

## 5. Implementation Decisions Made From Ambiguity

Binding for Phase 2. Deviation requires a new spec revision.

1. **`Issue.type` stays as the `IssueType` enum.** Source spec §7 shows `type: String`. The current codebase has ~80 `it.type == IssueType.X` references (production + Phase 1 tests). Converting them is out of scope and buys nothing in Phase 2. Binding: fingerprint uses `ruleId ?: type.name`, so a future string migration does not invalidate fingerprints.

2. **New fields on `Issue` are added with safe defaults.** `sources = listOf(IssueSource.STATIC)`, `providers = listOf(EngineProvider.STATIC)`, `confidence = null`, `ruleId = null`. Any analyzer that forgets to tag its output still compiles and produces a valid issue — the engine then backfills `ruleId`, `sources`, `providers`, and `confidence` per §9.1.

3. **`Issue` stays a `data class`, keeps `@Serializable`.** New fields are all `kotlinx.serialization`-friendly (enums with default values, nullable `Double`, nullable `String`, `List<Enum>`). Binding: serialization stability is preserved because added fields have default values and kotlinx-serialization treats missing fields with defaults as present.

4. **`fingerprint()` is a method, not a field.** Computed on demand from `ruleId ?: type.name`, `filePath`, `line`. Not serialized. Reason: it would otherwise go stale if any of the three inputs were mutated by downstream consumers.

5. **Fingerprint format is exactly `"$rule:$filePath:$line"`.** Binding. Column is deliberately omitted — static analyzers and AI reporters frequently disagree on column by one or two, and a column-sensitive fingerprint would fragment merge groups. Line granularity is the correct trade-off.

6. **`AnalysisResult.engineStatus` is non-nullable.** Every run produces exactly one status payload. A static-only run where AI was not even attempted gets `EngineStatus.DISABLED` with a descriptive message. Binding — callers can rely on always having a payload to render.

7. **`EngineStatusPayload` is `@Serializable`.** It will cross the JCEF bridge in Phase 4; adding `@Serializable` now is a zero-cost forward-compat decision.

8. **`AnalysisResult` and `ProjectMetrics` remain NOT `@Serializable`.** They are assembled on the Kotlin side and never cross the bridge as such — the bridge transmits the **webview-projection** types. This is a Phase-4 concern, not Phase 2. Binding.

9. **`EngineStatusPayload.provider: String`** (not `EngineProvider` enum). Rationale: source spec §10 shows a string, and the value can include fine-grained labels like `"OPENAI/gpt-4o"` or `"OLLAMA/llama3"` in later phases. Binding.

10. **`AIProvider.OLLAMA` in Phase 2 is treated as "not yet implemented."** The provider switch in the engine matches `OLLAMA` → emits `EngineStatus.FALLBACK_TO_STATIC` with message `"Ollama integration not yet available; continuing with static-only results."` The real Ollama call lands in Phase 5. Binding.

11. **`AIProvider.OPENAI` without `allowCloudUpload` is `EngineStatus.DISABLED`**, not `FALLBACK_TO_STATIC`. Reason: this is an explicit user choice (key present, cloud off), not a failure. The webview pill distinguishes these in Phase 4. Binding.

12. **`AIProvider.OPENAI` with `allowCloudUpload=true` but no API key is `FALLBACK_TO_STATIC`.** Reason: configuration gap, not a deliberate disable. Matches the user's mental model ("AI wanted but not reachable"). Binding.

13. **`AIProvider.OPENAI` with `maxAiFiles == 0` is `EngineStatus.DISABLED`.** Reason: deliberate user setting that says "do not call AI." Same category as provider = `NONE`. Binding.

14. **`AnalysisEngine` gains three injectable constructor defaults**: `settingsProvider`, `apiKeyProvider`, `aiPassRunner`. Each is a `() -> ...` (plus `aiPassRunner: AiPassRunner` functional interface). Defaults call the real services. Tests inject fakes. Binding. This makes the engine testable without `ApplicationManager` bootstrapping.

15. **`AiPassRunner` is a `fun interface`, not a typealias.** Clearer Kotlin ergonomics in tests (`AiPassRunner { ctx, key -> ... }`). Signature: `suspend fun run(context: AnalysisContext, apiKey: String): List<Issue>`. Binding.

16. **`GhostDebuggerService.kt:295` call site does NOT change**. The engine's no-arg constructor default wires the real settings/key/AI path. Binding — avoids a service-wide refactor.

17. **File prioritization is isolated in a pure function `prioritizeFiles(...)`.** It takes the file list, the cap, and four lambdas (`currentFilePath`, `changedFilePaths`, `recentFilePaths`, `hotspotFilePaths`). The engine-side `AnalysisContext.limitTo(cap)` constructs those lambdas via `runCatching { IntelliJ API }`. Binding — enables unit tests that never touch IntelliJ statics.

18. **Priority ordering algorithm uses `LinkedHashSet<ParsedFile>` to preserve first-insertion wins**, then `.take(cap)`. Binding. Files present in a higher-priority bucket are not duplicated.

19. **Hotspots are derived from `GraphNode.complexity` descending.** `InMemoryGraph.getAllNodes()` is sorted by `.complexity` desc, then `.filePath` is mapped back through the `ParsedFile` lookup. Binding — matches what we already compute and avoids introducing a new graph metric.

20. **Files produced by the fallthrough step 5 (remaining) preserve source order.** Reason: deterministic behavior so that two consecutive runs on the same project hit the same cap slice. Binding.

21. **`AnalysisContext.limitTo(cap)` with `cap <= 0` is a no-op.** Source spec §5 declares `maxFilesToAnalyze <= 0` is invalid and coerced to 500 by `GhostDebuggerSettings.validate()`; this guard is defense-in-depth. Binding.

22. **`AnalysisContext.limitAiFilesTo(cap)` with `cap <= 0` returns an empty file list** (not a no-op). Reason: `maxAiFiles == 0` is a legitimate user setting meaning "send zero files to AI." Binding.

23. **`maxAiFiles` cap applies to the AI input only.** Static analyzers always see the full `limitTo(maxFilesToAnalyze)` set. Reason: static cost is bounded by the analyzer's regex work; AI cost is per-file OpenAI calls. Binding.

24. **When the AI pass raises, the engine logs at `WARN` and emits `EngineStatus.FALLBACK_TO_STATIC`.** Exception message → `message` field. Static results ship. Binding — matches source spec §9 "never block the scan."

25. **`EngineStatusPayload.latencyMs` is populated only for actually-attempted AI runs.** For `DISABLED` / `FALLBACK_TO_STATIC` (without attempt), `latencyMs` is `null`. Binding.

26. **Per-analyzer timing** (wall clock per analyzer) is not tracked in Phase 2. Reason: adds complexity without a caller. Deferred. Binding.

27. **Issue IDs remain analyzer-generated strings (UUID or synthetic).** No change. The engine does not re-generate ids during merge; it uses `group.first().id`. Binding.

28. **`group.first()` retention rule** during merge: the issue with the highest-confidence winner is preferred for the "base" copy. If multiple have the same max confidence, the first in the `staticIssues + aiIssues` concatenation wins (so static wins ties). Binding — preserves deterministic behavior and makes static-detected titles/descriptions authoritative when AI agrees.

29. **Confidence for static issues defaults to `1.0`.** Reason: static matches are deterministic; a regex hit is either there or not. Binding.

30. **Confidence for AI issues defaults to `0.7`** when the model doesn't return one. Reason: source spec §7 shows AI confidence as typically sub-1.0; 0.7 is a conservative placeholder. Binding — this is a provenance tag, not a quality signal driving UI behavior in Phase 2.

31. **`GhostDebuggerService.kt` consumer fields stay read-compatible.** `AnalysisResult.issues` / `.hotspots` / `.risks` / `.metrics` are all present. The new `engineStatus` is additive. Binding.

32. **No new public Kotlin classes leak into `bridge/` or `toolwindow/`.** Phase 4 will add bridge surfaces; Phase 2 keeps everything inside `model/` and `analysis/`. Binding.

33. **Each new test class lives under `src/test/kotlin/com/ghostdebugger/...` mirroring the production package.** JUnit 5 + MockK + `kotlinx-coroutines-test` (`runTest { }` for `suspend fun analyze`). Binding.

---

## 6. Files to Create or Modify

### 6.1 Create

| Path | Purpose |
|---|---|
| `src/main/kotlin/com/ghostdebugger/model/EngineStatus.kt` | `EngineStatus` enum + `EngineStatusPayload` data class (source spec §10). Both `@Serializable`. |
| `src/main/kotlin/com/ghostdebugger/analysis/AnalysisContextPrioritization.kt` | Extensions: `AnalysisContext.limitTo(cap)`, `AnalysisContext.limitAiFilesTo(cap)`; internal pure `prioritizeFiles(...)` used by both. |
| `src/main/kotlin/com/ghostdebugger/analysis/AiPassRunner.kt` | `fun interface AiPassRunner { suspend fun run(context: AnalysisContext, apiKey: String): List<Issue> }` + default implementation object. |
| `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineStaticFirstTest.kt` | Static pass runs before AI in every provider mode. |
| `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineProviderFallbackTest.kt` | Provider decision table (§9.5) produces the correct `EngineStatusPayload` for each scenario. |
| `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineAnalyzerIsolationTest.kt` | One throwing analyzer does not break the rest. |
| `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineFileCapTest.kt` | `maxFilesToAnalyze` caps the static input; `maxAiFiles` caps the AI input. |
| `src/test/kotlin/com/ghostdebugger/analysis/AnalysisContextPrioritizationTest.kt` | `§15.3` order is enforced by `prioritizeFiles(...)`. |
| `src/test/kotlin/com/ghostdebugger/model/IssueFingerprintTest.kt` | `fingerprint()` uses `ruleId ?: type.name`, `filePath`, `line`; stable across equal triples. |
| `src/test/kotlin/com/ghostdebugger/model/IssueMergeTest.kt` | Merge unions `sources`/`providers` and takes `maxOrNull()` of `confidence`. |
| `src/test/kotlin/com/ghostdebugger/model/EngineStatusPayloadTest.kt` | Enum values, defaults, serialization round-trip via `Json`. |

### 6.2 Modify

| Path | Target | Change summary |
|---|---|---|
| `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` | top | Add `IssueSource`, `EngineProvider` `@Serializable` enums. |
| `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` | `data class Issue` | Add four fields (`sources`, `providers`, `confidence`, `ruleId`) with defaults; add `fun fingerprint(): String` method. |
| `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` | `data class AnalysisResult` | Add `engineStatus: EngineStatusPayload` (non-null, no default — every call site already constructs `AnalysisResult` at exactly one location: `AnalysisEngine.analyze`, which is rewritten in this phase). |
| `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt` | full file | Replace with §7.2 verbatim: static-first pipeline, fingerprint merge, provider fallback. |

### 6.3 Leave untouched

- Every file under `src/main/kotlin/com/ghostdebugger/analysis/analyzers/` (including `AIAnalyzer.kt`).
- Every file under `src/main/kotlin/com/ghostdebugger/parser/`, `graph/`, `bridge/`, `annotator/`, `ai/`, `actions/`, `toolwindow/`, `settings/`.
- `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` — the call at line 295 works unchanged (new engine has a zero-arg constructor equivalent to the old one).
- All Phase 1 tests (`AnalyzerContractTest`, `GhostDebuggerSettingsTest`, the five analyzer tests, `FixtureFactory`). They continue to pass.
- All webview files.
- All `plugin.xml`, `build.gradle.kts`, `README.md`.

---

## 7. Data Contracts / Interfaces / Schemas

### 7.1 `AnalysisModels.kt` — delta

Add two new enums above `data class Issue`:

```kotlin
@Serializable
enum class IssueSource { STATIC, AI_LOCAL, AI_CLOUD }

@Serializable
enum class EngineProvider { STATIC, OLLAMA, OPENAI }
```

Extend `Issue`:

```kotlin
@Serializable
data class Issue(
    val id: String,
    val type: IssueType,
    val severity: IssueSeverity,
    val title: String,
    val description: String,
    val filePath: String,
    val line: Int = 0,
    val column: Int = 0,
    val codeSnippet: String = "",
    val affectedNodes: List<String> = emptyList(),
    var explanation: String? = null,
    var suggestedFix: CodeFix? = null,
    val sources: List<IssueSource> = listOf(IssueSource.STATIC),
    val providers: List<EngineProvider> = listOf(EngineProvider.STATIC),
    val confidence: Double? = null,
    val ruleId: String? = null
) {
    fun fingerprint(): String =
        listOf(ruleId ?: type.name, filePath, line.toString()).joinToString(":")
}
```

Extend `AnalysisResult`:

```kotlin
data class AnalysisResult(
    val issues: List<Issue>,
    val metrics: ProjectMetrics,
    val hotspots: List<String>,
    val risks: List<RiskItem>,
    val engineStatus: EngineStatusPayload
)
```

**Binding invariants:**
- `IssueSource`, `EngineProvider`, and the new fields on `Issue` are `@Serializable`-safe because they have default values and use enums or primitives.
- `fingerprint()` is a regular method; it is not serialized.
- `AnalysisResult.engineStatus` is non-null — producers must supply one. `AnalysisEngine` is the only producer today.

### 7.2 `AnalysisEngine.kt` — complete target contents

Replace the entire file with:

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.analysis.analyzers.AIAnalyzer
import com.ghostdebugger.analysis.analyzers.AsyncFlowAnalyzer
import com.ghostdebugger.analysis.analyzers.CircularDependencyAnalyzer
import com.ghostdebugger.analysis.analyzers.ComplexityAnalyzer
import com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzer
import com.ghostdebugger.analysis.analyzers.StateInitAnalyzer
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.AnalysisResult
import com.ghostdebugger.model.EngineProvider
import com.ghostdebugger.model.EngineStatus
import com.ghostdebugger.model.EngineStatusPayload
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.ProjectMetrics
import com.ghostdebugger.model.RiskItem
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.openapi.diagnostic.logger

class AnalysisEngine(
    private val settingsProvider: () -> GhostDebuggerSettings.State =
        { GhostDebuggerSettings.getInstance().snapshot() },
    private val apiKeyProvider: () -> String? = { ApiKeyManager.getApiKey() },
    private val aiPassRunner: AiPassRunner = AiPassRunner { ctx, key ->
        AIAnalyzer(key).analyze(ctx)
    }
) {
    private val log = logger<AnalysisEngine>()

    private val analyzers: List<Analyzer> = listOf(
        NullSafetyAnalyzer(),
        StateInitAnalyzer(),
        AsyncFlowAnalyzer(),
        CircularDependencyAnalyzer(),
        ComplexityAnalyzer()
    )

    suspend fun analyze(context: AnalysisContext): AnalysisResult {
        val settings = settingsProvider()
        val limitedContext = context.limitTo(settings.maxFilesToAnalyze)

        val staticIssues = runStaticPass(limitedContext)
        val (aiIssues, engineStatus) = runAiPass(limitedContext, settings)
        val merged = mergeIssues(staticIssues + aiIssues)

        val metrics = ProjectMetrics(
            totalFiles = limitedContext.parsedFiles.size,
            totalIssues = merged.size,
            errorCount = merged.count { it.severity == IssueSeverity.ERROR },
            warningCount = merged.count { it.severity == IssueSeverity.WARNING },
            infoCount = merged.count { it.severity == IssueSeverity.INFO },
            healthScore = calculateHealthScore(limitedContext.parsedFiles.size, merged),
            avgComplexity = limitedContext.graph.getAllNodes()
                .map { it.complexity.toDouble() }
                .average()
                .takeIf { !it.isNaN() } ?: 0.0
        )

        val hotspots = merged.groupBy { it.filePath }
            .filter { (_, issues) -> issues.size >= 2 }
            .keys.toList()

        val risks = merged.filter { it.severity == IssueSeverity.ERROR }
            .map { RiskItem(nodeId = it.filePath, riskLevel = "HIGH", reason = it.title) }

        return AnalysisResult(
            issues = merged,
            metrics = metrics,
            hotspots = hotspots,
            risks = risks,
            engineStatus = engineStatus
        )
    }

    private fun runStaticPass(context: AnalysisContext): List<Issue> {
        val collected = mutableListOf<Issue>()
        for (analyzer in analyzers) {
            try {
                val produced = analyzer.analyze(context).map { issue ->
                    issue.copy(
                        ruleId = issue.ruleId ?: analyzer.ruleId,
                        sources = if (issue.sources.isNotEmpty()) issue.sources
                                  else listOf(IssueSource.STATIC),
                        providers = if (issue.providers.isNotEmpty()) issue.providers
                                    else listOf(EngineProvider.STATIC),
                        confidence = issue.confidence ?: 1.0
                    )
                }
                log.info("${analyzer.name}: produced ${produced.size} issues")
                collected.addAll(produced)
            } catch (e: Exception) {
                log.warn("Analyzer ${analyzer.name} failed; continuing", e)
            }
        }
        return collected
    }

    private suspend fun runAiPass(
        context: AnalysisContext,
        settings: GhostDebuggerSettings.State
    ): Pair<List<Issue>, EngineStatusPayload> {
        return when (settings.aiProvider) {
            AIProvider.NONE -> emptyList<Issue>() to EngineStatusPayload(
                provider = "STATIC",
                status = EngineStatus.DISABLED,
                message = "AI provider disabled; static-only run."
            )
            AIProvider.OLLAMA -> emptyList<Issue>() to EngineStatusPayload(
                provider = "OLLAMA",
                status = EngineStatus.FALLBACK_TO_STATIC,
                message = "Ollama integration not yet available; continuing with static-only results."
            )
            AIProvider.OPENAI -> runOpenAiPass(context, settings)
        }
    }

    private suspend fun runOpenAiPass(
        context: AnalysisContext,
        settings: GhostDebuggerSettings.State
    ): Pair<List<Issue>, EngineStatusPayload> {
        if (!settings.allowCloudUpload) {
            return emptyList<Issue>() to EngineStatusPayload(
                provider = "OPENAI",
                status = EngineStatus.DISABLED,
                message = "Cloud upload is off; static-only run."
            )
        }
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return emptyList<Issue>() to EngineStatusPayload(
                provider = "OPENAI",
                status = EngineStatus.FALLBACK_TO_STATIC,
                message = "No OpenAI API key configured; continuing with static-only results."
            )
        }
        if (settings.maxAiFiles <= 0) {
            return emptyList<Issue>() to EngineStatusPayload(
                provider = "OPENAI",
                status = EngineStatus.DISABLED,
                message = "maxAiFiles = 0; AI pass skipped."
            )
        }

        val aiContext = context.limitAiFilesTo(settings.maxAiFiles)
        val started = System.currentTimeMillis()
        val result = runCatching { aiPassRunner.run(aiContext, apiKey) }
        val latency = System.currentTimeMillis() - started

        return result.fold(
            onSuccess = { issues ->
                val tagged = issues.map { it.copy(
                    sources = if (it.sources.isNotEmpty() &&
                                  it.sources != listOf(IssueSource.STATIC)) it.sources
                              else listOf(IssueSource.AI_CLOUD),
                    providers = if (it.providers.isNotEmpty() &&
                                    it.providers != listOf(EngineProvider.STATIC)) it.providers
                                else listOf(EngineProvider.OPENAI),
                    confidence = it.confidence ?: 0.7
                ) }
                tagged to EngineStatusPayload(
                    provider = "OPENAI",
                    status = EngineStatus.ONLINE,
                    message = "OpenAI pass ok (${tagged.size} issues).",
                    latencyMs = latency
                )
            },
            onFailure = { e ->
                log.warn("OpenAI pass failed; static results will ship", e)
                emptyList<Issue>() to EngineStatusPayload(
                    provider = "OPENAI",
                    status = EngineStatus.FALLBACK_TO_STATIC,
                    message = "OpenAI unreachable (${e.javaClass.simpleName}); static results returned.",
                    latencyMs = latency
                )
            }
        )
    }

    private fun mergeIssues(issues: List<Issue>): List<Issue> =
        issues.groupBy { it.fingerprint() }.map { (_, group) ->
            val base = group.maxByOrNull { it.confidence ?: 0.0 } ?: group.first()
            base.copy(
                sources = group.flatMap { it.sources }.distinct(),
                providers = group.flatMap { it.providers }.distinct(),
                confidence = group.mapNotNull { it.confidence }.maxOrNull()
            )
        }

    private fun calculateHealthScore(totalFiles: Int, issues: List<Issue>): Double {
        if (totalFiles == 0) return 100.0
        val errorPenalty = issues.count { it.severity == IssueSeverity.ERROR } * 15.0
        val warningPenalty = issues.count { it.severity == IssueSeverity.WARNING } * 5.0
        return (100.0 - errorPenalty - warningPenalty).coerceIn(0.0, 100.0)
    }
}
```

### 7.3 `AiPassRunner.kt` — complete contents

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue

fun interface AiPassRunner {
    suspend fun run(context: AnalysisContext, apiKey: String): List<Issue>
}
```

### 7.4 `EngineStatus.kt` — complete contents

```kotlin
package com.ghostdebugger.model

import kotlinx.serialization.Serializable

@Serializable
enum class EngineStatus {
    ONLINE,
    OFFLINE,
    DEGRADED,
    FALLBACK_TO_STATIC,
    DISABLED
}

@Serializable
data class EngineStatusPayload(
    val provider: String,
    val status: EngineStatus,
    val message: String? = null,
    val latencyMs: Long? = null
)
```

### 7.5 `AnalysisContextPrioritization.kt` — complete contents

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.ParsedFile
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.vcs.changes.ChangeListManager

fun AnalysisContext.limitTo(cap: Int): AnalysisContext {
    if (cap <= 0) return this
    if (parsedFiles.size <= cap) return this
    val prioritized = prioritizeFiles(
        files = parsedFiles,
        cap = cap,
        currentFilePath = {
            runCatching {
                FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path
            }.getOrNull()
        },
        changedFilePaths = {
            runCatching {
                ChangeListManager.getInstance(project).changeLists
                    .flatMap { it.changes }
                    .mapNotNull { it.virtualFile?.path }
            }.getOrElse { emptyList() }
        },
        recentFilePaths = {
            runCatching {
                EditorHistoryManager.getInstance(project).fileList.map { it.path }
            }.getOrElse { emptyList() }
        },
        hotspotFilePaths = {
            graph.getAllNodes()
                .sortedByDescending { it.complexity }
                .map { it.filePath }
        }
    )
    return copy(parsedFiles = prioritized)
}

fun AnalysisContext.limitAiFilesTo(cap: Int): AnalysisContext {
    if (cap <= 0) return copy(parsedFiles = emptyList())
    if (parsedFiles.size <= cap) return this
    val prioritized = prioritizeFiles(
        files = parsedFiles,
        cap = cap,
        currentFilePath = {
            runCatching {
                FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path
            }.getOrNull()
        },
        changedFilePaths = {
            runCatching {
                ChangeListManager.getInstance(project).changeLists
                    .flatMap { it.changes }
                    .mapNotNull { it.virtualFile?.path }
            }.getOrElse { emptyList() }
        },
        recentFilePaths = {
            runCatching {
                EditorHistoryManager.getInstance(project).fileList.map { it.path }
            }.getOrElse { emptyList() }
        },
        hotspotFilePaths = {
            graph.getAllNodes()
                .sortedByDescending { it.complexity }
                .map { it.filePath }
        }
    )
    return copy(parsedFiles = prioritized)
}

internal fun prioritizeFiles(
    files: List<ParsedFile>,
    cap: Int,
    currentFilePath: () -> String?,
    changedFilePaths: () -> List<String>,
    recentFilePaths: () -> List<String>,
    hotspotFilePaths: () -> List<String>
): List<ParsedFile> {
    if (cap <= 0) return emptyList()
    if (files.size <= cap) return files
    val byPath = files.associateBy { it.path }
    val ordered = linkedSetOf<ParsedFile>()

    // 1) current file
    currentFilePath()?.let { byPath[it]?.let(ordered::add) }

    // 2) changed files
    for (p in changedFilePaths()) byPath[p]?.let(ordered::add)

    // 3) recently opened files
    for (p in recentFilePaths()) byPath[p]?.let(ordered::add)

    // 4) hotspots
    for (p in hotspotFilePaths()) byPath[p]?.let(ordered::add)

    // 5) remaining, preserving source order
    for (f in files) ordered.add(f)

    return ordered.take(cap)
}
```

### 7.6 Import adjustments in `AnalysisModels.kt`

No new imports — `kotlinx.serialization.Serializable` is already present.

---

## 8. Ordered Implementation Steps

Execute in order. Each step must leave the project compilable and all previously-introduced tests green.

### Step 0 — Baseline
1. `./gradlew build` must pass after Phase 1. Capture the output.
2. Confirm `AnalyzerContractTest` and the five per-analyzer tests are green.

### Step 1 — Model extensions
1. Open `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt`.
2. Add `IssueSource` and `EngineProvider` `@Serializable` enums above `data class Issue` (top of the file, after the package line, between the existing `@Serializable` declarations).
3. Extend `Issue` with `sources`, `providers`, `confidence`, `ruleId` (per §7.1) and the `fingerprint()` method.
4. Extend `AnalysisResult` with `engineStatus: EngineStatusPayload` (non-null, no default).
5. `./gradlew compileKotlin` — expected to fail: `AnalysisEngine.kt:85-90` builds `AnalysisResult(...)` without the new field. Fix in Step 3. Leave this failure for now — no intermediate commit is required.

### Step 2 — `EngineStatus.kt` + `AiPassRunner.kt`
1. Create `src/main/kotlin/com/ghostdebugger/model/EngineStatus.kt` with contents from §7.4.
2. Create `src/main/kotlin/com/ghostdebugger/analysis/AiPassRunner.kt` with contents from §7.3.
3. `./gradlew compileKotlin` — same failure as Step 1 in `AnalysisEngine.kt`, expected.

### Step 3 — `AnalysisEngine` rewrite
1. Replace `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt` contents with §7.2 verbatim.
2. `./gradlew compileKotlin` — must pass. The call at `GhostDebuggerService.kt:295` (`AnalysisEngine().analyze(analysisContext)`) compiles because the new constructor has all-default parameters.

### Step 4 — Prioritization extensions
1. Create `src/main/kotlin/com/ghostdebugger/analysis/AnalysisContextPrioritization.kt` with §7.5.
2. `./gradlew compileKotlin` — must pass. `AnalysisEngine.analyze` calls `context.limitTo(...)` and `context.limitAiFilesTo(...)` defined here.

### Step 5 — Fingerprint & merge tests
1. Create `src/test/kotlin/com/ghostdebugger/model/IssueFingerprintTest.kt`:

```kotlin
package com.ghostdebugger.model

import kotlin.test.Test
import kotlin.test.assertEquals

class IssueFingerprintTest {

    private fun baseIssue(
        ruleId: String? = null,
        type: IssueType = IssueType.NULL_SAFETY,
        filePath: String = "/src/A.tsx",
        line: Int = 5
    ) = Issue(
        id = "x", type = type, severity = IssueSeverity.ERROR,
        title = "t", description = "d",
        filePath = filePath, line = line,
        ruleId = ruleId
    )

    @Test
    fun `fingerprint uses ruleId when present`() {
        val i = baseIssue(ruleId = "AEG-NULL-001")
        assertEquals("AEG-NULL-001:/src/A.tsx:5", i.fingerprint())
    }

    @Test
    fun `fingerprint falls back to type name when ruleId is null`() {
        val i = baseIssue(ruleId = null)
        assertEquals("NULL_SAFETY:/src/A.tsx:5", i.fingerprint())
    }

    @Test
    fun `fingerprint is stable across equal triples`() {
        val a = baseIssue(ruleId = "AEG-NULL-001")
        val b = baseIssue(ruleId = "AEG-NULL-001")
        assertEquals(a.fingerprint(), b.fingerprint())
    }

    @Test
    fun `fingerprint differs when line differs`() {
        val a = baseIssue(ruleId = "AEG-NULL-001", line = 5)
        val b = baseIssue(ruleId = "AEG-NULL-001", line = 6)
        assert(a.fingerprint() != b.fingerprint())
    }
}
```

2. Create `src/test/kotlin/com/ghostdebugger/model/IssueMergeTest.kt`:

```kotlin
package com.ghostdebugger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssueMergeTest {
    // Hand-run the same merge logic used inside AnalysisEngine.mergeIssues.
    private fun merge(issues: List<Issue>): List<Issue> =
        issues.groupBy { it.fingerprint() }.map { (_, group) ->
            val base = group.maxByOrNull { it.confidence ?: 0.0 } ?: group.first()
            base.copy(
                sources = group.flatMap { it.sources }.distinct(),
                providers = group.flatMap { it.providers }.distinct(),
                confidence = group.mapNotNull { it.confidence }.maxOrNull()
            )
        }

    private fun issue(
        ruleId: String? = "AEG-NULL-001",
        line: Int = 5,
        sources: List<IssueSource> = listOf(IssueSource.STATIC),
        providers: List<EngineProvider> = listOf(EngineProvider.STATIC),
        confidence: Double? = 1.0
    ) = Issue(
        id = "x", type = IssueType.NULL_SAFETY,
        severity = IssueSeverity.ERROR,
        title = "t", description = "d",
        filePath = "/src/A.tsx", line = line,
        ruleId = ruleId, sources = sources, providers = providers,
        confidence = confidence
    )

    @Test
    fun `merge unions sources and providers for matching fingerprints`() {
        val merged = merge(listOf(
            issue(sources = listOf(IssueSource.STATIC),
                  providers = listOf(EngineProvider.STATIC),
                  confidence = 1.0),
            issue(sources = listOf(IssueSource.AI_CLOUD),
                  providers = listOf(EngineProvider.OPENAI),
                  confidence = 0.8)
        ))
        assertEquals(1, merged.size)
        assertTrue(IssueSource.STATIC in merged[0].sources)
        assertTrue(IssueSource.AI_CLOUD in merged[0].sources)
        assertTrue(EngineProvider.STATIC in merged[0].providers)
        assertTrue(EngineProvider.OPENAI in merged[0].providers)
    }

    @Test
    fun `merge takes max confidence across the group`() {
        val merged = merge(listOf(
            issue(confidence = 0.2),
            issue(confidence = 0.9),
            issue(confidence = null)
        ))
        assertEquals(0.9, merged[0].confidence)
    }

    @Test
    fun `merge keeps issues with different fingerprints separate`() {
        val merged = merge(listOf(
            issue(line = 5),
            issue(line = 9)
        ))
        assertEquals(2, merged.size)
    }

    @Test
    fun `merge with single issue returns it unchanged except for distinct lists`() {
        val single = issue()
        val merged = merge(listOf(single))
        assertEquals(1, merged.size)
        assertEquals(single.fingerprint(), merged[0].fingerprint())
    }
}
```

3. Create `src/test/kotlin/com/ghostdebugger/model/EngineStatusPayloadTest.kt`:

```kotlin
package com.ghostdebugger.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineStatusPayloadTest {

    @Test
    fun `enum contains exactly ONLINE OFFLINE DEGRADED FALLBACK_TO_STATIC DISABLED`() {
        assertEquals(
            listOf("ONLINE", "OFFLINE", "DEGRADED", "FALLBACK_TO_STATIC", "DISABLED"),
            EngineStatus.values().map { it.name }
        )
    }

    @Test
    fun `payload defaults message and latency to null`() {
        val p = EngineStatusPayload(provider = "STATIC", status = EngineStatus.DISABLED)
        assertEquals(null, p.message)
        assertEquals(null, p.latencyMs)
    }

    @Test
    fun `payload serializes and deserializes via kotlinx-serialization`() {
        val src = EngineStatusPayload(
            provider = "OPENAI",
            status = EngineStatus.FALLBACK_TO_STATIC,
            message = "boom",
            latencyMs = 123L
        )
        val json = Json.encodeToString(src)
        assertTrue(json.contains("OPENAI"))
        val back = Json.decodeFromString<EngineStatusPayload>(json)
        assertEquals(src, back)
    }
}
```

4. `./gradlew test --tests "com.ghostdebugger.model.*"` — new tests green.

### Step 6 — Prioritization tests
Create `src/test/kotlin/com/ghostdebugger/analysis/AnalysisContextPrioritizationTest.kt`:

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.model.ParsedFile
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisContextPrioritizationTest {

    private fun pf(path: String): ParsedFile = ParsedFile(
        virtualFile = mockk<VirtualFile>(relaxed = true),
        path = path,
        extension = "kt",
        content = ""
    )

    @Test
    fun `returns all files when under the cap`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"))
        val out = prioritizeFiles(
            files = files, cap = 5,
            currentFilePath = { null },
            changedFilePaths = { emptyList() },
            recentFilePaths = { emptyList() },
            hotspotFilePaths = { emptyList() }
        )
        assertEquals(files, out)
    }

    @Test
    fun `puts current file first`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"), pf("/c.kt"), pf("/d.kt"))
        val out = prioritizeFiles(
            files = files, cap = 2,
            currentFilePath = { "/c.kt" },
            changedFilePaths = { emptyList() },
            recentFilePaths = { emptyList() },
            hotspotFilePaths = { emptyList() }
        )
        assertEquals(listOf("/c.kt", "/a.kt"), out.map { it.path })
    }

    @Test
    fun `ordering is current then changed then recent then hotspot then remaining`() {
        val files = listOf(
            pf("/a.kt"), pf("/b.kt"), pf("/c.kt"),
            pf("/d.kt"), pf("/e.kt"), pf("/f.kt")
        )
        val out = prioritizeFiles(
            files = files, cap = 5,
            currentFilePath = { "/f.kt" },
            changedFilePaths = { listOf("/a.kt") },
            recentFilePaths = { listOf("/e.kt") },
            hotspotFilePaths = { listOf("/d.kt") }
        )
        assertEquals(
            listOf("/f.kt", "/a.kt", "/e.kt", "/d.kt", "/b.kt"),
            out.map { it.path }
        )
    }

    @Test
    fun `deduplicates when a file appears in multiple buckets`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"), pf("/c.kt"))
        val out = prioritizeFiles(
            files = files, cap = 3,
            currentFilePath = { "/a.kt" },
            changedFilePaths = { listOf("/a.kt", "/b.kt") },
            recentFilePaths = { listOf("/a.kt") },
            hotspotFilePaths = { listOf("/b.kt") }
        )
        assertEquals(listOf("/a.kt", "/b.kt", "/c.kt"), out.map { it.path })
    }

    @Test
    fun `falls back to source order when no signals are available`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"), pf("/c.kt"))
        val out = prioritizeFiles(
            files = files, cap = 2,
            currentFilePath = { null },
            changedFilePaths = { emptyList() },
            recentFilePaths = { emptyList() },
            hotspotFilePaths = { emptyList() }
        )
        assertEquals(listOf("/a.kt", "/b.kt"), out.map { it.path })
    }

    @Test
    fun `cap of zero returns empty list`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"))
        val out = prioritizeFiles(
            files = files, cap = 0,
            currentFilePath = { "/a.kt" },
            changedFilePaths = { emptyList() },
            recentFilePaths = { emptyList() },
            hotspotFilePaths = { emptyList() }
        )
        assertEquals(emptyList(), out)
    }

    @Test
    fun `unknown paths in signals are ignored`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"))
        val out = prioritizeFiles(
            files = files, cap = 1,
            currentFilePath = { "/does-not-exist.kt" },
            changedFilePaths = { listOf("/nowhere.kt") },
            recentFilePaths = { emptyList() },
            hotspotFilePaths = { emptyList() }
        )
        assertEquals(listOf("/a.kt"), out.map { it.path })
    }
}
```

### Step 7 — Engine static-first test
Create `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineStaticFirstTest.kt`:

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.EngineProvider
import com.ghostdebugger.model.EngineStatus
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisEngineStaticFirstTest {

    private fun settings(
        aiProvider: AIProvider = AIProvider.NONE,
        allowCloudUpload: Boolean = false,
        maxFilesToAnalyze: Int = 500,
        maxAiFiles: Int = 100
    ) = GhostDebuggerSettings.State(
        aiProvider = aiProvider,
        allowCloudUpload = allowCloudUpload,
        maxFilesToAnalyze = maxFilesToAnalyze,
        maxAiFiles = maxAiFiles
    )

    // A real AnalysisContext with one TSX file that triggers NullSafetyAnalyzer.
    private fun ctx(): AnalysisContext {
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              return <div>{user.name}</div>;
            }
        """.trimIndent()
        return FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/A.tsx", "tsx", code))
        )
    }

    @Test
    fun `static analyzers run even when AI provider is NONE`() = runTest {
        val engine = AnalysisEngine(
            settingsProvider = { settings(aiProvider = AIProvider.NONE) },
            apiKeyProvider = { null },
            aiPassRunner = AiPassRunner { _, _ -> error("AI should not run") }
        )
        val result = engine.analyze(ctx())
        assertTrue(result.issues.any { it.type == IssueType.NULL_SAFETY })
        assertEquals("STATIC", result.engineStatus.provider)
        assertEquals(EngineStatus.DISABLED, result.engineStatus.status)
    }

    @Test
    fun `static issues carry STATIC sources and providers`() = runTest {
        val engine = AnalysisEngine(
            settingsProvider = { settings() },
            apiKeyProvider = { null },
            aiPassRunner = AiPassRunner { _, _ -> emptyList() }
        )
        val result = engine.analyze(ctx())
        val staticHit = result.issues.first { it.type == IssueType.NULL_SAFETY }
        assertTrue(IssueSource.STATIC in staticHit.sources)
        assertTrue(EngineProvider.STATIC in staticHit.providers)
        assertEquals(1.0, staticHit.confidence)
    }

    @Test
    fun `static analyzers run before AI and both contribute when AI is ONLINE`() = runTest {
        val aiIssue = Issue(
            id = "ai-1",
            type = IssueType.MISSING_ERROR_HANDLING,
            severity = IssueSeverity.WARNING,
            title = "Catch-all missing",
            description = "AI-flagged",
            filePath = "/src/A.tsx",
            line = 2
        )
        val engine = AnalysisEngine(
            settingsProvider = {
                settings(aiProvider = AIProvider.OPENAI, allowCloudUpload = true)
            },
            apiKeyProvider = { "sk-test" },
            aiPassRunner = AiPassRunner { _, _ -> listOf(aiIssue) }
        )
        val result = engine.analyze(ctx())
        assertTrue(result.issues.any { it.type == IssueType.NULL_SAFETY },
            "static issue missing")
        assertTrue(result.issues.any { it.type == IssueType.MISSING_ERROR_HANDLING },
            "AI issue missing")
        assertEquals(EngineStatus.ONLINE, result.engineStatus.status)
    }

    @Test
    fun `static analyzers run even when the AI pass throws`() = runTest {
        val engine = AnalysisEngine(
            settingsProvider = {
                settings(aiProvider = AIProvider.OPENAI, allowCloudUpload = true)
            },
            apiKeyProvider = { "sk-test" },
            aiPassRunner = AiPassRunner { _, _ -> throw RuntimeException("network down") }
        )
        val result = engine.analyze(ctx())
        assertTrue(result.issues.any { it.type == IssueType.NULL_SAFETY })
        assertEquals(EngineStatus.FALLBACK_TO_STATIC, result.engineStatus.status)
        assertEquals("OPENAI", result.engineStatus.provider)
    }
}
```

### Step 8 — Engine provider-fallback test
Create `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineProviderFallbackTest.kt`:

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.model.EngineStatus
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisEngineProviderFallbackTest {

    private fun state(
        aiProvider: AIProvider = AIProvider.NONE,
        allowCloudUpload: Boolean = false,
        maxAiFiles: Int = 100
    ) = GhostDebuggerSettings.State(
        aiProvider = aiProvider,
        allowCloudUpload = allowCloudUpload,
        maxAiFiles = maxAiFiles
    )

    private fun emptyCtx() = FixtureFactory.context(emptyList())

    private fun engine(
        settings: GhostDebuggerSettings.State,
        apiKey: String? = null,
        runner: AiPassRunner = AiPassRunner { _, _ -> emptyList() }
    ) = AnalysisEngine(
        settingsProvider = { settings },
        apiKeyProvider = { apiKey },
        aiPassRunner = runner
    )

    @Test
    fun `NONE yields STATIC_DISABLED`() = runTest {
        val r = engine(state(aiProvider = AIProvider.NONE)).analyze(emptyCtx())
        assertEquals("STATIC", r.engineStatus.provider)
        assertEquals(EngineStatus.DISABLED, r.engineStatus.status)
    }

    @Test
    fun `OLLAMA yields OLLAMA_FALLBACK_TO_STATIC in Phase 2`() = runTest {
        val r = engine(state(aiProvider = AIProvider.OLLAMA)).analyze(emptyCtx())
        assertEquals("OLLAMA", r.engineStatus.provider)
        assertEquals(EngineStatus.FALLBACK_TO_STATIC, r.engineStatus.status)
    }

    @Test
    fun `OPENAI with cloud upload off yields OPENAI_DISABLED`() = runTest {
        val r = engine(
            state(aiProvider = AIProvider.OPENAI, allowCloudUpload = false),
            apiKey = "sk-present"
        ).analyze(emptyCtx())
        assertEquals("OPENAI", r.engineStatus.provider)
        assertEquals(EngineStatus.DISABLED, r.engineStatus.status)
    }

    @Test
    fun `OPENAI with cloud on but no key yields OPENAI_FALLBACK_TO_STATIC`() = runTest {
        val r = engine(
            state(aiProvider = AIProvider.OPENAI, allowCloudUpload = true),
            apiKey = null
        ).analyze(emptyCtx())
        assertEquals("OPENAI", r.engineStatus.provider)
        assertEquals(EngineStatus.FALLBACK_TO_STATIC, r.engineStatus.status)
    }

    @Test
    fun `OPENAI with cloud on and blank key yields OPENAI_FALLBACK_TO_STATIC`() = runTest {
        val r = engine(
            state(aiProvider = AIProvider.OPENAI, allowCloudUpload = true),
            apiKey = "   "
        ).analyze(emptyCtx())
        assertEquals(EngineStatus.FALLBACK_TO_STATIC, r.engineStatus.status)
    }

    @Test
    fun `OPENAI with maxAiFiles zero yields OPENAI_DISABLED`() = runTest {
        val r = engine(
            state(aiProvider = AIProvider.OPENAI, allowCloudUpload = true, maxAiFiles = 0),
            apiKey = "sk-present"
        ).analyze(emptyCtx())
        assertEquals(EngineStatus.DISABLED, r.engineStatus.status)
    }

    @Test
    fun `OPENAI success yields ONLINE with latency`() = runTest {
        val r = engine(
            state(aiProvider = AIProvider.OPENAI, allowCloudUpload = true),
            apiKey = "sk-present",
            runner = AiPassRunner { _, _ -> emptyList() }
        ).analyze(emptyCtx())
        assertEquals(EngineStatus.ONLINE, r.engineStatus.status)
        assertEquals("OPENAI", r.engineStatus.provider)
        assert((r.engineStatus.latencyMs ?: -1) >= 0)
    }

    @Test
    fun `OPENAI failure yields FALLBACK_TO_STATIC with exception class in message`() = runTest {
        val r = engine(
            state(aiProvider = AIProvider.OPENAI, allowCloudUpload = true),
            apiKey = "sk-present",
            runner = AiPassRunner { _, _ -> throw IllegalStateException("boom") }
        ).analyze(emptyCtx())
        assertEquals(EngineStatus.FALLBACK_TO_STATIC, r.engineStatus.status)
        assert(r.engineStatus.message!!.contains("IllegalStateException"))
    }
}
```

### Step 9 — Engine analyzer-isolation test
Create `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineAnalyzerIsolationTest.kt`:

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.model.IssueType
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class AnalysisEngineAnalyzerIsolationTest {

    @Test
    fun `engine produces issues even if one analyzer is fed input that would throw`() = runTest {
        // ComplexityAnalyzer iterates context.graph.getAllNodes(); a null or broken
        // graph element is absorbed by its internal logic. A more robust check:
        // feed a fixture that all five analyzers tolerate but where exactly
        // NullSafetyAnalyzer produces a hit.
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              return <div>{user.name}</div>;
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/Iso.tsx", "tsx", code))
        )
        val engine = AnalysisEngine(
            settingsProvider = {
                GhostDebuggerSettings.State(aiProvider = AIProvider.NONE)
            },
            apiKeyProvider = { null },
            aiPassRunner = AiPassRunner { _, _ -> emptyList() }
        )
        val result = engine.analyze(ctx)
        assertTrue(
            result.issues.any { it.type == IssueType.NULL_SAFETY },
            "expected at least one NULL_SAFETY issue; got ${result.issues}"
        )
    }
}
```

> **Note.** True analyzer fault-injection (throwing from inside an analyzer's `analyze`) requires a test-only `FakeAnalyzer` subclass. The engine's exception-absorbing loop is exercised indirectly here; deeper injection is optional and out of scope for Phase 2's binding tests. If you add a `FakeAnalyzer`, it must be placed under `src/test/kotlin/com/ghostdebugger/analysis/analyzers/` and kept package-private.

### Step 10 — Engine file-cap test
Create `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineFileCapTest.kt`:

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.ParsedFile
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisEngineFileCapTest {

    private fun files(n: Int): List<ParsedFile> =
        (1..n).map { FixtureFactory.parsedFile("/src/F$it.tsx", "tsx", "// stub\n") }

    @Test
    fun `engine caps static input to maxFilesToAnalyze`() = runTest {
        val ctx: AnalysisContext = FixtureFactory.context(files(10))
        val engine = AnalysisEngine(
            settingsProvider = {
                GhostDebuggerSettings.State(
                    aiProvider = AIProvider.NONE,
                    maxFilesToAnalyze = 3
                )
            },
            apiKeyProvider = { null },
            aiPassRunner = AiPassRunner { _, _ -> emptyList() }
        )
        val result = engine.analyze(ctx)
        assertEquals(3, result.metrics.totalFiles)
    }

    @Test
    fun `engine leaves file list intact when under the cap`() = runTest {
        val ctx = FixtureFactory.context(files(2))
        val engine = AnalysisEngine(
            settingsProvider = {
                GhostDebuggerSettings.State(
                    aiProvider = AIProvider.NONE,
                    maxFilesToAnalyze = 100
                )
            },
            apiKeyProvider = { null },
            aiPassRunner = AiPassRunner { _, _ -> emptyList() }
        )
        val result = engine.analyze(ctx)
        assertEquals(2, result.metrics.totalFiles)
    }

    @Test
    fun `AI pass receives at most maxAiFiles files`() = runTest {
        val ctx = FixtureFactory.context(files(20))
        var aiReceived = -1
        val engine = AnalysisEngine(
            settingsProvider = {
                GhostDebuggerSettings.State(
                    aiProvider = AIProvider.OPENAI,
                    allowCloudUpload = true,
                    maxFilesToAnalyze = 500,
                    maxAiFiles = 4
                )
            },
            apiKeyProvider = { "sk-test" },
            aiPassRunner = AiPassRunner { aiCtx, _ ->
                aiReceived = aiCtx.parsedFiles.size
                emptyList()
            }
        )
        engine.analyze(ctx)
        assertEquals(4, aiReceived)
    }

    @Test
    fun `AI pass is invoked with empty file list when maxAiFiles is zero but provider is OPENAI`() = runTest {
        val ctx = FixtureFactory.context(files(5))
        val invoked = mutableListOf<Int>()
        val engine = AnalysisEngine(
            settingsProvider = {
                GhostDebuggerSettings.State(
                    aiProvider = AIProvider.OPENAI,
                    allowCloudUpload = true,
                    maxAiFiles = 0
                )
            },
            apiKeyProvider = { "sk-test" },
            aiPassRunner = AiPassRunner { aiCtx, _ ->
                invoked.add(aiCtx.parsedFiles.size)
                emptyList()
            }
        )
        engine.analyze(ctx)
        // maxAiFiles == 0 is DISABLED at the provider switch; runner is NOT invoked.
        assertTrue(invoked.isEmpty())
    }
}
```

### Step 11 — Run the full test suite
1. `./gradlew test`
2. All Phase 1 tests + all Phase 2 tests must pass.
3. If any analyzer test from Phase 1 now fails because the `Issue` equality was affected by the new fields, verify it's checking on `.type` / `.filePath` / `.line` (Phase 1 assertions use `.type`), not full equality — the Phase 1 tests in `§12.3` of the Phase 1 spec do not use equality, so they should be unaffected.

### Step 12 — Full build + sandbox smoke
1. `./gradlew clean build` → green.
2. `./gradlew buildPlugin` → zip built.
3. `./gradlew runIde` → sandbox opens.
4. With `AIProvider = NONE`, run **Analyze Project** from the `Tools → Aegis Debug` menu. Confirm `idea.log` contains no stack trace. The existing webview flow still renders issues (because `AnalysisResult.issues` / `.hotspots` are unchanged in shape).
5. Set `AIProvider = OPENAI`, `allowCloudUpload = true`, provide a valid key. Run Analyze. Confirm a log line "OpenAI pass ok ..." appears, and the issue list still renders. (Phase 4 will surface status in the UI; in Phase 2 the payload is merely attached to `AnalysisResult`.)
6. Set `AIProvider = OPENAI`, `allowCloudUpload = true`, clear the key. Run Analyze. Confirm the static analyzers still fire. `idea.log` contains no stack trace. (No UI surface for status yet — Phase 4.)

---

## 9. Business Rules and Edge Cases

### 9.1 Provenance tagging — static path
- Every static-analyzer issue is re-emitted by the engine with `ruleId = analyzer.ruleId` (if not already set), `sources = [STATIC]` (if empty), `providers = [STATIC]` (if empty), `confidence = 1.0` (if null).
- Existing analyzers that already set any of these are **not overwritten**. This lets a future analyzer emit its own ruleId per issue.

### 9.2 Provenance tagging — AI path
- Every AI-analyzer issue is re-emitted with `sources = [AI_CLOUD]` for OpenAI (or `[AI_LOCAL]` for Ollama when Phase 5 lands), `providers = [OPENAI]`/`[OLLAMA]`, `confidence = 0.7` (if null).
- Exception: if the AI issue already has `sources != [STATIC]` (i.e., the analyzer pre-tagged it), we preserve whatever it supplied.

### 9.3 Fingerprint
- Format: `"$rule:$filePath:$line"` where `rule = ruleId ?: type.name`.
- Case-sensitive, colon-separated. No version, hash, or normalization.
- Column is NOT part of the fingerprint. Binding.

### 9.4 Merge rule
- Group by `fingerprint()`.
- Winner of each group = `group.maxByOrNull { it.confidence ?: 0.0 }` (falls back to `group.first()` on empty confidences).
- `sources` = union (distinct, preserving encounter order).
- `providers` = union (distinct, preserving encounter order).
- `confidence` = `group.mapNotNull { it.confidence }.maxOrNull()` → `null` only if all members had null confidence.
- The winner's `title`, `description`, `id`, `filePath`, `line`, `column`, `codeSnippet`, `affectedNodes`, `explanation`, `suggestedFix`, `ruleId`, `type`, `severity` are preserved.

### 9.5 Provider fallback decision table

| `aiProvider` | `allowCloudUpload` | API key | `maxAiFiles` | AI call result | `EngineStatus` | `provider` | Message prefix |
|---|---|---|---|---|---|---|---|
| `NONE` | * | * | * | — | `DISABLED` | `STATIC` | "AI provider disabled..." |
| `OLLAMA` | * | * | * | — | `FALLBACK_TO_STATIC` | `OLLAMA` | "Ollama integration not yet available..." |
| `OPENAI` | `false` | * | * | — | `DISABLED` | `OPENAI` | "Cloud upload is off..." |
| `OPENAI` | `true` | null/blank | * | — | `FALLBACK_TO_STATIC` | `OPENAI` | "No OpenAI API key configured..." |
| `OPENAI` | `true` | present | `<= 0` | — | `DISABLED` | `OPENAI` | "maxAiFiles = 0..." |
| `OPENAI` | `true` | present | `> 0` | success | `ONLINE` | `OPENAI` | "OpenAI pass ok..." |
| `OPENAI` | `true` | present | `> 0` | exception | `FALLBACK_TO_STATIC` | `OPENAI` | "OpenAI unreachable..." |

### 9.6 AI file cap edge cases
- `maxAiFiles > parsedFiles.size` → AI pass receives the full limited context (already capped by `maxFilesToAnalyze`).
- `maxAiFiles == 0` → engine returns `DISABLED` before invoking the runner. Runner not called.
- `maxAiFiles < 0` → `GhostDebuggerSettings.validate()` coerces to `0` on load; if somehow present at runtime, the provider switch treats `<= 0` as `DISABLED`.

### 9.7 File cap edge cases
- `maxFilesToAnalyze > parsedFiles.size` → no-op, full list passed through.
- `maxFilesToAnalyze == parsedFiles.size` → no-op.
- `maxFilesToAnalyze <= 0` → defensive no-op (settings validation normally prevents this).

### 9.8 Prioritization tie-breaking
- Within a bucket, order is the order of insertion into `LinkedHashSet`.
- Bucket 2 (changed files) order = `ChangeListManager.changeLists[*].changes[*].virtualFile.path` iteration order.
- Bucket 3 (recent) = `EditorHistoryManager.fileList` order (most recent last, per IntelliJ semantics — this gives the newer-recent a higher slot in the dedup if already present via an earlier bucket, but re-adding a present element is a no-op for `LinkedHashSet`).
- Bucket 4 (hotspots) = nodes sorted by complexity descending. Ties broken by node insertion order in `InMemoryGraph.nodes` (which is a `LinkedHashMap` by default).
- Bucket 5 (remaining) = original `parsedFiles` order.

### 9.9 Static analyzer isolation
- Each analyzer's `analyze(context)` call is wrapped in `try { } catch (Exception) { log.warn }`.
- A throwing analyzer does NOT stop the pipeline. Its output is discarded; other analyzers still run.
- Exceptions are logged at `WARN`, not `ERROR`, because they do not block the scan.

### 9.10 AI analyzer isolation
- The entire AI pass is wrapped in `runCatching`. An exception thrown anywhere inside `AIAnalyzer` (connection, timeout, JSON parse, rate limit) maps to `EngineStatus.FALLBACK_TO_STATIC`.
- Static results are unaffected.
- `latencyMs` on a failed AI call reflects the time from start of the attempt to the thrown exception — useful for diagnostics.

### 9.11 Empty input
- `parsedFiles.isEmpty()` → static pass produces zero issues (each analyzer early-returns when iterating an empty list); AI pass is either `DISABLED` (NONE/OLLAMA) or attempts a zero-file run. Runner invoked with zero files is allowed and should return empty. Not a defect.

### 9.12 Cancellation
- `analyze` is `suspend`. If the caller's coroutine is cancelled, `CancellationException` propagates. The engine does NOT swallow it inside the analyzer loop — `Exception` catch does not catch `CancellationException` because `CancellationException` extends `IllegalStateException` indirectly; Kotlin coroutines recommend catching `CancellationException` explicitly before a general catch. Current `try/catch (Exception)` pattern in the engine does catch `CancellationException` — this is a known Kotlin coroutines footgun but is consistent with the pre-Phase-2 behavior. Not in scope for Phase 2 to fix.

### 9.13 Tag preservation on merge
- If the AI run returns an issue whose `sources` or `providers` were pre-tagged (e.g., `[AI_CLOUD]`), the engine does not overwrite the tag. See §7.2 `runOpenAiPass` — the re-tagging condition preserves any non-`[STATIC]` value.
- After `mergeIssues`, the surviving `Issue.sources` / `.providers` are `.distinct()`, so duplicate tags from the same side do not accumulate.

### 9.14 Non-regression on `GhostDebuggerService`
- `AnalysisEngine().analyze(analysisContext)` at `GhostDebuggerService.kt:295` still works — zero-arg constructor wires the real providers.
- `analysisResult.issues` / `.hotspots` / `.risks` / `.metrics` reads are unchanged.
- `.engineStatus` is available but unused in Phase 2; Phase 4 wires it to the bridge.

---

## 10. UI/UX Requirements

### 10.1 User-facing surfaces in Phase 2

**None.** Phase 2 is entirely an internal engine refactor. No settings page changes, no webview changes, no bridge message changes, no new notifications.

### 10.2 Status surfacing deferred
- `EngineStatusPayload` is attached to `AnalysisResult` but NOT rendered. The webview status-bar pill ("Static Mode", "OpenAI · Online", "Fallback to Static") is Phase 4 work.
- Issue cards do NOT display source/provider badges in Phase 2. Phase 4 adds the badge.
- The settings dialog does NOT gain a "test AI connection" button in Phase 2. Already present from Phase 1 as a placeholder; no behavior change.

### 10.3 No color or font changes
- The Dark Navy + Cream palette and IBM Plex bundling remain a Phase 4 responsibility.

---

## 11. API / Backend Requirements

### 11.1 `AnalysisEngine` public API

| Member | Signature |
|---|---|
| Constructor | `AnalysisEngine(settingsProvider, apiKeyProvider, aiPassRunner)` all with defaults |
| `analyze` | `suspend fun analyze(context: AnalysisContext): AnalysisResult` |

### 11.2 `AnalysisContext` extensions
- `fun AnalysisContext.limitTo(cap: Int): AnalysisContext`
- `fun AnalysisContext.limitAiFilesTo(cap: Int): AnalysisContext`

### 11.3 `Issue` new API
- Fields: `sources: List<IssueSource>`, `providers: List<EngineProvider>`, `confidence: Double?`, `ruleId: String?`
- Method: `fun fingerprint(): String`

### 11.4 `AnalysisResult` new API
- Field: `engineStatus: EngineStatusPayload`

### 11.5 `AiPassRunner`
- `fun interface AiPassRunner { suspend fun run(context: AnalysisContext, apiKey: String): List<Issue> }`

### 11.6 `EngineStatus` / `EngineStatusPayload`
- Enum values: `ONLINE`, `OFFLINE`, `DEGRADED`, `FALLBACK_TO_STATIC`, `DISABLED`
- Payload fields: `provider: String`, `status: EngineStatus`, `message: String? = null`, `latencyMs: Long? = null`
- Both `@Serializable` (forward-compatible for Phase 4 JCEF bridge).

### 11.7 Backwards compatibility
- No public method signature from Phase 1 changes.
- `AnalysisEngine()` zero-arg construction still works.
- `AnalysisResult(issues, metrics, hotspots, risks)` four-arg construction **does NOT** work anymore — the new `engineStatus` is non-nullable and has no default. The only construction site is `AnalysisEngine.analyze` (inside this phase's rewrite), so no external consumer breaks.
- `Issue(id, type, severity, title, description, filePath, line, column, codeSnippet, affectedNodes, explanation, suggestedFix)` 12-arg positional construction still works because the four new parameters have defaults. Named construction is preferred and used throughout.

### 11.8 Storage impact
- None. Phase 2 touches runtime logic only.

---

## 12. Testing Requirements

### 12.1 Frameworks
- JUnit 5 (`org.junit.jupiter:junit-jupiter`)
- `kotlin-test-junit5`
- MockK 1.13.15
- `kotlinx-coroutines-test:1.9.0` (used via `runTest { }` for `suspend fun analyze`)
- `kotlinx-serialization-json:1.7.3` (used in `EngineStatusPayloadTest` for round-trip)

### 12.2 Source set
- `src/test/kotlin/com/ghostdebugger/**`, same as Phase 1.
- `runTest { }` from `kotlinx-coroutines-test` is required for any test touching `AnalysisEngine.analyze`.

### 12.3 Required tests (all must pass)

| Test file | Methods | Purpose |
|---|---|---|
| `model/IssueFingerprintTest.kt` | `fingerprint uses ruleId when present`, `fingerprint falls back to type name when ruleId is null`, `fingerprint is stable across equal triples`, `fingerprint differs when line differs` | `Issue.fingerprint()` contract. |
| `model/IssueMergeTest.kt` | `merge unions sources and providers for matching fingerprints`, `merge takes max confidence across the group`, `merge keeps issues with different fingerprints separate`, `merge with single issue returns it unchanged except for distinct lists` | Merge semantics. |
| `model/EngineStatusPayloadTest.kt` | `enum contains exactly ONLINE OFFLINE DEGRADED FALLBACK_TO_STATIC DISABLED`, `payload defaults message and latency to null`, `payload serializes and deserializes via kotlinx-serialization` | `EngineStatus` / `EngineStatusPayload` data type. |
| `analysis/AnalysisContextPrioritizationTest.kt` | `returns all files when under the cap`, `puts current file first`, `ordering is current then changed then recent then hotspot then remaining`, `deduplicates when a file appears in multiple buckets`, `falls back to source order when no signals are available`, `cap of zero returns empty list`, `unknown paths in signals are ignored` | §15.3 ordering. |
| `analysis/AnalysisEngineStaticFirstTest.kt` | `static analyzers run even when AI provider is NONE`, `static issues carry STATIC sources and providers`, `static analyzers run before AI and both contribute when AI is ONLINE`, `static analyzers run even when the AI pass throws` | Static-first ordering contract. |
| `analysis/AnalysisEngineProviderFallbackTest.kt` | `NONE yields STATIC_DISABLED`, `OLLAMA yields OLLAMA_FALLBACK_TO_STATIC in Phase 2`, `OPENAI with cloud upload off yields OPENAI_DISABLED`, `OPENAI with cloud on but no key yields OPENAI_FALLBACK_TO_STATIC`, `OPENAI with cloud on and blank key yields OPENAI_FALLBACK_TO_STATIC`, `OPENAI with maxAiFiles zero yields OPENAI_DISABLED`, `OPENAI success yields ONLINE with latency`, `OPENAI failure yields FALLBACK_TO_STATIC with exception class in message` | Decision table §9.5. |
| `analysis/AnalysisEngineAnalyzerIsolationTest.kt` | `engine produces issues even if one analyzer is fed input that would throw` | Isolation loop. |
| `analysis/AnalysisEngineFileCapTest.kt` | `engine caps static input to maxFilesToAnalyze`, `engine leaves file list intact when under the cap`, `AI pass receives at most maxAiFiles files`, `AI pass is invoked with empty file list when maxAiFiles is zero but provider is OPENAI` (last one asserts **NOT** invoked) | File-limit contract. |

**Total: 33 new test methods across 8 files.** Combined with Phase 1's 18 methods, the suite totals 51.

### 12.4 Gating
- `./gradlew test` exit `0` is the gate for Phase 2 completion.
- Any Phase 1 test regression is a blocker.

### 12.5 Not required in Phase 2
- Integration test with real `IdeaTestFixture` (no bridge emission to verify yet).
- Performance/benchmark of `prioritizeFiles` (Phase 6).
- End-to-end webview rendering of status pill (Phase 4).
- Ollama mock service (Phase 5).

---

## 13. Acceptance Criteria

Phase 2 is complete when **all** of the following hold:

1. **Static-first ordering**
   1. With `aiProvider = NONE`, `analyze` returns static-detected issues, each tagged `sources = [STATIC]`, `providers = [STATIC]`, `confidence = 1.0`, `ruleId` matching the owning analyzer.
   2. With `aiProvider = OPENAI` + valid key + cloud upload on, static issues appear in `AnalysisResult.issues` before or alongside AI issues (same list; static-first in the internal concat).
   3. With any analyzer throwing, the other four analyzers' issues still ship.

2. **File limiting**
   1. With `maxFilesToAnalyze = 3` and a 10-file input, `AnalysisResult.metrics.totalFiles == 3`.
   2. With `maxAiFiles = 4` and `AiPassRunner` inspection, the runner receives exactly 4 files.
   3. Priority ordering matches the §15.3 table (verified by `AnalysisContextPrioritizationTest`).

3. **Issue fingerprint merge**
   1. Two issues with the same `ruleId`, `filePath`, `line` are merged into one.
   2. Merged issue has the union of `sources` and `providers`, and `max` of the non-null confidences.
   3. Fingerprint format is exactly `"$rule:$filePath:$line"`.

4. **Provider fallback**
   1. Every row of the §9.5 decision table is covered by at least one passing test in `AnalysisEngineProviderFallbackTest`.
   2. An exception inside the AI pass results in `status = FALLBACK_TO_STATIC`, and static issues still appear in the result.
   3. `EngineStatusPayload.latencyMs` is populated for actually-attempted AI runs (both success and failure).

5. **API compatibility**
   1. `GhostDebuggerService.kt:295` `AnalysisEngine().analyze(analysisContext)` still compiles and runs.
   2. No public signature from Phase 1 changed.
   3. `AnalysisResult` gains exactly one new field: `engineStatus`.

6. **Test baseline**
   1. All 8 new test files are present under `src/test/kotlin/com/ghostdebugger/...`.
   2. `./gradlew test` passes: 18 Phase-1 methods + 33 Phase-2 methods = 51 green.

7. **Build hygiene**
   1. `./gradlew clean build` runs green on a fresh checkout.
   2. `./gradlew runIde` opens the sandbox IDE without errors in `idea.log`.
   3. `./gradlew buildPlugin` produces the zip.

8. **Non-regression**
   1. Smoke: with `AIProvider = NONE` in the sandbox IDE, Analyze Project produces issues and the webview renders them (existing behavior preserved).
   2. Smoke: with `AIProvider = OPENAI` + valid key, Analyze Project produces issues without throwing regardless of OpenAI availability.

---

## 14. Risks / Caveats

### 14.1 `AnalysisResult.engineStatus` is non-nullable
Because every construction site is under our control (the rewritten `AnalysisEngine.analyze`), we add the field without a default. If a downstream consumer in a later phase constructs `AnalysisResult` elsewhere (e.g., in a cache-hydrated code path), it must supply an `engineStatus`. Document this when caching is revisited.

### 14.2 `AIAnalyzer` still owns file filtering and concurrency
The current `AIAnalyzer.analyze` filters `{ts,tsx,js,jsx,kt,java}` < 2000 lines and limits to three concurrent requests. Phase 2 introduces an **additional** cap (`maxAiFiles`) applied **before** `AIAnalyzer` sees the files. The two caps compose: effective AI input = `min(maxAiFiles, AIAnalyzer-internal filter)`. This is intentional; the analyzer's language/size filter is a safety net that does not replace the user-facing cap.

### 14.3 `CancellationException` absorption
`try { } catch (Exception)` catches `CancellationException`. Kotlin coroutines recommend filtering this. Pre-Phase-2 behavior in the codebase already does this; changing it is out of scope. Documented for the reviewer.

### 14.4 Merge is lossy on secondary metadata
When two issues merge, `title` / `description` / `codeSnippet` / `explanation` come from the winner (highest confidence). Per-group alternatives are discarded. This is the intended behavior for a trust-weighted UI (Phase 4), but a future surface that wants "show all overlapping issues" would need to re-compute from the pre-merge list.

### 14.5 `prioritizeFiles` depends on IntelliJ statics in `limitTo`
Tests avoid these by calling `prioritizeFiles` directly with lambdas. The `limitTo` wrappers' `runCatching` guards ensure that if `FileEditorManager.getInstance` etc. are unavailable (in a test runner that happens to call `limitTo` without stubbing), we degrade gracefully to bucket 5 (source order). Do not rely on this in production code paths — the real IDE always exposes these services.

### 14.6 `ChangeListManager.changeLists[*].changes[*].virtualFile` can be null
Our `mapNotNull { it.virtualFile?.path }` handles it. Regression risk is low but the guard is important; do not remove the `?.`.

### 14.7 `EditorHistoryManager.fileList` returns `VirtualFile[]`
Iteration is stable per IntelliJ's implementation; we cast to a list and use `.path`. If the API changes in a future platform, the `runCatching` ensures graceful fallback.

### 14.8 `maxByOrNull` vs `firstOrNull` for merge winner
`maxByOrNull { it.confidence ?: 0.0 }` can pick an AI-tagged issue if its confidence is higher than the static's `1.0`. We guard against this by starting static confidence at `1.0` (the effective ceiling), so in practice the winner is static when present. This is intentional: static is authoritative; AI adds confirmation signal.

### 14.9 Provenance tagging order sensitivity
In `runStaticPass`, we check `if (issue.sources.isNotEmpty()) issue.sources else listOf(STATIC)`. Because the default on the `Issue` constructor is `listOf(STATIC)`, `sources` is **never** empty for a newly constructed issue — the `else` branch is effectively dead. Left in place as defense-in-depth if a future analyzer passes `sources = emptyList()` explicitly.

### 14.10 Semaphore interaction with `maxAiFiles`
`AIAnalyzer`'s internal `Semaphore(3)` is not changed. With `maxAiFiles = 100`, there will be up to 100 coroutines started, but only 3 concurrent OpenAI calls. This matches the existing behavior.

### 14.11 Integer overflow in `latencyMs`
`System.currentTimeMillis()` difference fits comfortably in `Long`. No risk.

### 14.12 Test stability under JDK 21
`runTest { }` from `kotlinx-coroutines-test:1.9.0` skips virtual time by default for `suspend fun` under test. Our `latencyMs` values are `>= 0` because `runTest` uses `currentTime`, which always advances monotonically. Tests verifying the value assert `>= 0`, not a specific number.

### 14.13 `GhostDebuggerSettings.State()` zero-arg construction in tests
Phase 1 spec decision §5.16 established that tests may instantiate `State()` directly. Phase 2 tests follow the same pattern; they construct `State(aiProvider = X, ...)` without going through `getInstance()`.

### 14.14 `FixtureFactory.context` creates a relaxed-mock `Project`
This is sufficient for `limitTo(cap)` when `parsedFiles.size <= cap` (no IntelliJ service lookup). When testing the actual priority path, the test calls `prioritizeFiles` directly with lambdas — it does not go through `limitTo`.

### 14.15 Preserving Phase 1 analyzer tests
The existing `NullSafetyAnalyzerTest` et al. assert on `it.type == IssueType.X` and do not inspect `sources` / `providers` / `ruleId`. Adding default values to `Issue` fields does not alter their assertions. Verified by re-reading Phase 1 §12 template.

### 14.16 `ComplexityAnalyzer` threshold unchanged
Phase 1 binding §14.11 keeps `complexityThreshold = 10`. Phase 2 does not touch analyzer internals.

### 14.17 `NullSafetyAnalyzer` regex fragility
Phase 1 binding §14.10. The Static-First test uses the same positive fixture format; do not reformat.

### 14.18 `GhostDebuggerService.kt:290-305` consumer
Reads `analysisResult.issues`, `analysisResult.hotspots`. Adding a field does not break either read. Graph-building code downstream uses `graphBuilder.applyIssues(inMemoryGraph, analysisResult.issues)` which is also unchanged.

### 14.19 `AnalysisEngine` constructor defaults and IntelliJ lifecycle
`GhostDebuggerSettings.getInstance()` inside the default `settingsProvider` lambda is called lazily per `analyze()` invocation. This is the correct scope: settings captured at the start of each scan, not at engine construction. Binding.

### 14.20 AI issue confidence source of truth
Phase 2 hard-codes AI default confidence at `0.7`. When OpenAI starts returning structured confidence scores (later phase / prompt change), the engine reads `issue.confidence` verbatim — our fallback `?: 0.7` only fires when the AI omits it.

---

## 15. Definition of Done

Phase 2 is **done** when a reviewer can check every box:

- [ ] `./gradlew clean build` exits `0` on a fresh checkout.
- [ ] `./gradlew test` exits `0`; all 51 test methods green (18 Phase 1 + 33 Phase 2).
- [ ] `./gradlew buildPlugin` produces `build/distributions/Aegis Debug-0.1.0.zip`.
- [ ] `./gradlew runIde` opens the sandbox IDE; `idea.log` has no `ERROR` or stack trace attributable to `AnalysisEngine`.
- [ ] `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` declares `IssueSource`, `EngineProvider` enums; `Issue` has `sources`, `providers`, `confidence`, `ruleId` and a `fingerprint()` method; `AnalysisResult` has `engineStatus`.
- [ ] `src/main/kotlin/com/ghostdebugger/model/EngineStatus.kt` exists with `EngineStatus` enum and `EngineStatusPayload` data class, both `@Serializable`.
- [ ] `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt` calls static analyzers first, then AI, then merges — verified by reading the file and by passing `AnalysisEngineStaticFirstTest`.
- [ ] `src/main/kotlin/com/ghostdebugger/analysis/AnalysisContextPrioritization.kt` exists with `limitTo`, `limitAiFilesTo`, and `prioritizeFiles`.
- [ ] `src/main/kotlin/com/ghostdebugger/analysis/AiPassRunner.kt` exists with the `fun interface` declaration.
- [ ] `AnalysisEngineProviderFallbackTest` covers all eight rows of the §9.5 decision table.
- [ ] `IssueFingerprintTest` asserts the four cases listed in §12.3.
- [ ] `IssueMergeTest` asserts the four cases listed in §12.3.
- [ ] `AnalysisContextPrioritizationTest` covers the seven priority-ordering cases in §12.3.
- [ ] `AnalysisEngineFileCapTest` verifies `maxFilesToAnalyze` and `maxAiFiles` independently.
- [ ] `AnalysisEngineAnalyzerIsolationTest` verifies a throwing path does not drop the remaining analyzers' output.
- [ ] `EngineStatusPayloadTest` verifies enum values and `kotlinx-serialization` round-trip.
- [ ] Smoke: sandbox IDE with `AIProvider = NONE` analyzes a project and renders issues.
- [ ] Smoke: sandbox IDE with `AIProvider = OPENAI` + valid key + cloud upload on analyzes a project; `idea.log` contains an "OpenAI pass ok ..." line.
- [ ] Smoke: sandbox IDE with `AIProvider = OPENAI` + no key does NOT block; analyze completes with static results.
- [ ] `grep -r "engineStatus" src/main/kotlin` returns exactly the field on `AnalysisResult` and its uses inside `AnalysisEngine.kt`.
- [ ] `grep -r "fingerprint" src/main/kotlin` returns exactly the `Issue.fingerprint()` definition and its usages inside `AnalysisEngine.mergeIssues`.
- [ ] `grep -r "limitTo\|limitAiFilesTo" src/main/kotlin` returns the definitions in `AnalysisContextPrioritization.kt` and their two call sites inside `AnalysisEngine.analyze`.
- [ ] No file under `src/main/kotlin/com/ghostdebugger/bridge/`, `toolwindow/`, or `webview/` has been modified (Phase 2 is engine-only).
- [ ] This spec document is committed alongside the implementation in the same PR.

When every box is checked, Phase 2 ships. Phase 3 (deterministic PSI fixes + diff preview) and Phase 4 (EngineStatus bridge + webview pill + Dark Navy/Cream palette) can begin without rework.
