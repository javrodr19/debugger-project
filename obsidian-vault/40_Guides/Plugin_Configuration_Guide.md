---
title: "Plugin Configuration Guide"
type: "guide"
related_components:
  - "[[BaseAIService]]"
  - "[[GhostDebuggerService]]"
aliases:
  - "Settings Guide"
tags:
  - aegis-debug
  - settings
---

# Plugin Configuration Guide

Aegis Debug provides several configurable settings under **Settings → Tools → Aegis Debug**.

## Key Configuration Options

### 1. Privacy & AI Settings
- **AI Provider**: `NONE` (default), `OLLAMA` (local), or `OPENAI` (cloud). In 3.0.0 the `AI_ANALYSIS` and
  `AI_EXPLANATION` capabilities are gated off (`AegisCapability.kt`; see `docs/IMPLEMENTATION_STATUS.md`
  Gated table) regardless of this setting — choosing `OLLAMA`/`OPENAI` here configures the provider but has
  no visible effect yet; every finding you see comes from the deterministic static analyzers.
- **"Allow cloud upload (OpenAI)"**: exact checkbox label (`GhostDebuggerConfigurable.kt:167`) — explicit
  opt-in required before any snippet is sent to OpenAI.
- **Ollama Endpoint**: URL for local Ollama instance (default `http://localhost:11434`).
- **API Key Storage**: OpenAI API keys are securely stored in IntelliJ `PasswordSafe`.

### 2. Analysis & Complexity Thresholds
- **`maxComplexity`**: Cyclomatic complexity threshold for `ComplexityAnalyzer` (default: 10). Spinner labeled
  "Complexity threshold:" in Settings ▸ Tools ▸ Aegis Debug.
- **`maxFilesToAnalyze`**: Cap on the number of files analyzed in a single pass (prevents UI lockup on
  monorepos). Spinner labeled "Max files to analyze:" in the settings panel.
- **`maxDependentsToReanalyze`**: Cap on dependent-cascade re-analysis fan-out (default: 20; 0 disables
  cascade), read at `AnalysisOrchestrator.kt:302`. **Not exposed in Settings ▸ Tools ▸ Aegis Debug** —
  `GhostDebuggerConfigurable.kt` has no control for it; changing it requires editing the persisted
  `ghostdebugger.xml` state directly.

### 3. Suppression & Provenance
- **False-positive suppression memory**: Dismissed findings that are not confirmed by runtime execution are suppressed locally.
