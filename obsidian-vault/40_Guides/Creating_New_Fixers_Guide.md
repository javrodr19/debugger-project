---
title: "Creating New Fixers Guide"
type: "guide"
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
- **PSI-valid output, not necessarily a PSI-driven mechanism**: the hard rule is that the *result* must parse
  cleanly, not that every fixer must manipulate AST/PSI nodes internally. `generateFix(issue, fileContent)` —
  the one required method — operates on the file's raw text and may use string/regex manipulation, provided
  it's careful: `NullSafetyFixer` (`fix/NullSafetyFixer.kt`) masks string-literal and comment contents before
  matching so it never rewrites text inside them, then returns `null` if the masked match doesn't line up.
  `generateFixFromPsi` and `generatePlan` are the optional AST/PSI-native paths, tried before `generateFix`.
- **FixEngine Integration**: fix *derivation* routes through `FixDeriver` (`fix/FixDeriver.kt`), which tries
  `generatePlan` → `generateFixFromPsi` → `generateFix` in that order and adapts the result to a single-op
  `FixPlan`. Fix *application* routes through `FixEngine`/`FixPlanApplicator` (`fix/engine/`) behind a Tier-1
  PSI-validity gate and a Tier-2 re-analysis gate. **Fix application is gated off in the current release**:
  `AegisCapabilityGate` blocks `FIX_APPLICATION` at `AnalysisOrchestrator.applyVerifiedFix`
  (`AnalysisOrchestrator.kt:499`), so a correctly implemented fixer will derive and validate a plan, but
  nothing applies it to the user's file until that capability is enabled.

## 2. Step-by-Step Implementation

1. **Create Fixer File**: Create `src/main/kotlin/com/ghostdebugger/fix/YourNewFixer.kt`.
2. **Implement the `Fixer` Interface** (`fix/Fixer.kt`) — there is **no `derive` method on `Fixer`**; `derive`/
   `derivePlan` belong to `FixDeriver`, which *calls* your fixer, not the other way around. `ruleId` (must equal
   the corresponding `Analyzer`'s `ruleId`) and `description` are required properties; `generateFix` is the only
   required method:
```kotlin
class YourNewFixer : Fixer {
    override val ruleId = "AEG-YOUR-001"
    override val description = "One-sentence description of the transformation this fixer applies."

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? {
        // Find the target text, validate it can be rewritten safely, return CodeFix or null.
    }

    // Optional PSI-driven path, tried before generateFix — override only if you need it:
    // override fun generateFixFromPsi(issue: Issue, file: PsiFile): CodeFix? = null

    // Optional op-emitting path, tried before generateFixFromPsi — override only if you need it:
    // override fun generatePlan(issue: Issue, ctx: FixContext): FixPlan? = null
}
```
3. **Register**: add `YourNewFixer()` to the `listOf(...)` in `FixerRegistry` (`fix/FixerRegistry.kt:6-15`). A
   fixer that isn't in that list is never returned by `FixerRegistry.forIssue` (the lookup `FixDeriver` uses by
   default), no matter how correct its implementation.
4. **Write Unit Tests**: Add automated tests covering fix generation and PSI verification.
