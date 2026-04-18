# Changelog

All notable changes to Aegis Debug are documented here.

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
