# Phase 8 Implementation Spec: Aegis Debug — Stability, Webview Robustness & Performance Gaps

**Version:** 1.0 (binding)
**Status:** Ready for immediate implementation — blocks V1 Marketplace submission
**Source of Truth:** `aegis_debug_true_v1_spec.md`
**Target Phase from Source Spec:** Post-GA hardening (not in original roadmap — added because Phase 7 shipped partially applied AND a new webview regression makes **Analyze Project** render a blank dark-navy screen, compounding the CPU saturation the phase was meant to cure)
**Prior Phase:** `aegis_debug_phase_7_performance_spec.md` — partially merged; gaps enumerated in §2 below

---

## 1. Objective

The V1 plugin remains unshippable for two independent reasons, both discovered when the user tried the build produced by commit `7d43064` ("refactor: optimize analysis performance …"):

1. **Clicking "Analyze Project" turns the entire webview into a solid Dark Navy background with no content.** Root cause: `webview/src/components/neuromap/NeuroMap.tsx:45,65` call a function named `buildGroupedNodes` that is **never defined** in the module (only `buildInitialNodes` exists, at line 27, and it is unused / tree-shaken out). When the graph payload arrives, React mounts `<NeuroMap />`, evaluation hits `buildGroupedNodes(graph.nodes)`, throws `ReferenceError`, and because `App.tsx` has no `ErrorBoundary`, React unmounts the whole tree. The empty `#root` reveals `body { background-color: var(--bg-base); }` which is `#0A1128` — exactly the solid color the user reported. The bundle was shipped because `webview/package.json`'s `"build": "vite build"` does not chain the existing `"lint": "tsc --noEmit"` step; TypeScript's error about the missing reference was never surfaced during release.

2. **Full-project analysis still saturates CPU at 100 %** on the reference machine (16 GB / 8 cores) for the lifetime of the run. Phase 7's regex hoisting, cache bounds, content drop, cycle memoization, and auto-refresh debounce all landed, but the two heaviest items **did not**:
   - **M6 / D3 (parallel static pass)** — `AnalysisEngine.runStaticPass` at line 92 is still a sequential `for (analyzer in analyzers)` loop. The five analyzers run one after another instead of concurrently on `Dispatchers.Default`. On 4+ cores this is the dominant wall-clock cost and the reason the `java` worker pins one core for the full analysis duration.
   - **M18 / D15 (cancel on tool-window close)** — `GhostDebuggerToolWindowFactory` never registers a `ToolWindowManagerListener`. Closing the Aegis Debug panel mid-analysis leaves `performAnalysis` running in the background indefinitely, keeping both the `java` process and the `jcef_helper` process pinned (the latter because the webview never unmounts the React tree while hidden).
   
   Compounding this, the Ollama pass at `AnalysisEngine.runOllamaPass:222` is a sequential `flatMap` over `aiContext.parsedFiles` — up to 40 consecutive HTTP calls with a 30 s timeout each. When the local Ollama server is slow, this serializes ~20 minutes of I/O-bound work on one coroutine.

   Several `project.messageBus.connect()` calls in `GhostDebuggerService.kt` (lines 97 and 139) are unparented to any `Disposable`, so on plugin reload (dev mode) or project close these listeners leak and keep stale references to the previous service alive — a silent contributor to idle RAM creep.

Phase 8 delivers three concurrent fixes in one phase because they all ship together or not at all:

- **Webview must render.** A V1 plugin whose main action produces a blank screen is worse than a plugin with fewer features. A React `ErrorBoundary` plus a chained type-check in the build means this class of regression cannot quietly ship again.
- **Phase 7 must complete.** The two missing items are exactly the two items that matter most on the reference machine. Finishing M6 and M18 is the difference between "spikes to 100 % for ~20 s then idles" (Phase 7's target §9.6) and "pins a core for 60–90 s, plus background runaway if the panel is closed."
- **External surfaces must be safe.** The exported HTML report (`ReportGenerator`) currently interpolates unescaped issue titles, descriptions, code snippets, and file paths into its template. A single AI-generated description containing an HTML tag renders as markup; a malicious or accidentally-crafted path with `<script>` executes in the user's browser when they open the report. Also, the report is written into `project.basePath` by default, polluting the user's working tree. Both are one-line fixes that should ship before any user exports a report.

After Phase 8 ships:

- Clicking **Analyze Project** renders the NeuroMap graph, or, if any render-time error occurs, a visible error panel — never a blank body color.
- The webview build fails if TypeScript fails. No more silently-shipped reference errors.
- Full analysis of the reference 1 000-file project completes in **under 30 s** on 4 cores (Phase 7 target §9.3), with no core held for more than ~5 continuous seconds (Phase 7 target §9.6).
- Closing the tool window mid-analysis cancels the analysis within 2 s (Phase 7 target §9.9).
- The Ollama pass fan-out uses bounded concurrency (4 inflight), turning a serial 20-minute worst case into a ~5-minute worst case on slow local models, and a 30–60 s typical case.
- All `messageBus.connect()` calls are scoped to a `Disposable` so listener lifetime matches service lifetime.
- The HTML report cannot be weaponized via issue content, and the report file lands in the OS temp directory instead of the user's source tree.

Scope is **correctness, stability, and completion of Phase 7 only**. No new analyzers, no new fixers, no new bridge events, no new engine features, no UI redesign.

Every issue addressed below was discovered by re-reading `AnalysisEngine.kt`, `GhostDebuggerService.kt`, `GhostDebuggerToolWindowFactory.kt`, `ReportGenerator.kt`, `NeuroMap.tsx`, `App.tsx`, `webview/package.json`, and the Phase 7 spec as-of 2026-04-15 (commit `7d43064`).

---

## 2. Scope

### 2.1 In Scope

| # | Area | Work |
|---|---|---|
| S1 | **Fix webview ReferenceError** | `NeuroMap.tsx:45,65` call `buildGroupedNodes(graph.nodes)`; the function is not defined. Replace both call sites with `buildInitialNodes(graph.nodes)` (already defined at line 27 with the correct signature and grid layout). Remove no other code — the existing reconciliation logic around drag-position preservation stays. |
| S2 | **React ErrorBoundary** | New `webview/src/components/ErrorBoundary.tsx` — a minimal class component (React 19 still requires a class for `componentDidCatch` / `getDerivedStateFromError`). `App.tsx` wraps the `<NeuroMap>` / `<PixelCity>` / `<EmptyState>` canvas region with it. On error, render a small dark-themed card with the message and a "Reload" button that calls `window.location.reload()`. No network call, no telemetry — purely a visible fallback so the user can see the plugin crashed instead of thinking the IDE froze. |
| S3 | **Typecheck the webview build** | `webview/package.json` changes `"build": "vite build"` to `"build": "tsc --noEmit && vite build"`. The `"lint"` script stays as an alias for direct `tsc --noEmit` runs. Consequence: any TypeScript error (missing import, undefined reference, signature mismatch) fails the build instead of producing a silently broken bundle. |
| S4 | **Finish Phase 7 M6 — parallel static pass** | `AnalysisEngine.runStaticPass` at line 92 is currently a sequential `for (analyzer in analyzers)` loop. Rewrite it exactly as Phase 7 §4 D3 specified: `coroutineScope { analyzers.map { async(Dispatchers.Default) { runOne(it, context, indicator) } }.awaitAll().flatten() }` with a private `runOne(analyzer, context, indicator): List<Issue>` that preserves the existing per-analyzer `try/catch`, the `ruleId` / `sources` / `providers` / `confidence` tagging, and the `indicator.text2 = "Analyzer: ${analyzer.name}"` update (now per-async-block). Each `runOne` calls `ensureActive()` at top and `indicator.checkCanceled()` before tagging. |
| S5 | **Finish Phase 7 M18 — cancel on tool-window close** | `GhostDebuggerToolWindowFactory` currently only creates the content. Add a `Disposable` whose parent is the `Content` (so it disposes when the window content is disposed), and `project.messageBus.connect(disposable).subscribe(ToolWindowManagerListener.TOPIC, ...)`. In `stateChanged(toolWindowManager)`, read `toolWindowManager.getToolWindow("GhostDebugger")?.isVisible`. When the value transitions from `true` to `false`, call `GhostDebuggerService.getInstance(project).cancelAnalysis()`. Transition detection uses a local `var wasVisible: Boolean` captured by the listener. |
| S6 | **Bounded-concurrency Ollama fan-out** | `AnalysisEngine.runOllamaPass` currently fans out with `aiContext.parsedFiles.flatMap { file -> ... ollamaService.detectIssues(...) }` — fully sequential. Rewrite as: `val semaphore = Semaphore(OLLAMA_CONCURRENCY)` (companion constant `= 4`), then `coroutineScope { parsedFiles.map { file -> async(Dispatchers.IO) { semaphore.withPermit { indicator.checkCanceled(); indicator.text2 = "Ollama: ${file.path.substringAfterLast('/')}"; ollamaService.detectIssues(file.path, file.content) } } }.awaitAll().flatten() }`. Uses `Dispatchers.IO` (HTTP work, not CPU). 4 inflight is a conservative cap — Ollama local single-GPU instances usually serialize anyway, but a remote endpoint benefits from pipelined requests. Failure of any single file still fails the whole pass into the existing `onFailure` branch — preserved. |
| S7 | **Disposable-scoped listeners in `GhostDebuggerService`** | `GhostDebuggerService` implements `com.intellij.openapi.Disposable`. Its existing `dispose()` method already cancels `autoRefreshJob` and `scope`; add it to the `Disposable.dispose()` override. Register the service as a child of the project at construction: `Disposer.register(project, this)`. Replace both `project.messageBus.connect()` calls (at lines 97 and 139 of the current file) with `project.messageBus.connect(this)`. The `XDebugSessionListener` attached in `attachToDebugSession` stays — `XDebugSession.addSessionListener` has its own lifecycle bound to session end. |
| S8 | **HTML-escape the report** | `ReportGenerator.generateHTMLReport`, `buildIssuesList`, and `buildNodesOverview` currently interpolate raw `${issue.title}`, `${issue.description}`, `${issue.codeSnippet}`, `${issue.filePath}`, `${node.name}`, `${node.filePath}`, `${graph.metadata.projectName}` into the template. Add a private `fun htmlEscape(s: String): String` that replaces `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;`, `"` → `&quot;`, `'` → `&#39;`, and wrap every user-controlled interpolation with it. Fixed strings (CSS class names, `graph.metadata.healthScore`, numeric counts) are not wrapped. |
| S9 | **Move report out of `project.basePath`** | `GhostDebuggerService.handleExportReportRequested` currently writes to `File(project.basePath, "aegis-debug-report.html")` — pollutes the user's source tree and will show up as an untracked file in git status. Change to `File(System.getProperty("java.io.tmpdir"), "aegis-debug-${project.name}-${System.currentTimeMillis()}.html")`. The success toast message is updated to show the full temp path so users can find it. `Desktop.browse` still opens it in the default browser. |
| S10 | **Tests** | Four new backend tests + one webview test covering: parallel static pass concurrency, tool-window cancel behavior, report XSS escaping, report path location, and the ErrorBoundary render fallback. |

### 2.2 Out of Scope (strict)

- No redesign of the NeuroMap layout algorithm. A real "grouped by folder" layout is a future UX phase; here we restore the grid layout that already existed via `buildInitialNodes`.
- No change to the `Analyzer` SPI. S4 is a call-site parallelization only; `analyzer.analyze(context): List<Issue>` stays synchronous-looking and stateless-safe.
- No change to `OllamaService` or `OpenAIService` internals. S6 wraps them in bounded `async`; the services themselves are unchanged.
- No telemetry, no crash reporting, no remote logging. ErrorBoundary renders a local card and a reload button; nothing leaves the IDE.
- No migration of webview state management. The existing `useReducer` + `AppContext` stays.
- No webview UI redesign, no new components beyond `ErrorBoundary`.
- No change to bridge event names, payloads, or the `UIEvent` grammar.
- No change to persisted settings schema. Phase 7 already added `aiCacheMaxEntries`; Phase 8 adds none.
- No rewrite of `ReportGenerator` into a templating library. String interpolation + escape helper is sufficient.
- No change to `plugin.xml`, `build.gradle.kts`, or the plugin ID.
- No performance work beyond the two unfinished Phase 7 items plus the Ollama fan-out. Everything else in Phase 7 §2.1 is either already shipped or confirmed out of scope.
- No update to `CHANGELOG.md`, `README.md`, or marketing copy.
- No XDebugger refactor. Debug overlays stay as-is.
- No removal of React `StrictMode` from `main.tsx`. Double-effect in dev is correct; prod build strips it.

---

## 3. Non-Goals

The following MUST NOT be touched:

1. **No change to the `Analyzer`, `Fixer`, or `AIService` SPI.** Phase 7 already asserted this; Phase 8 upholds it.
2. **No change to any `IssueType`, `IssueSeverity`, `IssueSource`, `EngineProvider`, `NodeStatus`, or `EdgeType` enum value.**
3. **No change to any bridge event name** — `onGraphUpdate`, `onIssuesForFile`, `onAnalysisStart`, etc. stay byte-identical.
4. **No change to `GhostDebuggerSettings` field names, defaults, or storage key.** Phase 7 owned all settings changes.
5. **No change to the `com.ghostdebugger` package tree, the plugin ID, or the tool window ID `"GhostDebugger"`.**
6. **No change to `plugin.xml` action IDs, keyboard shortcuts, or `applicationService`/`projectService` registrations.**
7. **No change to `build.gradle.kts` dependencies.** All Phase 8 code uses APIs already on the classpath (`kotlinx.coroutines.sync.Semaphore` is in `kotlinx-coroutines-core`, already a dep; `com.intellij.openapi.Disposable` is IntelliJ Platform).
8. **No change to the `Task.Backgroundable` wrapper or `runBlocking` boundary.** Phase 7 decided this is correct; Phase 8 does not revisit it.
9. **No change to the webview's React, `@xyflow/react`, `framer-motion`, or `react-zoom-pan-pinch` versions.** `package.json` edit is to a script only, not a dependency.
10. **No change to the NeuroMap render layout, edge styling, or node shape.** Only the broken function reference is replaced.
11. **No new persisted state, no new settings UI, no new environment variables.**
12. **No CHANGELOG entry until Phase 8 ships.**

---

## 4. Implementation Decisions

Derived from the post-`7d43064` audit pass on 2026-04-15 and binding for Phase 8.

| # | Decision | Rationale |
|---|---|---|
| D1 | Replace `buildGroupedNodes(graph.nodes)` at `NeuroMap.tsx:45,65` with `buildInitialNodes(graph.nodes)`. Do not introduce a new layout function; do not rename `buildInitialNodes`. | The broken call was added in the most recent commit with no supporting implementation — there is no partially-built `buildGroupedNodes` elsewhere in the webview. The existing `buildInitialNodes` produces a deterministic 5-wide grid that works on every graph size; restoring the call restores the behavior users saw in the last green build. A grouped-by-folder layout is desirable but is a feature, not a fix; deferred to a future phase. |
| D2 | `ErrorBoundary` is a React **class** component with `componentDidCatch(error, info)` and `static getDerivedStateFromError(error)`. No functional-component equivalent exists in React 19. Render: a `<div>` with `--bg-elevated` background, `--error-text` border, the error message (truncated to 500 chars), and a single "Reload plugin" button calling `window.location.reload()`. | Functional components can't catch descendant render errors. A class wrapper is 40 lines and exactly the canonical pattern. Showing the error message (not just "something broke") gives users a reproducible bug report. |
| D3 | `webview/package.json` `"build": "tsc --noEmit && vite build"`. Left-to-right shell semantics means the vite step only runs if tsc passes. The `"lint"` script stays so developers can still run `tsc --noEmit` alone. | A build that can't fail on type errors is a build that will eventually ship a ReferenceError — as it did here. The cost of the added `tsc` pass is ~1–2 s on a cold cache, amortized away on warm runs. Gradle's `buildWebview` task chains `npm ci && npm run build`, so the new check runs wherever the current one runs. |
| D4 | `AnalysisEngine.runStaticPass` rewrite follows Phase 7 D3 verbatim, with one addition: the method's `indicator.text2` line (previously set once per analyzer in the outer `for`) moves inside each `async` block, so the status bar reflects whichever analyzer last started, not the one serially in progress. This is cosmetic; correctness is unchanged. | Phase 7 already documented and justified the parallelization; the only reason it's in Phase 8 is that the edit didn't land in `7d43064`. Copy-paste from the Phase 7 spec §4 D3. |
| D5 | Tool-window listener lives in `GhostDebuggerToolWindowFactory.createToolWindowContent`, scoped to a `Disposable` registered against the `Content` object (`Disposer.register(content, disposable)`). When IntelliJ disposes the `Content` (tool-window recreated, plugin unloaded, project closed), the listener automatically unsubscribes. The listener tracks visibility transitions with a captured `var wasVisible = toolWindow.isVisible`; only fires `cancelAnalysis()` on `true → false`. | Scoping to `Content` means the factory doesn't need its own `Disposable` lifecycle. Transition-detection avoids firing `cancelAnalysis` on every `stateChanged` event (which IntelliJ fires often, including on focus changes). |
| D6 | Ollama concurrency is `kotlinx.coroutines.sync.Semaphore(4)` inside a `coroutineScope { ... awaitAll() }`. Dispatcher is `Dispatchers.IO` (HTTP is I/O-bound; CPU dispatcher would starve the parallel static pass that uses it). On cancellation, `awaitAll()` propagates the `CancellationException` and the whole scope tears down — individual permits are released in `withPermit`'s `finally`. | 4 is below the typical number of CPU cores so it doesn't oversubscribe; it's above 1 so it gives meaningful pipelining on remote Ollama or fast local models. Semaphore (vs. `chunked(4)` with serial chunks) keeps permits saturated — no "wait for the slowest in the chunk before starting the next chunk." |
| D7 | `GhostDebuggerService` adds `: Disposable` to its class declaration. The existing `fun dispose()` becomes `override fun dispose()`. `init { Disposer.register(project, this) }`. Both `project.messageBus.connect()` calls become `project.messageBus.connect(this)`. The `fileWatcherRegistered` flag becomes unnecessary (listeners cannot double-register on the same service instance because construction happens once per service lifetime, but the flag is kept for defensive read-ordering and does no harm). | IntelliJ's project-level services are already singletons per project, and the platform disposes them when the project closes. Registering with `Disposer.register(project, this)` lets our service piggy-back on project disposal; `messageBus.connect(this)` then ties the listeners to that lifetime. Without this, reloading the plugin in dev leaves stale listeners subscribed to the old service instance, which keeps the old service alive through closures — classic listener leak. |
| D8 | HTML escape helper is a private file-level function in `ReportGenerator.kt`: `private fun htmlEscape(s: String): String = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")`. Applied to: `graph.metadata.projectName` (twice), `issue.title`, `issue.description`, `issue.filePath`, `issue.codeSnippet`, `node.name`, `node.filePath` (the pre-shortened path tail, which still can contain user-controlled characters). The `issue.severity` enum is safe (always `ERROR`, `WARNING`, `INFO`). The health score, issue counts, and timestamp are numeric/well-known safe. | Replacement order matters: `&` must be first so subsequent escapes don't get re-escaped. Five replacements cover the OWASP HTML-context requirements for this use case (not inside JS, not inside CSS, not in an attribute name). The numeric fields are bypassed intentionally. |
| D9 | Report path is `File(System.getProperty("java.io.tmpdir"), "aegis-debug-${sanitize(project.name)}-${System.currentTimeMillis()}.html")`. `sanitize` replaces anything not in `[A-Za-z0-9._-]` with `_` so project names with spaces or Unicode don't produce illegal filenames. The success toast reads `"Report exported to: ${reportFile.absolutePath}"`. | Tmpdir is universally writable, doesn't pollute the user's project, and is the standard location for throwaway artifacts the browser opens. Timestamp collision-avoidance means successive exports don't clobber each other. Sanitizing the project name avoids the rare but annoying "cannot create file" failure on Windows when the name contains `:` or `?`. |
| D10 | The `ErrorBoundary` wraps only the canvas region of `App.tsx` (the `{state.graph ? ...}` ternary), not the entire `AppContext.Provider`. The StatusBar, toolbar, and detail panel are outside the boundary. | Errors in the graph renderer shouldn't blank out the toolbar — the user still needs to see the "Analyze Project" button and the engine status pill. Keeping those components outside means a render crash in `<NeuroMap>` leaves the rest of the UI intact, and the reload button is one click away. |
| D11 | The `ErrorBoundary` does not catch async/event-handler errors — that's React's intended design. The existing `bridge.onError` + toast flow already handles backend-originated errors. The boundary only catches render-phase errors. | Expanding scope to `window.onerror` would require intercepting events from arbitrary sources and risks showing irrelevant errors (e.g., a React DevTools extension crash). The design goal is narrow: "if our render blows up, show a panel instead of a blank body." |
| D12 | `Semaphore` import is `kotlinx.coroutines.sync.Semaphore`. The `withPermit` extension is already available from the same package. No new dependency. | Already on the classpath via `kotlinx-coroutines-core` — same artifact that provides `SupervisorJob`, which the service already uses. |

---

## 5. Decisions Made From Ambiguity

Where the post-`7d43064` audit left choices open, this section records the path taken and why:

1. **`buildInitialNodes` vs. a new `buildGroupedNodes` that does something meaningful.** Chosen: reuse `buildInitialNodes`. Adding real grouping logic is a feature change; this phase is a bug fix. When the user or a future phase wants folder grouping, it gets its own design doc and its own tests. Mixing a feature into a fix is how regressions like this one happen.

2. **ErrorBoundary scope — full tree vs. canvas only.** Chosen: canvas only. See D10. The toolbar and detail panel have no render-time dependencies on the graph state and should remain operable even if NeuroMap crashes.

3. **`tsc --noEmit` chained into `build` vs. a separate `prebuild` hook vs. a CI-only check.** Chosen: chained. `prebuild` is npm-specific and Yarn/Bun users would silently skip it; a CI-only check means developers ship broken bundles locally and only discover them in CI. Chaining is the least clever option and therefore the most robust.

4. **Ollama concurrency of 2 vs. 4 vs. 8.** Chosen: 4. 2 is barely faster than serial; 8 is too many in-flight HTTP requests for the median local Ollama setup. 4 matches the number of analyzer coroutines in the parallel static pass, giving a consistent mental model.

5. **IO dispatcher vs. Default for Ollama.** Chosen: IO. Ollama calls are HTTP over network; even local Ollama goes through an HTTP server. `Dispatchers.IO` is the correct pool for blocking I/O; `Default` reserves its small thread count for CPU work and starving it with HTTP waits would slow the parallel static pass (which runs concurrently with the AI pass in `AnalysisEngine.analyze`).

6. **Tool-window listener on `Content` disposable vs. service disposable vs. a new application-level disposable.** Chosen: `Content`. The listener's lifetime should match the tool-window content's lifetime; if the user closes the tool window and IntelliJ reconstructs it, the old listener should vanish. `Content` disposal is the event that signals "this tool-window content is gone" — exactly the semantic we want.

7. **`componentDidCatch` log target.** Chosen: `console.error` only. No `bridge.onError` emission — the JCEF bridge may itself be in a broken state when the boundary fires (e.g., during bridge init). Logging to console is sufficient; users can copy the stack from Chrome DevTools if they need to file a bug.

8. **Sanitize project name for tmpdir filename vs. use a fixed `aegis-debug-report.html`.** Chosen: sanitize + timestamp. Fixed filename means concurrent exports clobber each other and repeated exports can't be found by timestamp. Sanitizing makes the filename readable but legal on all filesystems.

9. **Whether to delete the old `aegis-debug-report.html` from `project.basePath` on first Phase-8 run (migration).** Chosen: no. Users who previously committed it to their repo need to decide whether to delete it themselves; a plugin that silently deletes files in the user's project is a worse surprise than a leftover file.

10. **Should the parallel Ollama fan-out abort the whole pass on first failure, or collect partial results?** Chosen: abort on first failure (current behavior preserved). The existing `runCatching { ... }.fold(onFailure = ...)` wraps the whole fan-out; a single failed request falls back to static-only results with the existing fallback status message. Collecting partial results would require a second result type and a new UI state — out of scope.

11. **Whether to add `@Volatile` to the new `wasVisible` in the tool-window listener.** Chosen: no. Listener callbacks run on the EDT single-threaded; no cross-thread visibility concerns. Adding `@Volatile` would suggest a concurrency story that doesn't exist.

12. **Whether the ErrorBoundary ships with a test.** Chosen: yes, a minimal one using `@testing-library/react` (already a devDep per Phase 7's `package.json`). Tests the boundary catches a thrown child and renders the fallback. Not testing the reload button — `window.location.reload` is untestable in jsdom without extra mocking.

---

## 6. Files

### 6.1 Files to Modify

| # | Path | Change |
|---|---|---|
| M1 | `webview/src/components/neuromap/NeuroMap.tsx` | Replace `buildGroupedNodes(graph.nodes)` with `buildInitialNodes(graph.nodes)` at lines 45 and 65. No other change. |
| M2 | `webview/src/App.tsx` | Import `ErrorBoundary` from `./components/ErrorBoundary`. Wrap the `<div style={{ flex: 1, position: 'relative', minWidth: 0, flexGrow: 1 }}>` block (the canvas region) with `<ErrorBoundary>…</ErrorBoundary>`. Leave StatusBar, toolbar, detail panel, and error toast outside the boundary. |
| M3 | `webview/package.json` | `"build"` script: `"vite build"` → `"tsc --noEmit && vite build"`. No dependency changes. |
| M4 | `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt` | (a) Rewrite `runStaticPass` per D4 — `coroutineScope { analyzers.map { async(Dispatchers.Default) { runOne(it, context, indicator) } }.awaitAll().flatten() }`, with private `runOne` preserving the existing per-analyzer try/catch and tagging. (b) Rewrite `runOllamaPass`'s per-file fan-out per D6 — `val semaphore = Semaphore(4); coroutineScope { aiContext.parsedFiles.map { file -> async(Dispatchers.IO) { semaphore.withPermit { indicator.checkCanceled(); indicator.text2 = "Ollama: …"; ollamaService.detectIssues(file.path, file.content) } } }.awaitAll().flatten() }`. Preserve the existing outer `runCatching { ... }.fold(...)` error handling. (c) Add companion constant `private const val OLLAMA_CONCURRENCY = 4`. Import `kotlinx.coroutines.sync.Semaphore` and `kotlinx.coroutines.sync.withPermit`. |
| M5 | `src/main/kotlin/com/ghostdebugger/toolwindow/GhostDebuggerToolWindowFactory.kt` | In `createToolWindowContent`, after `contentManager.addContent(content)`: construct a `Disposable` (lambda-based: `Disposable {}` is not valid in Kotlin — use `Disposer.newDisposable("GhostDebuggerToolWindowListener")`), `Disposer.register(content, disposable)`, then `project.messageBus.connect(disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener { private var wasVisible = toolWindow.isVisible; override fun stateChanged(mgr: ToolWindowManager) { val tw = mgr.getToolWindow("GhostDebugger") ?: return; val isVisible = tw.isVisible; if (wasVisible && !isVisible) { GhostDebuggerService.getInstance(project).cancelAnalysis() }; wasVisible = isVisible } })`. |
| M6 | `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` | (a) Class declaration: add `: com.intellij.openapi.Disposable`. (b) `init` block: `Disposer.register(project, this)`. (c) `dispose()` keeps its body (`autoRefreshJob?.cancel(); scope.cancel()`) and is marked `override`. (d) Both `project.messageBus.connect()` calls (currently at lines 97 and 139) become `project.messageBus.connect(this)`. (e) In `handleExportReportRequested`, change the report file construction from `File(basePath, "aegis-debug-report.html")` to `File(System.getProperty("java.io.tmpdir"), "aegis-debug-${sanitizeFilename(project.name)}-${System.currentTimeMillis()}.html")`. Add a private `fun sanitizeFilename(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")`. Update the success toast to include the absolute path. |
| M7 | `src/main/kotlin/com/ghostdebugger/ReportGenerator.kt` | Add private file-level `htmlEscape(s: String): String` per D8. Wrap every user-controlled interpolation: `${htmlEscape(graph.metadata.projectName)}` (title and paragraph), `${htmlEscape(issue.title)}`, `${htmlEscape(issue.filePath)}`, `${htmlEscape(issue.description)}`, `${htmlEscape(issue.codeSnippet)}`, `${htmlEscape(node.name)}`, `${htmlEscape(node.filePath.split("/").takeLast(3).joinToString("/"))}` (escape the joined path string, not each segment). Numeric fields (`errorCount`, `warningCount`, `issuesCount`, `graph.metadata.healthScore.toInt()`, `node.complexity`) stay raw. `issue.severity` (enum name) stays raw. |

### 6.2 Files to Create

| # | Path | Purpose |
|---|---|---|
| C1 | `webview/src/components/ErrorBoundary.tsx` | Class component per D2. Catches render errors from children, renders a themed fallback card with error message (≤500 chars) and a reload button. `console.error` on catch; no bridge call. |
| C2 | `webview/src/components/ErrorBoundary.test.tsx` | One test: renders a child that throws, asserts the fallback copy appears in the DOM, asserts the reload button is present. Uses `@testing-library/react` (already a devDep). |
| C3 | `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineParallelStaticPassTest.kt` | Replaces / fulfills Phase 7's planned `AnalysisEngineParallelStaticPassTest`. Injects five spy analyzers each with a `CountDownLatch` and a recorded start timestamp; asserts all five `analyze(ctx)` calls began before any of them returned (i.e. true concurrency, not a fast serial loop). Also asserts one analyzer throwing does not prevent the other four from producing results. |
| C4 | `src/test/kotlin/com/ghostdebugger/toolwindow/GhostDebuggerToolWindowFactoryCancelOnCloseTest.kt` | Uses `IdeaTestFixture` / `LightProjectDescriptor` from IntelliJ Platform Test Framework. Opens the Aegis Debug tool window, starts a (mocked) long-running analysis, hides the tool window, asserts `GhostDebuggerService.cancelAnalysis` was called exactly once within 2 s. |
| C5 | `src/test/kotlin/com/ghostdebugger/ReportGeneratorXssTest.kt` | Builds a `ProjectGraph` where one `Issue.title` is `"<script>alert(1)</script>"`, one `Issue.description` contains `<img src=x onerror=alert(1)>`, and one `GraphNode.name` contains `"` and `&`. Generates the report, asserts (a) the substring `<script>` does NOT appear in the output, (b) `&lt;script&gt;` DOES appear, (c) `&quot;` and `&amp;` appear correctly escaped. |
| C6 | `src/test/kotlin/com/ghostdebugger/GhostDebuggerServiceReportPathTest.kt` | Mocks the export path generation (the private helper is called from `handleExportReportRequested`, so this test may need to verify via the actual write + assertion on the observed path). Asserts the file lands under `System.getProperty("java.io.tmpdir")` and its name matches `aegis-debug-.*-\d+\.html`. Does NOT assert anything about `project.basePath`. Cleanup: delete the produced temp file in `@After`. |

### 6.3 Files to Leave Untouched

| Path | Why untouched |
|---|---|
| `build.gradle.kts` | No dependency changes; all Phase 8 APIs already on the classpath. |
| `src/main/resources/META-INF/plugin.xml` | No new services, no new actions, no new extensions. |
| `README.md` / `CHANGELOG.md` / `DATA_HANDLING.md` | Docs update after Phase 8 merges, not during. |
| Every analyzer class (`NullSafetyAnalyzer`, `StateInitAnalyzer`, `AsyncFlowAnalyzer`, `ComplexityAnalyzer`, `CircularDependencyAnalyzer`, `AIAnalyzer`) | Phase 7 already hardened these. Phase 8 touches only the engine that orchestrates them. |
| `SymbolExtractor.kt`, `GraphBuilder.kt`, `InMemoryGraph.kt`, `AICache.kt` | Phase 7 completed these. |
| `GhostDebuggerSettings.kt` | No new or changed settings. |
| `JcefBridge.kt`, `UIEvent.kt`, `UIEventParser.kt` | No new bridge events, no new inbound events. |
| Every fixer class + `FixApplicator.kt` + `FixerRegistry.kt` | Fixers are out of scope. |
| `ParsedFile` / `AnalysisContext` / `AnalysisModels.kt` | Phase 7 added `lines` / `filesByPath` / `aiCacheMaxEntries`; nothing more needed. |
| `webview/src/stores/appStore.ts`, `webview/src/bridge/pluginBridge.ts` | Bridge events and reducers unchanged. |
| `webview/src/components/detail-panel/*`, `StatusBar.tsx`, `PixelCity.tsx`, `CustomNode.tsx` | No change to these components. |
| `webview/vite.config.ts` | Single-file bundling config is correct. |
| All existing Phase 1–7 tests | Must continue to pass unchanged. |

---

## 7. Data Contracts / Interfaces / Schemas

**No wire-protocol changes. No persisted-state changes. No public API changes.**

- `NeuroMap.tsx` restores a call to a pre-existing function — no new export, no new prop.
- `ErrorBoundary` is a new internal React component, imported only by `App.tsx`. Not exported from the webview's public surface (there is none — the bundle is consumed only by JCEF).
- `GhostDebuggerService` implementing `Disposable` is a platform-internal contract: `dispose()` is already defined; adding the interface marker and the `Disposer.register` call is purely internal lifecycle plumbing. No consumer of `GhostDebuggerService.getInstance(project)` sees a behavior change.
- `ReportGenerator` adds a private function. Its public `generateHTMLReport(graph: ProjectGraph): String` signature is byte-identical.
- Tool-window factory registers a listener via an internal `Disposable`. No plugin.xml change; no public extension point touched.
- `AnalysisEngine.runStaticPass` changes from sequential `for` to parallel `coroutineScope`; call site in `analyze(...)` is unchanged and the return type (`List<Issue>`) is identical. Issue merging, fingerprinting, source/provider tagging are preserved.
- `AnalysisEngine.runOllamaPass`'s outer signature, return pair, `EngineStatusPayload` construction, and cancellation semantics are preserved. Only the inner fan-out strategy changes.

**No migration.** Existing user settings, persisted state, and stored AI cache entries are unaffected. Existing reports (if any) left in `project.basePath` from pre-Phase-8 runs stay where they are — the plugin never deletes user files.

---

## 8. Test Plan

### 8.1 New tests (all must pass)

- **C3 `AnalysisEngineParallelStaticPassTest`** — verifies true concurrency of the five analyzers and isolation of failures.
- **C4 `GhostDebuggerToolWindowFactoryCancelOnCloseTest`** — verifies `cancelAnalysis()` fires on window hide.
- **C5 `ReportGeneratorXssTest`** — verifies HTML escaping of issue title, description, snippet, file path, project name, node name.
- **C6 `GhostDebuggerServiceReportPathTest`** — verifies report lands in `tmpdir`, not `project.basePath`.
- **C2 `ErrorBoundary.test.tsx`** — verifies the boundary renders the fallback when a child throws.

### 8.2 Existing tests that must continue to pass unchanged

- All analyzer unit tests. Parallel dispatch is transparent to the analyzer implementations.
- All fixer tests, AI service tests, bridge tests, cancellation tests.
- All Phase 7 tests (`AICacheLruEvictionTest`, `InMemoryGraphCycleCacheTest`, `ParsedFileLinesCachingTest`, `GhostDebuggerServiceFileWatcherTest`, `GhostDebuggerServicePartialReanalysisTest`). These will now additionally pass on top of a parallel static pass.
- `AnalysisEnginePerfSmokeTest` (if `CI_PERF=1`) — should now complete strictly faster with the parallel pass; the assertion is a ceiling (< 30 s), so it still passes but with more headroom.

### 8.3 Manual verification on the reference machine (16 GB / 8-core)

1. Open a 1 000-file TypeScript project. Click **Analyze Project**. Observe:
   - Webview renders the NeuroMap graph (nodes visible, not a solid color).
   - CPU spikes briefly to multiple cores (parallel pass) for under 20 s, then drops to idle.
   - Total wall-clock under 30 s.
2. Introduce a TypeScript error in any `.tsx` file inside `webview/src/`. Run `npm run build`. Observe: build fails on the `tsc --noEmit` step; no `index.html` is written.
3. Install a build with `NeuroMap` deliberately throwing (add `throw new Error("test")` at the top of the component). Click **Analyze Project**. Observe: error card renders in the canvas; StatusBar and toolbar are intact; clicking **Reload plugin** reloads the webview.
4. Start an analysis of a 1 000-file project, then close the Aegis Debug tool window mid-analysis. Observe in the IDE log: "Analysis canceled by user" appears within 2 s; CPU drops to idle within 2 s.
5. With Ollama configured and a 40-file project, run analysis. Observe: Ollama calls pipeline with up to 4 in flight (verify via `tcpdump` or Ollama server logs); total AI-pass duration approximately `ceil(files/4) * typical_per_call_latency`, not `files * typical_per_call_latency`.
6. Click **Export Report** on a project whose name contains a space or non-ASCII character. Observe: browser opens the report; file path is in the OS temp directory; project source tree has NO new `aegis-debug-report.html` file.
7. Manually craft a project with a file whose path or content produces an issue title containing `<script>alert(1)</script>`, export the report, open it. Observe: the text `<script>alert(1)</script>` renders literally; no alert fires; no JavaScript executes.
8. Reload the plugin via Settings → Plugins. Observe: no warning in the IDE log about leaked `BulkFileListener` or `XDebuggerManagerListener` subscriptions from the old service instance.

---

## 9. Acceptance Criteria

Phase 8 is complete when all of the following hold on the reference machine:

1. Clicking **Analyze Project** on a non-empty TypeScript project renders the NeuroMap with visible nodes and edges — never a solid background color.
2. `npm run build` in `webview/` fails with a non-zero exit code when there is any TypeScript error.
3. If a render-time error occurs inside NeuroMap or PixelCity, the `ErrorBoundary` fallback renders with the error message and a reload button; the StatusBar and toolbar remain visible and operable.
4. `AnalysisEngine.runStaticPass` runs the five analyzers concurrently (all five enter their `analyze(ctx)` body before any of them returns, verified by C3).
5. Full analysis of the 1 000-file synthetic fixture completes in under 30 s with no single core pinned for more than 5 continuous seconds (Phase 7 §9.3 and §9.6 now actually achieved).
6. Closing the Aegis Debug tool window during analysis cancels the analysis within 2 s.
7. Ollama AI pass runs with up to 4 concurrent in-flight HTTP requests — verified by observation and by C3-equivalent timing (per-file-count wall clock drops by a factor of ~3–4 on slow remote endpoints).
8. Every `project.messageBus.connect()` call in `GhostDebuggerService` is scoped to `this` (the service as `Disposable`). `grep -n 'messageBus.connect()' src/main/kotlin/com/ghostdebugger/` returns zero matches in the service file.
9. The exported HTML report cannot execute JavaScript injected via `Issue.title`, `Issue.description`, `Issue.codeSnippet`, `Issue.filePath`, `GraphNode.name`, `GraphNode.filePath`, or `ProjectGraph.metadata.projectName`. Verified by C5.
10. The exported HTML report file lands in `System.getProperty("java.io.tmpdir")`. No file is written to `project.basePath`. Verified by C6.
11. `./gradlew clean build test` passes green with zero test regressions.
12. `npm run test` in `webview/` passes green.
13. No warning about leaked listeners appears in the IDE log after plugin reload.

---

## 10. Execution Order

Edits are sequenced so the webview bug is fixed first (user-blocking), then the Phase 7 gaps close, then hardening.

1. **M3** (`webview/package.json`): chain `tsc --noEmit`. Running it first means step 2 can be validated by running the build.
2. **M1** (`NeuroMap.tsx`): replace `buildGroupedNodes` → `buildInitialNodes`. Run `npm run build` — must succeed.
3. **C1** (`ErrorBoundary.tsx`) + **M2** (`App.tsx` wrap): add the boundary.
4. **C2** (`ErrorBoundary.test.tsx`): test the boundary.
5. **M4** (`AnalysisEngine.kt` — parallel static pass AND Ollama fan-out): single edit to one file; copy-paste from D4/D6. No other class touched.
6. **C3** (`AnalysisEngineParallelStaticPassTest`): validates M4's static-pass concurrency.
7. **M5** (`GhostDebuggerToolWindowFactory.kt`): add the listener.
8. **C4** (`GhostDebuggerToolWindowFactoryCancelOnCloseTest`): validates M5.
9. **M6** (`GhostDebuggerService.kt`): Disposable marker, Disposer.register, both `connect(this)` calls, report path change to tmpdir + sanitizeFilename.
10. **C6** (`GhostDebuggerServiceReportPathTest`): validates M6's report path change.
11. **M7** (`ReportGenerator.kt`): add `htmlEscape`, wrap interpolations.
12. **C5** (`ReportGeneratorXssTest`): validates M7.
13. Run full `./gradlew test` + `npm run test` — must be green.
14. Manual verification §8.3 on the reference machine. Any failing acceptance criterion in §9 identifies the offending M-item as the rollback target.

---

## 11. Rollback

Each M-item is a self-contained edit with targeted test coverage. On regression:

- **M1** (webview `buildInitialNodes` swap): revert; the solid-color bug returns but the build is at least typecheck-guarded by M3.
- **M2 + C1** (ErrorBoundary): revert; render crashes will again blank the body but only under the same conditions M1 covers.
- **M3** (build chain): revert; dev workflow is unchanged but type errors can silently ship again.
- **M4** (parallel static pass + Ollama fan-out): revert; wall-clock regresses to the sequential Phase-7-partial baseline. Cancellation semantics are unchanged (both the old and new forms honor `ProgressIndicator.checkCanceled`).
- **M5** (tool-window listener): revert; analysis keeps running after close. Harmless except for the CPU spike.
- **M6** (Disposable scoping + report path): the two sub-edits are independent. Revert of the `connect(this)` part restores the listener-leak risk; revert of the tmpdir part restores file pollution.
- **M7** (HTML escape): revert; report XSS re-opens. Harmless if the report is never exported, but a ship-blocker if it is.

**No persisted-state migration**, **no wire-protocol change**, **no API break**. Any combination of rollbacks is safe; no edit depends on another to compile (save for M2 depending on C1 existing).

---

## 12. Out-of-Band Checks

Before running the manual verification in §8.3, confirm:

- `grep -rn "buildGroupedNodes" webview/src/` returns zero matches. Any remaining hit means M1 is incomplete.
- `grep -rn "messageBus.connect()" src/main/kotlin/com/ghostdebugger/` returns zero matches in `GhostDebuggerService.kt`. Every call must be `connect(this)` or explicitly scoped to another `Disposable`.
- `grep -n "project.basePath" src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` — the only remaining match should be inside the file-watcher scope check (`path.startsWith(projectBase)`), not in `handleExportReportRequested`.
- `grep -n "tsc --noEmit" webview/package.json` returns one hit inside `"build"` and one inside `"lint"`.
- `grep -n "Disposable" src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` shows the class declaration, the `Disposer.register(project, this)` line, and the `override fun dispose()` line.
- IntelliJ's `Help → Show Log in Files` shows no `BulkFileListener not disposed` warning after a plugin reload cycle.

---

## 13. Summary

Commit `7d43064` shipped most of Phase 7 but missed the two items that made the biggest CPU difference (M6 parallel static pass, M18 cancel-on-close). At the same time, a new webview regression landed that calls an undefined `buildGroupedNodes` function in `NeuroMap.tsx`, producing the blank-Dark-Navy screen the user reported when clicking **Analyze Project**. Neither was caught because the webview build doesn't run `tsc --noEmit` and the webview has no `ErrorBoundary` to surface render crashes visibly.

Phase 8 is a focused stability + completion pass:

- **Fix the webview regression** (`buildGroupedNodes` → `buildInitialNodes`, add ErrorBoundary, chain `tsc --noEmit` into the build).
- **Finish Phase 7** (parallel static pass in `AnalysisEngine.runStaticPass`; `ToolWindowManagerListener` cancel-on-close).
- **Finish what Phase 7 didn't see** (bounded-concurrency Ollama fan-out; `Disposable`-scoped `messageBus.connect()` calls; HTML-escape the exported report; move the report out of `project.basePath`).

Seven file modifications, six new tests, one re-audit of the engine plus the webview plus the report generator. After Phase 8 merges and the §9 acceptance criteria pass on the reference machine, the plugin clears the ship-blockers it re-introduced in Phase 7 and is ready for Marketplace submission.

— End of spec —
