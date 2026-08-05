---
title: "Creating New Analyzers Guide"
type: "guide"
status: "active"
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
2. **Implement `Analyzer`**:
```kotlin
class YourNewAnalyzer : Analyzer {
    override val ruleId = "AEG-YOUR-001"
    override val name = "Your Rule Name"
    
    override fun analyze(file: ParsedFile): List<Issue> {
        // Implementation logic
    }
}
```
3. **Handle Smart-Casts**: Use `effectiveType` or `effectiveTypeWithStructuralSmartCast` instead of raw `expressionType` to respect Kotlin smart-cast narrowing.
4. **Register in Orchestrator**: Register your new analyzer in `AnalysisEngine` / [[AnalysisOrchestrator]].
5. **Write Unit Tests**: Add test cases in `src/test/kotlin/com/ghostdebugger/analysis/analyzers/YourNewAnalyzerTest.kt` extending `AegisKotlinAnalysisTestCase`. Include positive, negative, and ambiguous (no-flag) cases.
