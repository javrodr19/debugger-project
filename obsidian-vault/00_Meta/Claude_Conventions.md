---
title: "Claude Conventions"
type: "meta"
status: "active"
related_components: []
aliases:
  - "CLAUDE.md"
tags:
  - aegis-debug
---

# Claude Conventions

Specific gotchas, build prerequisites, and error handling rules (from `CLAUDE.md`).

## Build Prerequisites
Gradle's `instrumentTestCode` requires `JAVA_HOME` to point at a JetBrains Runtime (JBR), not a generic JDK.
Use the following to locate and set it:
```bash
find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1
export JAVA_HOME=/path/to/jbr
export PATH=$JAVA_HOME/bin:$PATH
```

## Important Code Rules
1. **Error Handling**: `ProcessCanceledException` (PCE) must be rethrown immediately in any `catch (e: Exception)` block.
2. **Analyzer Bias**: Analyzers must not flag on ambiguity. False positives are costly.
3. **Fixer Principle**: Fixers must return `null` if they cannot guarantee a PSI-valid fix. Fixes are applied via `FixEngine` (V3).
4. **Kotlin Analysis API**: MUST use `parser/KotlinAnalysisHelpers.withKtAnalysis`. Calling `analyze(file) { ... }` directly bypasses exception handling.
5. **Facade State Ownership**: `GhostDebuggerService` is the single source of truth for `currentIssues`, `issuesByFile`, etc. Collaborators must write via `service.updateIssues(...)`.

## Common Gotchas
- **`trimIndent()` interpolation order**: Kotlin evaluates `${...}` before computing indentation. Be careful with multi-line interpolated strings. Use `StringBuilder`.
- **`KotlinLightProjectDescriptor`**: Unreachable in IPGP 2.14.0. Use `AegisKotlinStdlibProjectDescriptor`.
- **`expressionType` vs `effectiveType`**: `expressionType` doesn't always include smart-cast info. Use `effectiveType` or `effectiveTypeWithStructuralSmartCast`.
