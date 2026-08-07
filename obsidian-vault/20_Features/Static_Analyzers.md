---
title: "Static Analyzers"
type: "feature"
related_components:
  - "[[AnalysisOrchestrator]]"
aliases:
  - "Analyzers"
tags:
  - aegis-debug
---

# Static Analyzers

Aegis Debug is **Static-first**. Deterministic engines decide; AI augments.

## Core Rules
- **Conservative-miss bias:** If an analyzer can't decide (e.g., unresolved symbol, `KaErrorType`), it must NOT flag. False positives cost more trust than false negatives.
- Kotlin Analyzers MUST use `parser/KotlinAnalysisHelpers.withKtAnalysis` to safely interact with the Kotlin Analysis API.

## Implemented Analyzers

Twelve analyzers are registered in `AnalysisEngine`'s default list
(`src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt`):

| Analyzer | Covers |
|---|---|
| `PsiSyntaxAnalyzer` | Syntax error harvesting |
| `CompilationErrorAnalyzer` | Compilation error harvesting |
| `NullSafetyAnalyzer` | Null safety (JS/TS) |
| `KotlinNullSafetyAnalyzer` | Null safety (Kotlin, K2 Analysis API) |
| `KotlinUnsafeCastAnalyzer` | Unsafe `as` casts |
| `KotlinTypeMismatchAnalyzer` | Type mismatches |
| `KotlinRedundantLetAnalyzer` | Redundant `let` |
| `StateInitAnalyzer` | State initialization |
| `AsyncFlowAnalyzer` | Async flow |
| `CircularDependencyAnalyzer` | Circular dependencies |
| `ComplexityAnalyzer` | Complexity |
| `CustomRuleAnalyzer` | User-authored YAML rules — see [[Custom_Rule_Authoring]] |

`PsiSyntaxAnalyzer` and `CompilationErrorAnalyzer` implement `EarlyAnalyzer`: they run first so the
engine can compute the set of broken files and skip the remaining analyzers on them.

### Compile-error harvesting is budgeted

`CompilationErrorAnalyzer` runs IntelliJ's full highlighting daemon
(`DaemonCodeAnalyzerImpl.runMainPasses`) once per file — 0.3–2s each. It fans that work out
`DAEMON_CONCURRENCY`-way in parallel and bounds it with `DaemonHarvestBudget`, driven by two
settings: `daemonFileBudget` (default 150 files) and `daemonTimeBudgetMs` (default 60s). Hitting
either cap truncates the pass; truncation is logged and surfaced in the progress text rather than
being silent, so a partial harvest is never mistaken for a clean bill of health.

See [[Project_Principles]] for how these analyzers operate under strict privacy and correctness rules.
