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
(`src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt:40-53`): 11 built-in rules plus
`CustomRuleAnalyzer`, a dispatcher for user-authored YAML rules rather than a built-in rule of its
own (see [[Custom_Rule_Authoring]] — that one is ungated and active). Deterministic static analysis
as a whole is unconditional in 3.0.0 — it is not behind the capability gate.

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

### Per-language reality

"Static analysis" is not one mechanism across languages:

- **TypeScript/JavaScript** (`NullSafetyAnalyzer` and the other analyzers scoped to
  `ts`/`tsx`/`js`/`jsx`) is line-oriented lexical analysis over `file.lines`, with string- and
  comment-masking (`maskStringsAndComments`) so a `//` or a quote inside a string literal can't
  desynchronize the scan — not PSI. IntelliJ Community bundles no JS/TS PSI to parse against
  (`parser/SymbolExtractor.kt:6-14`, confirmed directly in `NullSafetyAnalyzer.kt`).
- **Kotlin**'s four rules use the Analysis API via `withKtAnalysis`, but two of them are shadowed
  by the compile-error gate and do not fire in the normal pipeline: `AEG-NULL-KT-001`
  (`KotlinNullSafetyAnalyzer`) and `AEG-TYPE-KT-001` (`KotlinTypeMismatchAnalyzer`) both target
  conditions that Kotlin's own compiler already treats as hard compile errors (an unguarded
  nullable dereference, a real type mismatch), so any file containing a genuine instance also
  trips `CompilationErrorAnalyzer`'s early pass, and `doStaticPasses`'s `excludeBrokenFromLate`
  drops that file from the late-pass context entirely before either analyzer runs on it —
  demonstrated by `AnalysisEngineShadowingTest` and noted directly in
  `TypeMismatchBreadthIntegrationTest`'s doc comment. `AEG-CAST-KT-001`
  (`KotlinUnsafeCastAnalyzer`) and `AEG-REDUNDANT-LET-KT-001` (`KotlinRedundantLetAnalyzer`) target
  compile-*valid* code (`as` and `?.let{}` both compile regardless of runtime type), so they are
  not shadowed.
- **Java** gets PSI symbol extraction (`parser/JavaPsiSymbolExtractor.kt`) but no dedicated
  Java-specific rule beyond the language-agnostic ones (syntax, compile, circular-dependency).
- **`CircularDependencyAnalyzer`**'s internal edges resolve for relative imports only
  (`parser/DependencyResolver.kt:56-58`, `isRelativeImport` — `./` or `../` prefix). In practice
  that means TypeScript/JavaScript: Kotlin and Java imports are fully-qualified, never relative, so
  a Kotlin- or Java-only project produces a graph with nodes but zero internal edges, and cycle
  detection/impact analysis have nothing to find there. The analyzer itself is not gated and runs
  unconditionally on every file — it's specifically the claim of graph-wide reach for JVM languages
  that is gated, under `JVM_DEPENDENCY_GRAPH`.

### Compile-error harvesting is budgeted

`CompilationErrorAnalyzer` runs IntelliJ's full highlighting daemon
(`DaemonCodeAnalyzerImpl.runMainPasses`) once per file — 0.3–2s each. It fans that work out
`DAEMON_CONCURRENCY`-way in parallel and bounds it with `DaemonHarvestBudget`, driven by two
settings: `daemonFileBudget` (default 150 files) and `daemonTimeBudgetMs` (default 60s). Hitting
either cap truncates the pass; truncation is logged and surfaced in the progress text rather than
being silent, so a partial harvest is never mistaken for a clean bill of health.

See [[Project_Principles]] for how these analyzers operate under strict privacy and correctness rules.
