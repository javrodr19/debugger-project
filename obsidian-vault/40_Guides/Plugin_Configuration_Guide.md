---
title: "Plugin Configuration Guide"
type: "guide"
status: "active"
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
- **AI Provider**: `NONE` (default), `OLLAMA` (local), or `OPENAI` (cloud).
- **Allow Cloud Upload**: Explicit opt-in boolean required before any snippet is sent to OpenAI.
- **Ollama Endpoint**: URL for local Ollama instance (default `http://localhost:11434`).
- **API Key Storage**: OpenAI API keys are securely stored in IntelliJ `PasswordSafe`.

### 2. Analysis & Complexity Thresholds
- **`maxComplexity`**: Cyclomatic complexity threshold for `ComplexityAnalyzer` (default: 10).
- **`maxFilesToAnalyze`**: Cap on the number of files analyzed in a single pass (prevents UI lockup on monorepos).
- **`maxDependentsToReanalyze`**: Cap on dependent-cascade re-analysis fan-out (default: 20). 0 disables cascade.

### 3. Suppression & Provenance
- **False-positive suppression memory**: Dismissed findings that are not confirmed by runtime execution are suppressed locally.
