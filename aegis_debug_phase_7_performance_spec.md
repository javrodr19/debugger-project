# Phase 7 Implementation Spec: Aegis Debug — Performance & Resource Footprint

**Version:** 1.0 (binding)
**Status:** Ready for immediate implementation — blocks V1 Marketplace submission
**Source of Truth:** `aegis_debug_true_v1_spec.md`
**Target Phase from Source Spec:** Post-GA hardening (not in original roadmap — added because V1 plugin consumes ~12 GB RAM and saturates CPU on a 16 GB / 8-core reference machine, rendering the IDE unusable)
**Prior Phase:** `aegis_debug_v1_remediation_spec.md` — merged

---

## 1. Objective

The V1 plugin is functionally complete but resource-incorrect. On the reference machine (16 GB RAM, 8 cores), opening a mid-size TS/TSX project makes the plugin consume ~12 GB RSS and 100 % CPU, freezing the IDE and the host OS within seconds. This is a **ship-blocker**. Marketplace reviewers will reject a plugin that makes IntelliJ unresponsive; users will uninstall it within one session.

This phase delivers the performance and resource fixes that bring Aegis Debug into the range expected of a well-behaved JetBrains plugin. After Phase 7 ships:

- Peak plugin RAM during full analysis of a 1 000-file TypeScript project must stay **under 500 MB** on the reference machine.
- Idle RAM (no analysis running, tool window open) must stay **under 150 MB**.
- Single full-project analysis of the reference 1 000-file project must complete in **under 30 s** on 4 cores, with CPU use spiking but not saturating all cores indefinitely.
- Re-analysis after a single-file edit must complete in **under 3 s** and must not rebuild the full project graph.
- Auto-refresh on save must be bounded, debounced, and cancellable — never pile up, never regress into "analyzes forever while the user types."
- The AI cache must be bounded (LRU) so a long editing session cannot drift upward in RAM.

Scope is **performance and correctness of resource use only**. No new analyzers, no new fixers, no new bridge events, no new UI. The contract between backend and webview is preserved.

The issues addressed below were discovered by reading the five analyzers, `SymbolExtractor`, `GraphBuilder`, `AnalysisEngine`, `GhostDebuggerService.performAnalysis`, `GhostDebuggerService.scheduleAutoRefresh`, `GhostDebuggerService.reanalyzeFile`, `AICache`, `InMemoryGraph`, and `JcefBridge` as-of 2026-04-15.

---

## 2. Scope

### 2.1 In Scope

| # | Area | Work |
|---|---|---|
| S1 | Regex hoisting | Every `Regex(...)` currently constructed inside a `forEachIndexed` / nested loop is lifted to a `companion object` or top-of-class `private val`. Compilation happens once per JVM, not once per line per file. Affects: `NullSafetyAnalyzer`, `StateInitAnalyzer`, `AsyncFlowAnalyzer`, `SymbolExtractor` (all 10 language branches), `GraphBuilder.estimateComplexity`. |
| S2 | Single-pass line splitting | Each `ParsedFile` gains a computed/cached `lines: List<String>` exposed once. Analyzers stop calling `file.content.lines()` independently. Measured cost today: on a 500-file project, `lines()` is called ~3 500 times per analysis (5 analyzers + extractor + builder + complexity + AI pre-filter). Target: 1 call per file per analysis. |
| S3 | Parallel static pass | `AnalysisEngine.runStaticPass` runs the five analyzers in parallel via `coroutineScope { analyzers.map { async { … } }.awaitAll() }` on `Dispatchers.Default`. Per-analyzer exception handling is preserved; cancellation is honored per analyzer. |
| S4 | Index `parsedFiles` by path once | `ComplexityAnalyzer`'s `context.parsedFiles.firstOrNull { it.path == node.filePath }` is replaced with a `Map<String, ParsedFile>` built once at `AnalysisContext` creation. Today O(files × nodes); after fix O(nodes). |
| S5 | Single `findCycles()` call per analysis | The graph's strongly-connected-component scan runs in `CircularDependencyAnalyzer.analyze`, then runs **again** inside `InMemoryGraph.toProjectGraph`. Cache the result on `InMemoryGraph` (cleared by `clear()` and any `addNode`/`addEdge`) so the second call is a lookup. |
| S6 | Drop `ParsedFile.content` after static pass | After all analyzers have produced issues, `AnalysisEngine` replaces each `ParsedFile.content` with an empty string before returning the `AnalysisResult`. Downstream consumers (UI, bridge, graph) never read `content` again; they use `codeSnippet` (already captured in issues). Saves the largest single RAM source — file bodies — for the lifetime of `currentIssues`. |
| S7 | Strip `FunctionSymbol.body` to at most 120 chars | `SymbolExtractor` stores the entire trimmed line as `body` on every extracted function. On TSX components with 40 functions per file this multiplies raw bytes. Truncate at extraction time. `body` is UI-only and not consumed by analyzers. |
| S8 | Bounded LRU `AICache` | Add `maxEntries: Int = 256` (configurable via settings). Switch from `ConcurrentHashMap` to `Collections.synchronizedMap(LinkedHashMap(..., accessOrder = true))` with an eviction hook. Existing TTL behavior preserved; LRU and TTL apply together. |
| S9 | Remove explanation pre-fetch loop on every analysis | `GhostDebuggerService.performAnalysis` lines 399–417 fire three AI `explainIssue` calls at the end of every analysis, even when the user never clicks the issue. On a cloud backend this is 3 HTTP round-trips per analysis. Delete the block — explanations are already fetched lazily on `handleNodeClicked` and `handleFixRequested`. |
| S10 | Auto-refresh debounce and self-edit suppression | Bump the debounce on `scheduleAutoRefresh` from 2 s to **7 s**. Add a flag `suppressNextRefresh` on `GhostDebuggerService` that `handleApplyFixRequested` sets before writing, so the watcher ignores events generated by our own fix application (today this causes a fix → save → full re-analysis loop). Also drop the "relevant change" check down to only files whose path matches an extension in `FileScanner.supportedExtensions` (today it fires on every write to a source-rooted file, including `.log`, `.json`, etc.). |
| S11 | Single-file reanalysis must NOT rebuild the graph | `GhostDebuggerService.reanalyzeFile` currently builds an `InMemoryGraph` twice (lines 624–630 and 635–638) and re-sends `sendGraphData(updatedGraph)` — the entire project graph JSON — to the webview even when only one node's issues changed. Replace with: `bridge.sendNodeUpdate(nodeId, newStatus)` + `bridge.sendIssueUpdate(nodeId, issues)` (new one-file bridge sender). Bridge diff, not graph dump. |
| S12 | Do not `DaemonCodeAnalyzer.restart()` unconditionally | `performAnalysis` calls `DaemonCodeAnalyzer.getInstance(project).restart()` at the end of every analysis, which forces the IDE to reannotate all open files — even ones unrelated to our plugin. Gate this behind "issues changed since last analysis" (compute `currentIssues.map { it.fingerprint() }.toSet()` and compare). |
| S13 | Annotator O(1) per-file lookup | `GhostDebuggerAnnotator.collectInformation` filters the flat `currentIssues` list on every annotation pass. Replace with a cached `Map<String, List<Issue>>` keyed by normalized file path, invalidated whenever `currentIssues` is reassigned. Measured cost today: O(total_issues) per open file per daemon tick; target: O(issues_in_file). |
| S14 | Remove dead-language branches from `SymbolExtractor` | `SymbolExtractor.extract` dispatches on 10 languages (ts/tsx/js/jsx/kt/java/py/go/rs/cs/rb/swift/php); but `FileScanner.supportedExtensions` is 6 (kt/java/ts/tsx/js/jsx). The 6 Python/Go/Rust/C#/Ruby/Swift/PHP branches are unreachable dead code. Delete them. No functional change — the outer `when` falls through to `else -> parsedFile` which would already be the case. |
| S15 | Cancel on tool window close | Plugin currently never cancels analysis when the user closes the Aegis Debug tool window. Add a `ToolWindowManagerListener` that cancels the active analysis + the auto-refresh debounce job when the tool window is hidden. Opening it again triggers a fresh analysis only if the user explicitly clicks **Analyze**. |
| S16 | Lower default caps and publish them | Drop `maxFilesToAnalyze` default from 500 → **300** and `maxAiFiles` default from 100 → **40**. These are the most common cause of "plugin ate my machine" reports. Users who need more can raise them. |
| S17 | Tests | Seven new tests (one per heavy change) plus a perf smoke test that asserts a 1 000-file synthetic project completes analysis in under 30 s on the CI runner. |

### 2.2 Out of Scope (strict)

- Rewriting any analyzer to PSI-based AST analysis. Regex-on-lines is the V1 architecture and the fixer PSI-validity post-check handles safety. PSI migration is a post-V1 refactor.
- Incremental dependency-graph diffing (add/remove a single edge without rebuilding the graph section). Current reanalyzeFile keeps the existing graph — S11 just removes the redundant *copy* and webview push.
- Caching parser output across analyses. Parsing is already fast relative to analyzer regex cost; optimizing parsing before analyzers is solving the wrong problem.
- Changing the webview's React reconciliation. The webview is fine; the backend is shipping too much data to it.
- Migrating coroutines from `Dispatchers.Default` to a custom dispatcher pool. The default pool is correctly sized; the problem is work units, not the dispatcher.
- Rewriting `JcefBridge` to a binary transport. JSON is fine if the payloads are small — S11 makes them small.
- Adding telemetry or a performance-metrics panel. No observability work in this phase.
- Supporting non-JVM / non-web languages. `SymbolExtractor`'s dead language branches are deleted (S14), not "kept for later."
- Changing the public `Analyzer` / `Fixer` SPI. Parallelization (S3) is a call-site change; `analyze(context): List<Issue>` stays the same.
- Any work on the AI prompt size or streaming. The AI timeout and cache bounds are the only AI-layer concerns here.
- Reducing `kotlinx-coroutines-*` dependencies or tuning the JVM heap. The perf problem is algorithmic, not VM config.

---

## 3. Non-Goals

The following MUST NOT be touched:

1. **No change to the `Analyzer` or `Fixer` SPI signatures.** `analyze(context): List<Issue>` and `Fixer.generateFix(issue, content): CodeFix?` are stable.
2. **No change to `IssueType`, `IssueSeverity`, `IssueSource`, `EngineProvider`, `NodeStatus`, or `EdgeType` enum values.**
3. **No change to any bridge event name** (`APPLY_FIX`, `CANCEL_ANALYSIS`, etc.) or payload shape received by the webview.
4. **No change to `GhostDebuggerSettings` field names.** Renaming a persisted-state field breaks every installed user's config. Additive fields are allowed (S8, S16 add no new fields — only change defaults).
5. **No change to `@Storage("ghostdebugger.xml")`.**
6. **No change to the `com.ghostdebugger` package tree, the plugin ID, or the tool window ID.** Same reasons as the V1 remediation spec.
7. **No change to `plugin.xml` action IDs or keyboard shortcuts.**
8. **No change to `build.gradle.kts` dependencies.** The fix is code, not library churn.
9. **No removal of the `XDebuggerManager` / `XDebugSessionListener` wiring.** Debug overlays are a Phase 4 feature and remain untouched.
10. **No change to the `Task.Backgroundable` wrapper.** `Task.Backgroundable` is the correct primitive; we just stop abusing it with `runBlocking`-held threads by making the inner work faster, not by changing the task type.
11. **No CHANGELOG entry until Phase 7 ships.** Marketing wording is a post-implementation concern.

---

## 4. Implementation Decisions

Derived from the profiling pass on 2026-04-15 and binding for Phase 7.

| # | Decision | Rationale |
|---|---|---|
| D1 | Regexes in `NullSafetyAnalyzer`, `StateInitAnalyzer`, `AsyncFlowAnalyzer`, `SymbolExtractor`, and `GraphBuilder.estimateComplexity` are lifted to `companion object` or class-level `private val`. Per-variable-name regexes (e.g. `Regex("""\b$varName\.([\w]+)""")` in `NullSafetyAnalyzer`) stay inside the loop but are **only compiled once per unique varName per file** via a small `HashMap<String, Regex>`. | Kotlin `Regex(pattern)` compiles a Pattern. In tight loops this is the single biggest CPU hit. Hoisting is lossless. |
| D2 | `ParsedFile` gains `val lines: List<String>` computed lazily via `by lazy { content.lines() }`. Every analyzer, `SymbolExtractor`, `GraphBuilder`, and `ComplexityAnalyzer` reads `file.lines` instead of calling `file.content.lines()`. | One allocation per file per analysis, not one per analyzer per file. |
| D3 | `AnalysisEngine.runStaticPass` becomes `suspend` and uses `coroutineScope { analyzers.map { async(Dispatchers.Default) { runOne(it) } }.awaitAll() }` with per-analyzer try/catch preserved. `indicator.text2` updates move into each `async` block. Cancellation is checked at the top of each analyzer's coroutine. | Five analyzers with disjoint state run independently — safe to parallelize. On a 4-core machine this is a 3–4× wall-clock win on the static pass. |
| D4 | `AnalysisContext` gains `val filesByPath: Map<String, ParsedFile> by lazy { parsedFiles.associateBy { it.path } }`. `ComplexityAnalyzer` consults `context.filesByPath[node.filePath]`. | O(1) lookup per node; constructor for the map is a single pass. |
| D5 | `InMemoryGraph` gains a private `@Volatile var cachedCycles: List<List<String>>? = null` that `findCycles()` populates and returns. `addNode`, `addEdge`, `updateNode`, and `clear` all invalidate the cache (`cachedCycles = null`). `toProjectGraph` calls `findCycles()` normally and gets the cached result. | DFS cycle detection is O(V+E) but not free. Running it twice per analysis is waste. |
| D6 | After `runStaticPass` and `runAiPass` complete inside `AnalysisEngine.analyze`, every `ParsedFile` in the returned `AnalysisContext` has its `content` replaced with `""` and its `lines` lazy reset. The `content` field becomes `var` (additive; was `val`). | File bodies are the single largest RAM retainer once analysis is done. They are never used after the final `mergeIssues` call — issue objects carry their own `codeSnippet` (already a 3–5 line slice). Typical saving: 500 files × 40 KB each = 20 MB → near-zero, and the retained lifetime was "forever" before. For 5 000-file projects this is the difference between 200 MB and 2 GB resident. |
| D7 | `SymbolExtractor` truncates `FunctionSymbol.body` to `trimmed.take(120)`. If a function's signature line is longer than 120 chars it is truncated with `…` appended. | The body field exists for debugging and future UI use; it's never parsed. 120 chars is more than any real function signature. |
| D8 | `AICache` becomes an LRU with default `maxEntries = 256`. Internal storage: `Collections.synchronizedMap(object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) { override fun removeEldestEntry(eldest) = size > maxEntries })`. TTL check on read is unchanged. | Unbounded ConcurrentHashMap is a memory-leak-by-design. 256 entries × a few KB each = stable ~1 MB ceiling for the cache. |
| D9 | `GhostDebuggerService.performAnalysis` — the final "pre-fetch top-3 critical explanations" block (lines 399–417) is deleted. The service's `handleNodeClicked` and `handleFixRequested` already fetch explanations lazily on demand. | This block runs 3 AI HTTP calls after every analysis for issues the user may never look at. On cloud backends the user pays token costs for this. On local Ollama it pins CPU/GPU for 10+ seconds of "why does my fan spin every time I click Analyze?" |
| D10 | `scheduleAutoRefresh` debounce changes from 2 000 ms to 7 000 ms. The `VirtualFileManager.VFS_CHANGES` filter gets a second check: `FileScanner.supportedExtensions` (re-exposed as `internal val SUPPORTED_EXTENSIONS: Set<String>` on a companion object) must contain the file's extension. `GhostDebuggerService` also gains `@Volatile private var suppressUntil: Long = 0L`. `handleApplyFixRequested` sets `suppressUntil = System.currentTimeMillis() + 3000` immediately before calling `fixApplicator.apply`. The VFS listener ignores events if `System.currentTimeMillis() < suppressUntil`. | Three problems, three fixes: (a) 2 s is too aggressive — users type faster than that and never finish. (b) writes to `.log`, `.json`, `.md` inside the source tree shouldn't trigger re-analysis. (c) our own fix writes currently loop back through the watcher and trigger a full re-analysis, which is what `reanalyzeFile` is supposed to handle instead. |
| D11 | `reanalyzeFile` is rewritten: no graph copy, no `sendGraphData`. Flow becomes: re-parse one `VirtualFile`, run all five analyzers against a single-file `AnalysisContext` whose `graph` field is the **existing** `currentGraph`'s `InMemoryGraph` (no copy, no re-add), replace that file's issues in `currentIssues`, update the corresponding `GraphNode.issues` and `GraphNode.status` in place, and emit two messages to the webview: `bridge.sendNodeUpdate(nodeId, newStatus)` + a new `bridge.sendIssuesForFile(filePath, issues)`. If analysis fails, fall back to full `analyzeProject()`. | Eliminates two `InMemoryGraph` copies and one full `ProjectGraph` JSON serialization per fix-apply. Fix-apply latency drops from ~2 s to ~100 ms on 1 000-file projects. |
| D12 | `performAnalysis` tracks a `lastIssueFingerprints: Set<String>` field. After `currentIssues = result.issues`, compute the new set; only if it differs from the previous set does `DaemonCodeAnalyzer.restart()` fire. | Editing a function comment triggers re-analysis, which previously always triggered full reannotation of every open file. Now unchanged issue sets → no daemon restart. |
| D13 | `GhostDebuggerService` gains `@Volatile private var issuesByFile: Map<String, List<Issue>> = emptyMap()` updated every time `currentIssues` is reassigned. `GhostDebuggerAnnotator.collectInformation` consults `service.issuesByFile[normalizedPath]` in O(1) instead of filtering the flat list. | Daemon annotation runs every few hundred ms on every open file. O(issues) × open_files × daemon ticks was measurable CPU; O(1) lookup is free. |
| D14 | `SymbolExtractor.extract`'s `when` branch loses the `"py", "go", "rs", "cs", "rb", "swift", "php"` arms; the `else` branch returns `parsedFile` unchanged, which was already the dead-language behavior. All seven `extractFrom*` private functions are deleted. | `FileScanner` cannot produce those extensions. Dead code removal only. |
| D15 | A `ToolWindowManagerListener` is registered by `GhostDebuggerToolWindowFactory` for the tool window id `GhostDebugger`. On `stateChanged` where the window transitions to hidden, the listener calls `GhostDebuggerService.getInstance(project).cancelAnalysis()` and cancels the `autoRefreshJob`. | User closing the panel is a strong signal that they don't want background work to continue. Today analysis keeps running even with the panel closed. |
| D16 | `GhostDebuggerSettings.State` defaults change: `maxFilesToAnalyze = 300` (was 500), `maxAiFiles = 40` (was 100). `validate()` is untouched — existing users' saved values keep their settings; only new installs get the lower defaults. | Lower defaults match the 99 % user, who will never need 500 files in a single AI pass. Users whose projects exceed 300 files can opt in to a larger cap. |
| D17 | `JcefBridge` gains `fun sendIssuesForFile(filePath: String, issues: List<Issue>)` emitting `window.__aegis_debug__.onIssuesForFile(...)` with a JSON payload of `{filePath, issues}`. The webview's `appStore` gains a matching reducer `ISSUES_FOR_FILE`. No other bridge changes. | Needed by S11/D11 to avoid full-graph dumps. |
| D18 | Every `async` block under `Dispatchers.Default` uses `ensureActive()` as the first statement and after each heavy inner loop, not just `progress?.checkCanceled()`. | Coroutine-level cancellation is strictly more robust than indicator-level only and costs nothing. |
| D19 | `runBlocking` inside `Task.Backgroundable.run` is kept (it's the correct primitive for bridging a suspend API to a blocking `ProgressIndicator.run` callback), but everything inside `performAnalysis` is carefully suspending so the blocked thread is the IDE worker only for the duration of the actual work. No `runBlocking` inside analyzers; no `runBlocking` inside the bridge senders. | `runBlocking` is not inherently bad; nested `runBlocking` or `runBlocking` around I/O would be. Current usage is correct once inner work is fast (S3 + S4 + S5 + S6). |

---

## 5. Decisions Made From Ambiguity

Where the profiling pass left choices open, this section records the path taken and why:

1. **Regex hoisting vs. compile-time `Pattern` constants.** Chosen: Kotlin `Regex` `val` in a `companion object`. Idiomatic Kotlin, identical runtime behavior to static `Pattern`. A `Pattern` rewrite would drag `java.util.regex` imports into every analyzer for zero additional win.

2. **Parallel analyzers via `coroutineScope` + `async` vs. `IO` dispatcher vs. Java `ForkJoinPool`.** Chosen: `coroutineScope { async(Dispatchers.Default) { … } }`. Default dispatcher is CPU-bounded (size = `max(2, availableProcessors)`), which is exactly what analyzer work wants. IO dispatcher would oversubscribe CPU. `ForkJoinPool` wouldn't integrate with `ProgressIndicator.checkCanceled()`.

3. **Dropping `ParsedFile.content` vs. keeping a weak reference.** Chosen: drop to empty string. A `WeakReference<String>` would be correct but adds complexity; `codeSnippet` on each `Issue` already captures the ~5 lines around each finding, which is the only thing the UI ever renders.

4. **LRU cache size of 256 vs. 1 000 vs. user-configurable.** Chosen: 256, and expose it on `GhostDebuggerSettings.State` as `aiCacheMaxEntries: Int = 256` (new field — additive). Users with very large projects using AI heavily can raise it. 256 matches typical request volumes per editing session.

5. **Auto-refresh debounce: 5 s vs. 7 s vs. 10 s.** Chosen: **7 s**. Shorter than a typical "thinking" pause between saves; longer than the "save immediately, save again, save again" burst during refactors. Empirically matches how IntelliJ's own indexer waits before re-indexing.

6. **Self-edit suppression window: 1 s vs. 3 s vs. 5 s.** Chosen: 3 s. Fix apply + document save usually completes within 500 ms; 3 s absorbs slow disks without being long enough to hide a real subsequent edit the user makes.

7. **Suppress daemon restart on unchanged issues vs. always restart.** Chosen: suppress on unchanged. The only cost is one `Set` comparison per analysis (cheap); the saved cost is IDE-wide daemon restart (expensive).

8. **Cancel analysis on tool-window close vs. keep running.** Chosen: cancel. Mirrors user intent. Users who want background analysis after closing the panel can pin the analyze action to the Tools menu and leave the panel closed — the plugin still runs from the action.

9. **Delete dead `SymbolExtractor` branches vs. leave them for possible future languages.** Chosen: delete. Dead code rots; future language support will be explicit in a future phase with tests, not quietly-dormant branches.

10. **Changing defaults vs. adding a "Safe Mode" preset.** Chosen: change defaults. Presets introduce a new setting surface; lowering numeric defaults is invisible to 99 % of users but rescues the 1 % who open the plugin on a huge project and expect sane behavior.

11. **`sendIssuesForFile` event name vs. reusing `sendGraphData`.** Chosen: new event. Reusing `sendGraphData` with a partial payload would require schema discrimination on the webview side and a new special-case reducer; a named event is clearer and the webview change is one line per side.

12. **Whether to add an `issuesByFile` cache on the service or push it into the annotator.** Chosen: on the service. The annotator is a lightweight class with no lifecycle for cache invalidation; the service already owns `currentIssues` state and can invalidate atomically.

13. **Keep `maxAiFiles = 0` semantics.** Chosen: unchanged. `0` still means "skip AI pass entirely." The default just drops from 100 to 40.

14. **Should parallel static pass preserve analyzer output order?** Chosen: no. Analyzers produce independent issue sets that are merged by fingerprint at the end (`mergeIssues`). Output order in the final list was never guaranteed; UI sorts by severity/file anyway.

15. **Whether to add a test that asserts total RAM < 500 MB.** Chosen: no — RAM assertions in unit tests are flaky across CI runners. Instead, the perf smoke test asserts wall-clock time on a synthetic 1 000-file fixture; the RAM target is documented as a manual-verification acceptance criterion (§9 item 7).

---

## 6. Files

### 6.1 Files to Modify

| # | Path | Change |
|---|---|---|
| M1 | `src/main/kotlin/com/ghostdebugger/analysis/analyzers/NullSafetyAnalyzer.kt` | Hoist `useStateNullRegex` and `varNullRegex` to `companion object`. Replace per-varName `directAccessRegex` compilation with a cached `HashMap<String, Regex>` scoped to the `analyze` call. Switch `file.content.lines()` to `file.lines`. |
| M2 | `src/main/kotlin/com/ghostdebugger/analysis/analyzers/StateInitAnalyzer.kt` | Hoist `useStateNoArgRegex` and the per-varName `iterationRegex` pattern to `companion object` (use pattern string, compile once per varName via a local map). Switch to `file.lines`. |
| M3 | `src/main/kotlin/com/ghostdebugger/analysis/analyzers/AsyncFlowAnalyzer.kt` | Hoist `fetchReturnPattern` and `setIntervalPattern` to `companion object`. Switch to `file.lines`. |
| M4 | `src/main/kotlin/com/ghostdebugger/analysis/analyzers/ComplexityAnalyzer.kt` | Replace `context.parsedFiles.firstOrNull { it.path == node.filePath }` with `context.filesByPath[node.filePath]`. |
| M5 | `src/main/kotlin/com/ghostdebugger/analysis/analyzers/CircularDependencyAnalyzer.kt` | No logic change; cycles are now cached on `InMemoryGraph` (M10), so the existing `context.graph.findCycles()` call gets the cached result. |
| M6 | `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt` | `runStaticPass` becomes `private suspend fun`; body uses `coroutineScope { analyzers.map { async(Dispatchers.Default) { runOne(it, context, indicator) } }.awaitAll().flatten() }`. Extract the existing per-analyzer try/catch + source/provider tagging into `runOne`. After `mergeIssues`, iterate `context.parsedFiles` and null-out `content` (change `ParsedFile.content` to `var` — see M13). |
| M7 | `src/main/kotlin/com/ghostdebugger/analysis/analyzers/AIAnalyzer.kt` | Replace `it.content.lines().size < 2000` with `it.lines.size < 2000`. |
| M8 | `src/main/kotlin/com/ghostdebugger/parser/SymbolExtractor.kt` | Hoist every regex to `companion object`. Delete the Python/Go/Rust/C#/Ruby/Swift/PHP `extractFrom*` functions and their `when` arms. Truncate `body = trimmed` to `body = trimmed.take(120)` on every `FunctionSymbol` construction. |
| M9 | `src/main/kotlin/com/ghostdebugger/graph/GraphBuilder.kt` | Hoist the 12 complexity-regex patterns in `estimateComplexity` to a `companion object val COMPLEXITY_PATTERNS: List<Regex>`. |
| M10 | `src/main/kotlin/com/ghostdebugger/graph/InMemoryGraph.kt` | Add `@Volatile private var cachedCycles: List<List<String>>? = null`. Wrap `findCycles()` body in `cachedCycles?.let { return it }; … compute … ; cachedCycles = result; return result`. Invalidate in `addNode`, `addEdge`, `updateNode`, `clear`. |
| M11 | `src/main/kotlin/com/ghostdebugger/ai/AICache.kt` | Change `cache` to a `Collections.synchronizedMap(object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) { … })` with `removeEldestEntry` returning `size > maxEntries`. Constructor takes `maxEntries: Int = 256`. |
| M12 | `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` | (a) Delete the "pre-fetch top 3 explanations" block at the end of `performAnalysis` (currently lines 399–417). (b) Bump `delay(2000)` in `scheduleAutoRefresh` to `delay(7000)`. (c) Add `@Volatile var suppressUntil: Long = 0L`. In `handleApplyFixRequested` set `suppressUntil = System.currentTimeMillis() + 3000` before calling `fixApplicator.apply`. In the VFS listener, short-circuit `if (System.currentTimeMillis() < suppressUntil) return`. Also gate the listener on `FileScanner.SUPPORTED_EXTENSIONS`. (d) Add `@Volatile private var lastIssueFingerprints: Set<String> = emptySet()`; gate `DaemonCodeAnalyzer.restart()` on fingerprint set change. (e) Add `@Volatile private var issuesByFile: Map<String, List<Issue>> = emptyMap()`; update whenever `currentIssues` is reassigned via a single `updateIssues(newIssues)` helper. (f) Rewrite `reanalyzeFile` per D11 — no graph copies, emit `sendNodeUpdate` + `sendIssuesForFile` instead of `sendGraphData`. |
| M13 | `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` | Change `ParsedFile.content: String` from `val` to `var`. Add `val lines: List<String> by lazy { content.lines() }` on `ParsedFile`. Add `val filesByPath: Map<String, ParsedFile> by lazy { parsedFiles.associateBy { it.path } }` on `AnalysisContext`. Add `aiCacheMaxEntries: Int = 256` to `GhostDebuggerSettings.State` (additive field; `validate()` gets a clamp `if (aiCacheMaxEntries <= 0) aiCacheMaxEntries = 256`). |
| M14 | `src/main/kotlin/com/ghostdebugger/parser/FileScanner.kt` | Expose `supportedExtensions` as `internal val SUPPORTED_EXTENSIONS` on a new `companion object`, keep the existing field as a thin alias for backward-compat. |
| M15 | `src/main/kotlin/com/ghostdebugger/annotator/GhostDebuggerAnnotator.kt` | In `collectInformation`, replace `service.currentIssues.filter { … }` with `service.issuesByFile[normalizedPath] ?: emptyList()`. |
| M16 | `src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt` | Add `fun sendIssuesForFile(filePath: String, issues: List<Issue>)` mirroring `sendGraphData`'s pattern. Uses `json.encodeToString` for the payload. |
| M17 | `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt` | `maxFilesToAnalyze` default `500` → `300`. `maxAiFiles` default `100` → `40`. Existing persisted values unchanged (PersistentStateComponent preserves them). |
| M18 | `src/main/kotlin/com/ghostdebugger/toolwindow/GhostDebuggerToolWindowFactory.kt` | Register `project.messageBus.connect(disposable).subscribe(ToolWindowManagerListener.TOPIC, …)`. On `stateChanged` where `toolWindowManager.getToolWindow("GhostDebugger")?.isVisible == false`, call `GhostDebuggerService.getInstance(project).cancelAnalysis()`. |
| M19 | `src/main/kotlin/com/ghostdebugger/ai/AIServiceFactory.kt` | Pass `settings.aiCacheMaxEntries` through to `AICache` constructor (when instantiated inside `OpenAIService` / `OllamaService`). |
| M20 | `src/main/kotlin/com/ghostdebugger/ai/OpenAIService.kt` and `OllamaService.kt` | Accept `cacheMaxEntries: Int = 256` parameter, pass to `AICache` constructor. |
| M21 | `webview/src/bridge/pluginBridge.ts` | Add `onIssuesForFile(handler)` subscription following the existing `onGraphUpdate` pattern. |
| M22 | `webview/src/stores/appStore.ts` | Add `ISSUES_FOR_FILE` reducer that replaces issues for a given file path in the existing state without rebuilding the entire issue list. |

### 6.2 Files to Create

| # | Path | Purpose |
|---|---|---|
| C1 | `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEnginePerfSmokeTest.kt` | Synthesizes 1 000 small TSX files with a mix of null-safety/state-init/async-flow triggers. Asserts full analysis completes in under 30 s on the CI runner. Skips if `System.getenv("CI_PERF") != "1"` so local runs don't flake. |
| C2 | `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineParallelStaticPassTest.kt` | Verifies all five analyzers are invoked, their issue sets merged by fingerprint, and that a failure in one analyzer does not prevent the other four from producing results. |
| C3 | `src/test/kotlin/com/ghostdebugger/ai/AICacheLruEvictionTest.kt` | Inserts 300 entries into a cache with `maxEntries = 256`, asserts `cache.size() == 256`, asserts the 44 oldest-accessed entries are evicted. |
| C4 | `src/test/kotlin/com/ghostdebugger/graph/InMemoryGraphCycleCacheTest.kt` | Asserts `findCycles()` computes once and returns the cached result on subsequent calls, and that `addEdge` invalidates the cache. |
| C5 | `src/test/kotlin/com/ghostdebugger/GhostDebuggerServiceFileWatcherTest.kt` | Asserts: (a) the watcher ignores writes to extensions outside `SUPPORTED_EXTENSIONS`; (b) the watcher ignores writes during `suppressUntil`; (c) two rapid writes within the debounce window produce one re-analysis, not two. |
| C6 | `src/test/kotlin/com/ghostdebugger/GhostDebuggerServicePartialReanalysisTest.kt` | Asserts `reanalyzeFile` does NOT call `bridge.sendGraphData` (uses a spy bridge) and DOES call `bridge.sendIssuesForFile`. |
| C7 | `src/test/kotlin/com/ghostdebugger/model/ParsedFileLinesCachingTest.kt` | Asserts `ParsedFile.lines` computes once (calls `content.lines()` once per instance) and is consulted multiple times without recomputation. |

### 6.3 Files to Leave Untouched

| Path | Why untouched |
|---|---|
| `build.gradle.kts` | No dependency changes. Perf is code, not libraries. |
| `src/main/resources/META-INF/plugin.xml` | No new extensions, no new actions, no manifest changes. |
| `README.md` / `CHANGELOG.md` / `DATA_HANDLING.md` | Docs are updated after Phase 7 ships, not during. |
| Three fixer classes + `FixApplicator.kt` + `FixerRegistry.kt` | Fixers are not a hot path. |
| `bridge/UIEvent.kt`, `bridge/UIEventParser.kt` | No new inbound events. |
| `AnalysisContextPrioritization.kt` | Prioritization is correct; it only runs when cap is hit. Not a perf issue. |
| `PartialReanalyzer.kt` | The class stays for the reanalyzeFile fallback path; M12 only changes the *caller* in `GhostDebuggerService`. |
| All existing Phase 1–6 test classes | Must continue to pass unchanged. |

---

## 7. Data Contracts / Interfaces / Schemas

**Changes limited to additive, backward-compatible extensions.**

- `ParsedFile` adds `lines: List<String>` (lazy). `content` changes `val` → `var`. Not `@Serializable`, not persisted — internal-only.
- `AnalysisContext` adds `filesByPath: Map<String, ParsedFile>` (lazy). Not persisted.
- `GhostDebuggerSettings.State` adds `aiCacheMaxEntries: Int = 256`. Persisted via existing `@Storage("ghostdebugger.xml")` mechanism. Existing user configs load with the default.
- `JcefBridge` adds `sendIssuesForFile(filePath, issues)`. New event name `onIssuesForFile` on the webview side.
- `InMemoryGraph` adds private `cachedCycles` field. No public API change.
- `GhostDebuggerService` adds `issuesByFile`, `lastIssueFingerprints`, `suppressUntil` — all private or `@Volatile` internal state; not exposed in any bridge payload.
- `FileScanner` adds `SUPPORTED_EXTENSIONS` as a public-internal companion constant. The instance-level `supportedExtensions` stays as a thin alias.

**No wire-protocol break.** A webview running pre-Phase-7 code against a post-Phase-7 backend ignores the unknown `onIssuesForFile` calls (JavaScript `undefined` function guard already in every bridge send via `window.__aegis_debug__ && window.__aegis_debug__.onX(...)`). A post-Phase-7 webview against a pre-Phase-7 backend never receives `onIssuesForFile` and falls back to the existing `onGraphUpdate` path — but this mixed-version scenario does not ship; backend and webview are bundled in the same JAR.

**No persisted-state migration.** Every new field is additive with a default.

---

## 8. Test Plan

### 8.1 New tests (all must pass)

- **C1 `AnalysisEnginePerfSmokeTest`** — 1 000 synthetic TSX files, asserts wall-clock under 30 s. Environment-gated.
- **C2 `AnalysisEngineParallelStaticPassTest`** — spy analyzers, assert all five ran, one throwing does not prevent others.
- **C3 `AICacheLruEvictionTest`** — insert 300, size stays at 256, oldest evicted.
- **C4 `InMemoryGraphCycleCacheTest`** — `findCycles` called twice returns same instance; `addEdge` invalidates.
- **C5 `GhostDebuggerServiceFileWatcherTest`** — extension filter + suppressUntil + debounce coalescing.
- **C6 `GhostDebuggerServicePartialReanalysisTest`** — spy bridge, verify `sendGraphData` NOT called, `sendIssuesForFile` IS called.
- **C7 `ParsedFileLinesCachingTest`** — computes once, reused.

### 8.2 Existing tests that must continue to pass unchanged

- All analyzer unit tests (`NullSafetyAnalyzerTest`, `NullSafetyAnalyzerSingleResponsibilityTest`, `StateInitAnalyzerTest`, `AsyncFlowAnalyzerTest`, `CircularDependencyAnalyzerTest`, `ComplexityAnalyzerTest`) — the regex-hoisting refactor is lossless; same inputs produce same outputs.
- All fixer tests (`NullSafetyFixerTest`, `StateInitFixerTest`, `AsyncFlowFixerTest`, `FixerContractTest`, `FixApplicatorValidityTest`).
- All AI service tests (`AIServiceFactoryTest`, `OllamaServiceParseTest`, `OpenAIServiceTimeoutTest`). The LRU change to `AICache` preserves the existing get/put/TTL contract.
- Phase 6 hardening tests (`AnalysisEngineCancellationTest`, `ApplyFixEventTest`, `EngineStatusBridgeTest`).
- `FixerRegistryTest`, `CodeFixExtensionTest`, and the remediation test `NullSafetyAnalyzerSingleResponsibilityTest`.

### 8.3 Manual verification on the reference machine (16 GB / 8-core)

1. Open a 1 000-file TypeScript project. Trigger **Analyze Project**. Observe:
   - Peak plugin RAM (via `jcmd <pid> VM.native_memory` or IntelliJ's built-in memory panel) stays **under 500 MB**.
   - CPU spikes to near 100 % for under 20 s, then drops to idle. OS remains responsive throughout.
   - Tool window renders results within 30 s total.
2. Idle-state check: leave the tool window open with no edits for 5 minutes. RAM should plateau (not climb), CPU should be near-zero.
3. Edit a single TSX file, save, wait 10 s. Observe:
   - Debounce fires once (not twice).
   - `reanalyzeFile` updates only the edited file's node in the UI — other nodes untouched.
   - No full graph JSON sent (verify via IDE log).
4. Apply a deterministic fix. Observe:
   - Fix applied within 1 s.
   - No runaway re-analysis loop from the self-edit (the suppression window ignores our own write).
5. Close the Aegis Debug tool window during analysis. Observe analysis cancels within 2 s.
6. With AI provider set to OpenAI (cloud) and a 100-file project, run analysis 10 times in a row. Observe:
   - No RAM climb across runs (LRU cache bounded).
   - Only 40 files (new default `maxAiFiles`) sent to AI, not 100.

---

## 9. Acceptance Criteria

Phase 7 is complete when all of the following hold on the reference machine:

1. Peak plugin RAM during a 1 000-file TSX analysis **< 500 MB**.
2. Idle plugin RAM (tool window open, no analysis running) **< 150 MB**.
3. Full analysis of the 1 000-file synthetic fixture completes in **< 30 s** on the CI runner (perf smoke test `AnalysisEnginePerfSmokeTest` passes with `CI_PERF=1`).
4. Single-file re-analysis after a fix completes in **< 3 s** and does not emit a `sendGraphData` call (verified by `GhostDebuggerServicePartialReanalysisTest`).
5. `./gradlew clean build test` passes green with zero test regressions.
6. The IDE remains responsive throughout analysis on a 4-core machine — no more than 1 core pinned for more than 5 continuous seconds.
7. `AICache` size stabilizes at `maxEntries` (default 256) and never exceeds it regardless of session length.
8. Auto-refresh fires **at most once per 7 s window** per edit session and never fires for writes to non-supported extensions.
9. Closing the Aegis Debug tool window during analysis cancels the active analysis within 2 s.
10. No `Regex(...)` constructor appears inside any `forEachIndexed`, `forEach`, or nested-loop body in `analysis/analyzers/*.kt`, `parser/SymbolExtractor.kt`, or `graph/GraphBuilder.kt`.
11. `SymbolExtractor.extract` contains exactly 3 language branches: `ts/tsx/js/jsx`, `kt`, `java` (plus `else -> parsedFile`).
12. `ParsedFile.content` is `var` and empty after `AnalysisEngine.analyze` returns, verified by reading `result`'s captured parsed files.
13. `DaemonCodeAnalyzer.restart()` is called **only** when the set of issue fingerprints changed since the previous analysis.
14. `GhostDebuggerAnnotator.collectInformation` runs in O(1) with respect to total `currentIssues` count, verified by benchmark.

---

## 10. Execution Order

Performance edits compose; order minimizes risk of breaking tests while the work lands.

1. **M13** (`AnalysisModels.kt`): add `lines` lazy, `filesByPath` lazy, `aiCacheMaxEntries`, flip `content` to `var`. Pure data-model, can't break anything.
2. **M14** (`FileScanner.kt`): expose `SUPPORTED_EXTENSIONS`. Pure addition.
3. **M11** (`AICache.kt`): switch to LRU. C3 test validates.
4. **M10** (`InMemoryGraph.kt`): cycle caching. C4 test validates.
5. **M8** (`SymbolExtractor.kt`): regex hoist + dead-language removal + body truncation.
6. **M9** (`GraphBuilder.kt`): complexity regex hoist.
7. **M1–M3** (three analyzers): regex hoist + `file.lines`. Existing analyzer unit tests validate correctness.
8. **M4** (`ComplexityAnalyzer.kt`): `filesByPath` lookup.
9. **M7** (`AIAnalyzer.kt`): `file.lines` switch.
10. **M6** (`AnalysisEngine.kt`): parallel static pass + drop `content` at end. C2 test validates.
11. **M16** (`JcefBridge.kt`): new `sendIssuesForFile`. No tests yet — webview reducer lands after.
12. **M21–M22** (webview): add `onIssuesForFile` handler and reducer.
13. **M15** (`GhostDebuggerAnnotator.kt`): O(1) lookup via service's `issuesByFile`.
14. **M12** (`GhostDebuggerService.kt`): the big one — delete explanation pre-fetch, bump debounce, add suppression, add fingerprint gate, add `issuesByFile` state, rewrite `reanalyzeFile`. C5, C6 tests validate.
15. **M18** (`GhostDebuggerToolWindowFactory.kt`): cancel-on-close listener.
16. **M17** (`GhostDebuggerSettings.kt`): lower defaults.
17. **M19, M20** (AI service factory + services): pass `cacheMaxEntries` through.
18. **C1, C7**: remaining new tests.
19. Run full `./gradlew test` — must be green.
20. Manual verification on the reference machine (§8.3). If any acceptance criterion in §9 fails, the offending M-item is the rollback target.

---

## 11. Rollback

Each M-item is a local, self-contained edit with test coverage. If a given edit causes regression:

- **M1–M9 (regex hoisting / dead-code removal)**: `git revert`; analyzer output is unchanged, so only perf regresses.
- **M10 (cycle cache)**: revert; double-call returns.
- **M11 (LRU cache)**: revert; cache becomes unbounded again.
- **M12 (service changes)**: revert; pre-fetch loop returns, debounce drops to 2 s, self-edit loop returns. Most impactful of all, so test C5/C6 coverage before merging.
- **M13 (model)**: revert; but downstream M-items that depend on `lines` / `filesByPath` / `aiCacheMaxEntries` must revert together.
- **M18 (tool-window listener)**: revert; analysis keeps running after panel close. Harmless.

No user-data migration. No persisted-state schema bump (the new `aiCacheMaxEntries` field is additive with a default; removing it on rollback just means existing installs fall back to the default). No bridge contract break (`sendIssuesForFile` is additive; removing it just means the old full-graph path runs again).

---

## 12. Out-of-Band Checks

Before running the manual verification in §8.3, confirm:

- `jstat -gc <pid>` shows no sustained Old-gen growth across 10 consecutive analyses. A steady-state heap after GC is the clearest evidence that retained references are correctly released (S6, S8).
- `jstack <pid>` during idle shows no `runBlocking`-blocked worker threads. Analysis must be truly idle when not running.
- IntelliJ's `Help → Collect Performance Snapshot` during analysis shows no Aegis Debug call on the EDT (event-dispatch thread) for more than 100 ms. All heavy work stays off the EDT.

---

## 13. Summary

V1 is feature-complete but resource-incorrect: 12 GB RAM and 100 % CPU render the plugin unshippable regardless of how good the analysis is. Phase 7 is a focused, surgical performance pass — no new features, no API changes, no UI changes — that brings the plugin into the resource envelope expected of a JetBrains Marketplace plugin. Twenty-two file modifications, seven new tests, one session of profiling and benchmarking. After Phase 7 merges and the §9 acceptance criteria pass on the reference machine, V1 ships.

— End of spec —
