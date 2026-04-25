# Changelog

All notable changes to Aegis Debug are documented here.

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
