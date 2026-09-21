---
title: "Creating New Analyzers Guide"
type: "guide"
related_components:
  - "[[Static_Analyzers]]"
  - "[[KotlinAnalysisHelpers]]"
  - "[[AnalysisOrchestrator]]"
aliases:
  - "New Analyzer Guide"
tags:
  - aegis-debug
  - analyzer
---

# Creating New Analyzers Guide

This guide details how to implement a new static analyzer for Aegis Debug.

## 1. Rules and Architecture
- **One file per rule ID**: Filename matches the human name of the rule (e.g., `NullSafetyAnalyzer.kt` for `AEG-NULL-001`).
- **Interface**: Inherit from `Analyzer`.
- **Conservative-Miss Bias**: If the type system or AST cannot decide (unresolved symbol, `KaErrorType`, ambiguous type), **DO NOT FLAG**. False positives destroy developer trust.
- **Single Entry Point for Kotlin**: All Kotlin Analysis API queries **MUST** go through `parser/KotlinAnalysisHelpers.withKtAnalysis`. Never call `analyze { }` directly.
- **ProcessCanceledException**: Ensure any `catch (e: Exception)` block rethrows `ProcessCanceledException` immediately.

## 2. Step-by-Step Implementation

1. **Create Analyzer File**: Create `src/main/kotlin/com/ghostdebugger/analysis/analyzers/YourNewAnalyzer.kt`.
2. **Implement `Analyzer`**: the real interface (`analysis/Analyzer.kt`) requires four properties plus the entry
   point — `name`, `ruleId`, `defaultSeverity: IssueSeverity`, `description`, and
   `analyze(context: AnalysisContext): List<Issue>` — **not** `analyze(file: ParsedFile)`. `AnalysisContext`
   wraps `parsedFiles: List<ParsedFile>` plus the project and its in-memory graph:
```kotlin
class YourNewAnalyzer : Analyzer {
    override val name = "YourNewAnalyzer"
    override val ruleId = "AEG-YOUR-001"
    override val defaultSeverity = IssueSeverity.WARNING
    override val description = "One-sentence description of what this rule detects."

    override fun analyze(context: AnalysisContext): List<Issue> {
        // Implementation logic over context.parsedFiles
    }
}
```
   If your analyzer queries the Kotlin Analysis API, extend `KotlinAnalyzer`
   (`analysis/analyzers/KotlinAnalyzer.kt`) instead of implementing `Analyzer` directly. It already filters
   `context.parsedFiles` to `.kt` files, resolves each one to a project-bound `KtFile`, runs inside a read
   action, makes the single `withKtAnalysis` call, and rethrows `ProcessCanceledException` per file — you
   only implement `analyzeKtFile`:
```kotlin
class YourNewAnalyzer : KotlinAnalyzer() {
    override val name = "YourNewAnalyzer"
    override val ruleId = "AEG-YOUR-KT-001"
    override val defaultSeverity = IssueSeverity.WARNING
    override val description = "One-sentence description of what this rule detects."

    override fun analyzeKtFile(
        ktFile: KtFile, parsedFile: ParsedFile, context: AnalysisContext, session: KaSession
    ): List<Issue> {
        // Implementation logic; `session` is the open Analysis API session
    }
}
```
   Every existing Kotlin-Analysis-API analyzer (`KotlinUnsafeCastAnalyzer`, `KotlinNullSafetyAnalyzer`,
   `KotlinTypeMismatchAnalyzer`, `KotlinRedundantLetAnalyzer`) follows this pattern — none of them call
   `withKtAnalysis` or catch `ProcessCanceledException` themselves; `KotlinAnalyzer` does it once for all of them.
3. **Handle Smart-Casts**: Use `effectiveType` or `effectiveTypeWithStructuralSmartCast` instead of raw `expressionType` to respect Kotlin smart-cast narrowing.
4. **Register in `AnalysisEngine`**: add `YourNewAnalyzer()` to the `analyzers` list in `AnalysisEngine`'s
   constructor (`analysis/AnalysisEngine.kt:40-53`). `AnalysisOrchestrator` does not hold an analyzer list of
   its own — it constructs `AnalysisEngine()` and calls `.analyze(...)` — so registering anywhere else has no effect.
5. **Write Unit Tests**: Add test cases in `src/test/kotlin/com/ghostdebugger/analysis/analyzers/YourNewAnalyzerTest.kt`.
   Extend `AegisKotlinAnalysisTestCase` only if the analyzer goes through the Kotlin Analysis API (i.e. extends
   `KotlinAnalyzer`) — it spins up a light IDE fixture with the Kotlin stdlib and provides `analyze` /
   `expectFinding` / `expectNoFinding` helpers. Analyzers that don't touch the Analysis API (the TS/JS
   regex-based ones, `ComplexityAnalyzer`, etc.) are plain test classes with no platform base class, built
   against `AnalysisContext`/`ParsedFile` fixtures directly (see `NullSafetyAnalyzerTest.kt`, which uses
   `testutil.FixtureFactory`). Either way, include positive, negative, and ambiguous (no-flag) cases.
