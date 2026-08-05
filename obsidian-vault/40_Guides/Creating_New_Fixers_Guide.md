---
title: "Creating New Fixers Guide"
type: "guide"
status: "active"
related_components:
  - "[[Deterministic_Fixers]]"
  - "[[AnalysisOrchestrator]]"
aliases:
  - "New Fixer Guide"
tags:
  - aegis-debug
  - fixer
---

# Creating New Fixers Guide

This guide details how to implement a deterministic fixer for Aegis Debug.

## 1. Guiding Principles
- **Deterministic fixes only**: Every fixer must produce output that is guaranteed to be PSI-valid.
- **Return `null` on failure**: If a fixer cannot guarantee a valid PSI outcome, it must return `null` so the orchestrator can fall back to the AI supervisory path.
- **PSI-driven, not regex**: Fixers must manipulate AST/PSI nodes rather than executing regex replacements on source text (to prevent rewriting symbols inside comments or strings).
- **FixEngine Integration**: In V3+, fix application routes through `FixEngine` (`fix/engine/`), adapting `CodeFix` to a single-op `FixPlan` executed by `FixPlanApplicator`.

## 2. Step-by-Step Implementation

1. **Create Fixer File**: Create `src/main/kotlin/com/ghostdebugger/fix/YourNewFixer.kt`.
2. **Implement `Fixer` Interface**:
```kotlin
class YourNewFixer : Fixer {
    override fun derive(issue: Issue, psiFile: PsiFile): CodeFix? {
        // Find PSI target
        // Validate PSI state
        // Return CodeFix or null
    }
}
```
3. **Write Unit Tests**: Add automated tests covering fix generation and PSI verification.
