# Changelog

All notable changes to Aegis Debug are documented here.

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
