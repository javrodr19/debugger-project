# Changelog

All notable changes to Aegis Debug are documented here.

## [3.0.0] — 2026-09-20 — Final release: capability gate, seven repairs, honest documentation

This is the first tagged, released version since 1.5.0. Everything under "2.0.0" and "3.0.0-dev"
below was built but never shipped on its own — it all reaches a user for the first time here,
which is why this entry, not those, is where the capability gate lives.

### What changed to make this release honest

- **Capability gate.** 3.0.0 ships as a read-only analysis and visualization tool. Eight
  implemented, tested capabilities are gated off behind one chokepoint, `AegisCapabilityGate`,
  rather than shipped half-finished: `FIX_APPLICATION`, `AI_EXPLANATION`, `AI_ANALYSIS`,
  `JVM_DEPENDENCY_GRAPH`, `RULE_PACKS`, `EXTERNAL_ANALYZERS`, `PROBLEMS_VIEW_EMIT`,
  `DEBUGGER_CROSS_CHECK`. Each capability's reason is a single sentence, defined once on
  `AegisCapability`, that the in-IDE dialog and `docs/IMPLEMENTATION_STATUS.md` both read
  verbatim — the two cannot say different things about the same capability.
- **Privacy defect closed.** The explain and fix paths constructed an OpenAI service without
  consulting the `allowCloudUpload` setting the README advertises — both `resolveAiService()`
  call sites (`UIEventRouter`, `AnalysisOrchestrator`) bypassed the one production site that did
  check it. The consent check now lives once, at the single chokepoint every caller goes through
  (`AIServiceFactory.create`), so no future caller can reintroduce the bypass.
- **A gate that gated nothing, found and fixed.** During this work, the debugger cross-check's
  guard was placed on `DebugObserver.start()` — a method that only logs. The platform subscription
  that does the real work runs in `init {}`, at construction, before `start()` is ever called, so
  the guard suppressed one log line while the gated behavior (`RuntimeEvidenceStore.record(...)`
  on every debugger pause) kept running underneath it. Moved to the first line of
  `evaluateRelevantFindingsAtCurrentFrame()`, the method every path into the pipeline converges on.
  Recorded in `docs/audit-2026-09-final-release.md` because it is the same failure mode this
  release exists to catch, found inside the mechanism built to fix it.
- **Four repaired user-visible actions.** "Show File in NeuroMap" targeted tool-window id
  `"Aegis Debug"` against a window registered as `"GhostDebugger"`, so it was a silent no-op.
  "Reanalyze Current File" called `analyzeProject()` (the whole project) instead of
  `reanalyzeFile(path)` (the one file its label names). The explicit "Suppress Finding Under
  Caret" command called `recordDismissal` instead of `suppressNow`, so an explicit suppress still
  needed three uses before the automatic threshold kicked in. Report export's success path called
  a null JCEF bridge instead of showing a notification, so a successful export gave no visible
  confirmation. All four now do exactly what their label promises.
- **Five dead settings removed.** `autoAnalyzeOnOpen`, `showInfoIssues`,
  `analyzeOnlyChangedFiles`, `coverageMode`, and `nudgeShownOnce` had zero production read sites —
  changing them from the Settings panel had no effect. `showUnreached` was investigated and found
  plumbed end-to-end with no leaf consumer (a different defect), so it was kept and documented
  rather than deleted; every other remaining setting has real effect.
- **Ollama streaming-flag encoding fixed.** `stream = false` was silently dropped from the request
  body because `kotlinx.serialization`'s `encodeDefaults` is `false` by default; the field is now
  pinned to always encode.
- **Documentation reconciled against measured counts.** "Eleven deterministic analyzers" and
  "five deterministic fixers" undercounted the registries (12 and 8) in README, `plugin.xml`'s
  own `<description>` (both the inline copy and `build.gradle.kts`'s authoritative one, which
  `patchPluginXml` actually writes into the shipped plugin), and the project landing page. A test
  now pins every documented count to the registry it describes, so the two cannot drift apart
  again. New:
  `docs/IMPLEMENTATION_STATUS.md` (what's live, what's gated and why, what's implemented with no
  consumer yet) and `docs/audit-2026-09-final-release.md` (the full audit this release closes out),
  replacing `docs/audit-2026-06-post-v2.md`.

### Also first shipping here (previously unreleased "2.0.0" / "3.0.0-dev" work, below)

The two sections below were written as if 2.0.0 would tag and ship on its own; it never did.
Their content is real and tested, but several of the capabilities they describe are gated per
this entry — most of "V2 — Dynamic Validation" ships disabled, and within "V3," fix application
(the AI-Supervised Fix Engine bullet), rule packs (V3.2), and the external SDK (V3.4) ship
disabled under the capability gate. V3.3's Fix-Preview UX classes (`FixDiffGenerator`,
`BatchFixPreview`, `FixPreviewDialog`) are a different status, not the same one: nothing gates
them, they simply have no caller — "implemented, no consumer" in `docs/IMPLEMENTATION_STATUS.md`,
not a capability this release turned off. Custom rule authoring (V3.1) is the one V3 item that is
neither: it runs unconditionally, ungated, as one of the 12 registered analyzers. Read
`docs/IMPLEMENTATION_STATUS.md` before assuming a bullet below is live in this release.

### V2 — Dynamic Validation & IDE-Native Integration

- **Runtime-Confirmed Provenance Tier.** A fourth source tag, `RUNTIME_CONFIRMED`, joins `STATIC` / `AI_LOCAL` / `AI_CLOUD`.
- **Debug-Session Cross-Check.** `DebugObserver` + `DebugObservationLogic` correlate variable values observed at breakpoints with null-safety / state-before-init findings (`DebugSessionCoordinator`).
- **Test-Suite Cross-Check.** `TestRunObserver` + `TestRunCorrelation` correlate executed / failing-test code paths with static findings via `AegisTestStatusListener`.
- **False-Positive Suppression Memory.** `SuppressionMemoryService` auto-hides findings dismissed and never runtime-confirmed across N analyses.
- **Native Problems Tool Window.** `ProblemsViewCoordinator` publishes issues to IntelliJ's `WolfTheProblemSolver` native panel.
- **Inspection Profile Integration.** `AegisLocalInspection` makes rules togglable under `Settings → Editor → Inspections`.
- **Quick-Fix Intentions.** Fixers exposed as `IntentionAction`s (`Alt+Enter`).

### V3 — Fix Engine, Custom Rules, Rule Packs & External SDK

- **AI-Supervised Fix Engine.** Fix application routes through `FixEngine.fixSupervised`: AI plans/supervises deterministic `FixOperation`s, verified by Tier-1 & Tier-2 verifiers (`FixPlanApplicator`).
- **V3.1 Custom Rule Authoring.** Repo-specific declarative YAML rules (`.aegis/rules/*.yml`) with `CustomRuleService`, `CustomRuleAnalyzer`, `RuleMatcher`, and `IssueSource.CUSTOM`.
- **V3.2 Rule Packs.** Curated, togglable rule bundles (React strict, Kotlin coroutines, Node security) + project packs (`.aegis/packs/*.yml`) via `RulePackService`.
- **V3.3 Fix-Preview UX.** Line-by-line unified diff generation (`FixDiffGenerator`), batch fix diff preview (`BatchFixPreview`), and a Swing preview dialog (`FixPreviewDialog`) — a single scrollable, color-coded unified-diff view (`+`/`-`/space-prefixed lines in one `JTextPane`), not a side-by-side dual-pane view.
- **V3.4 External Analyzer SDK.** Dynamic loading of third-party `.jar` analyzers (`.aegis/analyzers/*.jar`) using `ExternalAnalyzerLoader` with isolated `URLClassLoader`, PCE rethrow protection, and `IssueSource.EXTERNAL_SDK` provenance tagging.
- **No-Regression & Quality Gates.** `SingleFileStaticReanalysis` + Kover coverage measurement (`0.9.1`).

### Plugin Actions (Batch 1)

- **Re-analyze Current File** (editor popup; no keyboard shortcut is registered) via `ReanalyzeFileAction`.
- **Apply All Deterministic Fixes** via `ApplyAllFixesAction` — gated in 3.0.0 under `FIX_APPLICATION`; its original body was an unconditional no-op regardless, since it filtered on `Issue.suggestedFix`, which nothing ever assigns.
- **Next / Previous Finding Navigation** via `NextFindingAction` / `PrevFindingAction` (no keyboard shortcuts are registered).
- **Suppress Finding** via `SuppressFindingAction`.
- **Renamed AI Provider configuration label** in settings/menus (`Configure AI Provider`).

### CI & Repo Hygiene

- **JetBrains Runtime CI**: Added `.github/workflows/ci.yml` running `test`, `detekt`, and `verifyPlugin`.
- **Draft Release Workflow**: Added `.github/workflows/release.yml`.
- **Commit Convention**: Enforced via `.gitmessage`, `CONTRIBUTING.md`, and `.githooks/commit-msg`.
- **Pruned legacy bot workflows**: Removed unused Gemini dispatch/triage actions.

## 1.5.0 — Pre-V2 structural refactor (no user-visible change)

**Date:** 2026-05-11

### Highlights

- **Pure structural release.** No new analyzer, fixer, language target, or UI surface. Counts unchanged at 11 analyzers / 5 fixers / TS+JS+Kotlin+Java.
- **`GhostDebuggerService` shrinks 918 → ~150 LOC.** Becomes a thin facade over four new project-scoped services (`AnalysisOrchestrator`, `UIEventRouter`, `FileChangeWatcher`, `DebugSessionCoordinator`) plus a `ReportExporter` helper. Public API preserved — every external caller sees the same `getInstance(project)` entry point and the same method signatures.
- **`BaseAIService` extracted.** `OllamaService` (146 LOC) and `OpenAIService` (163 LOC) now extend a shared abstract parent that owns cache lifecycle, prompt dispatch, `parseFixResponse`, and `detectIssues` orchestration. Subclasses implement only `callModel(systemPrompt, userPrompt, jsonMode)`. Net duplication eliminated: ~120 LOC.
- **Why now, not in V2:** V2's roadmap (dynamic validation, Python, IDE-native integration) lands new code paths in or adjacent to today's god class. Splitting before V2 means each V2 feature lands on a clean seam; splitting after means V2 grows the god class first and V3 inherits a worse refactor problem.

### Deferred (still)

- `Issue.fingerprint()` memoization (perf, no observed pain) — carries forward to V1.6+.
- `InMemoryGraph.toProjectGraph` incremental rebuild — separate design.

## 1.4.1 — Audit-driven correctness, safety, and observability fixes

**Date:** 2026-05-10

### Highlights

- **Cancel-during-analysis is now honored.** A `catch (e: Exception)` block in `AnalysisEngine.runOne` was swallowing `ProcessCanceledException` (the IDE's cancellation signal) and returning an empty list, after which the next analyzer kept running. One-line fix; high-impact behavior change. Same class of bug fixed in `CompilationErrorAnalyzer.harvestFile` where `InvocationTargetException` wrapping hid PCE in the cause chain.
- **JCEF tool-window bridge now encodes payloads via `kotlinx.serialization`** for all eight `send*` methods that previously built JSON by hand. The hand-built payloads escaped only `"`; backslashes (Windows paths), newlines (multi-line error messages), tabs, and control chars survived into `executeJavaScript`, breaking the embedded JS or — when an upstream string carried attacker-shaped content — yielding a JS injection vector inside JCEF.
- **`InMemoryGraph` adjacency lists now use `ConcurrentHashMap.newKeySet()`** for the inner sets, eliminating a data race when analyzers add edges concurrently. `findCycles` rewritten as iterative DFS over an explicit stack — removes the recursion-depth ceiling on monorepo-scale graphs.
- **OkHttp `Response` wrapped in `use { }`** in both `OllamaService` and `OpenAIService` — connections return to the pool deterministically on JSON-decode failure.
- **`NullSafetyFixer` rewritten on PSI** — uses `KtDotQualifiedExpression` instead of line-text regex. Previous version could rewrite `varName.` inside string literals or comments.
- **`NullSafetyAnalyzer` mid-line comments now skipped** (previously only line-leading `//` was caught), and a combined-alternation regex collapses the per-file scan from O(N × M) to O(N).
- **`ComplexityAnalyzer` threshold now wired to settings** as `maxComplexity` (default 10, unchanged). The class description claimed "configurable threshold" since V1.0; now it's true.
- **Observability:** `ApiKeyManager` logs PasswordSafe fallbacks; `AIAnalyzer` logs files skipped for exceeding the 2000-line cap.
- **`ParsedFile.content` mutation** replaced with explicit `dropContent()` method — surfaces accidental post-analysis content reads instead of silently returning empty.

### Bug fixes

- `AnalysisEngine.runOne` swallowed `ProcessCanceledException` — Cancel button left subsequent analyzers running.
- `CompilationErrorAnalyzer.harvestFile` swallowed PCE wrapped inside `InvocationTargetException`.
- `JcefBridge.send*` hand-built JSON broke on backslashes / newlines / control chars.
- `InMemoryGraph` adjacency-list inner `LinkedHashSet`s were not thread-safe.
- `findCycles` could StackOverflow on deep dependency chains.
- `OllamaService` / `OpenAIService` Response not deterministically closed on parse failure.
- `AICache.get` get-then-remove TOCTOU could evict fresh entries.
- `NullSafetyFixer` regex matched inside strings / comments.
- `NullSafetyAnalyzer` mid-line comment slipped through.
- `ApiKeyManager` silent PasswordSafe-failure fallback.
- `AIAnalyzer` silent skip of >2000-line files.
- `ReportGenerator` `dateProvider()` interpolated raw into HTML.

### Deferred to V1.5

- `GhostDebuggerService` is 918 lines (god class) — refactor-class.
- `OllamaService` / `OpenAIService` near-duplicate — extract `BaseAIService`.
- `Issue.fingerprint()` recomputed in `mergeIssues` — perf without observed pain.
- `InMemoryGraph.toProjectGraph` re-walks whole graph on every analysis — separate design.

## 1.4.0 — Cleanup, report-export rewrite, smart-cast walker

**Date:** 2026-05-09

### Highlights

- **Report export rewrite** — clean HTML output (no leading-whitespace bug), file-chooser UX, IDE notification with "Open in browser" / "Show in Files" actions.
- **Smart-cast walker** — V1.3's three documented smart-cast known limitations now resolved via parent-chain narrowing detection.
- **AI prompts include function signatures** — JVM-language prompts now include a Function Signatures block listing `name(paramTypes): returnType`.
- **Marketplace copy refreshed** — analyzer count bumped to eleven, fixer count to five.
- **`KaExpressionTypeProvider.getReturnType(KtDeclaration)` deprecation** flagged by 2026.1's verifier resolved.
- **Java regex fallback enrichment** — best-effort capture of return type and parameter types when PSI is unavailable.
- **CLAUDE.md** — project-root file documenting build prerequisites and conventions.
- **Test bar** raised from 192 → ~232.

### Bug fixes

- Report HTML rendered with leading whitespace per line (Kotlin `trimIndent()` interpolation order); replaced with `StringBuilder`.
- Report success path used the error toast; now routes through proper `Notification` group.
- Auto-open via `Desktop.getDesktop().browse()` was fragile on Linux; replaced with file chooser + user-clicks-to-open notification.
- `displayPath = filePath.replace("/", "/")` no-op typo in `ReportGenerator`.
- Missing 1.2.0 entry in plugin.xml `<change-notes>`.

## 1.3.0 — Kotlin K2 + Analysis API

**Date:** 2026-04-29

### Highlights

- Aegis Debug now fully supports the Kotlin plugin in K2 mode. Minimum IDE raised to **IntelliJ 2024.3** (build 243.0).
- `AEG-NULL-KT-001` rewritten on the Kotlin Analysis API. Type-inferred nullables, smart-cast windows, and reassignment flow are now resolved with real type information instead of name-based matching.
- Three new Kotlin analyzers:
  - **AEG-CAST-KT-001** — flags unsafe `as` downcasts (use `as?` + Elvis fallback instead).
  - **AEG-TYPE-KT-001** — flags assignments where the declared type does not accept the initializer's type.
  - **AEG-REDUNDANT-LET-KT-001** — flags `x?.let { ... }` blocks where smart-cast already proved `x` non-null.
- Two new deterministic fixers (unsafe-cast and redundant-let) — type-mismatch is analyzer-only.
- `FunctionSymbol` now carries rendered `returnType` and `paramTypes` for Kotlin and Java files. NeuroMap signatures and AI prompts include them when present.

### Breaking

- Minimum IDE: **IntelliJ 2024.3 (build 243.0)**. Older IDEs will refuse the install.

## [1.2.0] — 2026-04-25 — Hardening release: PSI-backed parsers, resilient AI parsing, dependent cascade

### Added
- New analyzer `KotlinNullSafetyAnalyzer` (rule `AEG-NULL-KT-001`) — PSI-backed Kotlin null-safety check covering safe-call (`?.`), if-null guard, `?.let`, `!!`, Elvis-return/throw, and prior reassignment. Single-file scope, name-based matching (no `BindingContext`), confidence 0.9.
- Setting `maxDependentsToReanalyze` (default 20). Caps the dependent-cascade fan-out triggered by `reanalyzeFile`; 0 disables the cascade entirely.

### Changed
- Resilient AI JSON parsing: `AiJsonExtractor` tries direct parse → fenced block → bracket-balanced scan; `AiIssueMapper` centralizes `Issue` construction across OpenAI and Ollama (rec 2).
- Concurrency consolidation: Ollama pass now routes through `AIAnalyzer`; the duplicated `Semaphore` loop in `AnalysisEngine.runOllamaPass` is gone. Concurrency defaults preserved (OpenAI=3, Ollama=4) (rec 5).
- Few-shot examples added to `detectIssues` and `jointFix` prompts; prose prompts unchanged (rec 4).
- `SymbolExtractor` is now a language dispatcher: TS/JS uses a hardened regex pass with string/comment masking and multi-line import collapsing; Kotlin and Java use real PSI parsers (`KotlinPsiSymbolExtractor`, `JavaPsiSymbolExtractor`) with the regex implementation retained as a private fallback for broken input (rec 1).
- `reanalyzeFile` cascades static-only re-analysis to transitive dependents in the `ProjectGraph`, capped by `maxDependentsToReanalyze`. AI pass deliberately skipped on the cascade to keep cost bounded on hub files (rec 3).
- `analyzeStaticOnly` extracted from `analyze()` in `AnalysisEngine`. No behavior change for `analyze()`.
- `JcefBridge` now implements a minimal `BridgeChannel` interface so dependent-cascade tests can use a recording stub without standing up a real JCEF browser.
- `plugin.xml` declares explicit `<depends>` on `com.intellij.modules.java` and `org.jetbrains.kotlin`, required by the new PSI-backed paths.

### Verification
- Tests: 117 → 167, all green.
- `verifyPlugin` Compatible on IU 2023.2.6, 2024.1.6, 2024.3.2.2, 2025.1.

## [1.1.2] — 2026-04-18 — `AEG-COMPILE-001` now reports findings; test suite runs end-to-end; 2025.1 compatibility restored

### Fixed
- `AEG-COMPILE-001` (`CompilationErrorAnalyzer`) now surfaces IDE-reported compilation errors as intended. Since the analyzer shipped in V1.1, its call to `DaemonCodeAnalyzerImpl.runMainPasses` had been failing the IntelliJ Platform's thread-local `DaemonProgressIndicator` / `HighlightingSession` contracts; a broad `catch (Throwable)` swallowed the exception, so the analyzer silently returned zero findings on every file. The harvest path is now wrapped in `ProgressManager.runProcess(DaemonProgressIndicator)` + `HighlightingSessionImpl.runInsideHighlightingSession` inside a read action, matching the platform's internal contract.
- Restored compatibility with IDEA 2025.1+ (build 251 and newer). The platform-internal `HighlightingSessionImpl.runInsideHighlightingSession` signature grew a required `CodeInsightContext` parameter in 2025.1 as part of the "multiverse" feature. The analyzer now resolves the static method reflectively, caches whichever signature the running IDE exposes, and supplies `CodeInsightContextKt.anyContext()` on 2025.1+. Plugin-verifier runs now pass on all four target IDEs (IU 2023.2.6, 2024.1.6, 2024.3.2.2, 2025.1) with zero compatibility problems.

### Internal
- Resolved the `BasePlatformTestCase` "indexing hang" that silently disabled five tests since V1.1. Root cause was a classpath collision: `kotlinx-coroutines-core:1.9.0` (pulled in transitively, including via `mockk`) shadowed the IntelliJ Platform's forked `kotlinx-coroutines-core-jvm-*-intellij.jar`, which exposes extra methods like `runBlockingWithParallelismCompensation` that `UnindexedFilesScanner` invokes during test setup. The stock jar winning resolution produced a `NoSuchMethodError` inside the scanning coroutine, the scan never completed, and the daemon polled indefinitely at `IndexingTestUtil.waitUntilIndexesAreReady`. Fix: `kotlinx-coroutines-core` is now `compileOnly`, and a configuration-level `exclude` on `runtimeClasspath` + `testRuntimeClasspath` catches every transitive pull (per-dependency excludes are insufficient because `mockk` reintroduces the jar).
- Upgraded `org.jetbrains.intellij.platform` gradle plugin `2.2.1` → `2.14.0` (and bumped the Gradle wrapper to 9.0, required by the plugin). The upgrade was initially pursued under the wrong hypothesis about the hang; it is retained because the 2.14.x `TestFrameworkType.Platform` / `Plugin.Java` test-framework wiring is cleaner and already integrated.
- With the hang resolved, five `BasePlatformTestCase` tests (`PsiSyntaxAnalyzerTest`, `CompilationErrorAnalyzerTest`, `AnalysisEngineEarlyPassTest`, `FileScannerDocumentReadTest`, `AnalysisEnginePostEditRerunTest`) now execute. The V1.1.1 stale-content fix is retrospectively covered by automated tests. Full suite: 117/117 passing.

## [1.1.1] — 2026-04-17 — Stale-content bug fix on re-analysis

### Fixed
- Re-analysis now reflects unsaved editor edits for every analyzer, not just PSI-based syntax and compilation checks. Previously, correcting an issue from `NullSafetyAnalyzer`, `StateInitAnalyzer`, `AsyncFlowAnalyzer`, `CircularDependencyAnalyzer`, `ComplexityAnalyzer`, or the AI pass and re-running analysis would continue to flag the issue until the file was saved to disk. File text is now sourced from the live IDE `Document` when available, matching the behavior already used by `AEG-SYNTAX-001` and `AEG-COMPILE-001`.

### Internal
- `PsiDocumentManager.commitAllDocuments()` is invoked once at the start of each analysis run to guarantee PSI is in sync with recent Document edits before the early pass reads the tree.

## [1.1.0] — 2026-04-16 — Syntax & compilation error detection

### Added
- `AEG-SYNTAX-001` (PsiSyntaxAnalyzer): detects parse-level syntax errors across Kotlin, Java, TypeScript, JavaScript using the IDE's PSI tree.
- `AEG-COMPILE-001` (CompilationErrorAnalyzer): surfaces IDE-reported compilation errors (unresolved references, type mismatches, invalid declarations) harvested from the IntelliJ analysis daemon.
- Broken-file skip: when a file has syntax or compilation errors, downstream static and AI analyzers are not run on it to prevent false-positive cascades.

### Changed
- Analysis now runs in two static phases: `early` (syntax/compile) then `late` (the existing five rules). Total runtime on full-project audits increases by roughly 10–30 seconds on medium repos; this is the cost of the full IDE-level compilation sweep. The syntax pass alone (PSI errors only, without the compilation harvest) remains sub-second.

### Known Limitations
- TypeScript/JavaScript PSI availability depends on the IntelliJ JavaScript plugin. Without it, syntax/compile detection for TS/JS falls back to PSI-only (no daemon harvest). Kotlin and Java are covered in all supported IDEs.

## [1.0.0] — 2026-04-15 — V1 General Availability

### Added
- Five deterministic static analyzers: null safety, state-before-init, async flow, circular dependencies, complexity.
- Three deterministic fixers (regex construction, PSI-validated on apply) with diff preview and native undo.
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
- Primary: TypeScript and JavaScript (full analysis + fixers). Secondary: Kotlin and Java (graph and circular-dependency analysis only).
- Targeted post-fix re-analysis covers the modified file only.
- Very large repos (>500 files) are subject to the `maxFilesToAnalyze` cap.
