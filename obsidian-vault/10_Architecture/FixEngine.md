---
title: "FixEngine"
type: "architecture"
related_components:
  - "[[Deterministic_Fixers]]"
  - "[[Fix_Preview_UX]]"
  - "[[Creating_New_Fixers_Guide]]"
aliases:
  - "Fix Engine"
  - "FixPlan"
tags:
  - aegis-debug
  - v3
  - fixes
---

# FixEngine

`src/main/kotlin/com/ghostdebugger/fix/engine/` — the single entry point for applying fixes.

## Purpose

Before V3, a `Fixer` produced a `CodeFix` that was applied directly. Fix *application* now routes
through `FixEngine` instead, so every candidate edit — whether it came from a deterministic fixer or
from the AI — passes through the same gates before it touches a file.

That indirection is the whole point: acceptance is decided by deterministic verification, never by
whoever proposed the edit.

## Two gates

**Tier-1 — PSI validity** (`FixPlanApplicator.kt:47`). Applies the edits inside a write action on the
EDT, commits the PSI, and looks for a `PsiErrorElement`. If one appears, the change is reverted and
rejected. This upholds the project's long-standing rule that a fixer must produce parse-clean output
or nothing at all.

**Tier-2 — re-analysis** (`FixPlanApplicator.applyVerified`, `FixPlanApplicator.kt:132`). With the
candidate content in place, the file is statically re-analysed (`SingleFileStaticReanalysis`) and an
acceptance function returns a `VerifyDecision` — `Accept` or `Reject(reason)` (`FixVerifier.kt:6`).
Rejection reverts.

## Entry points (`FixEngine.kt`)

| Function | Behaviour |
|---|---|
| `planFor(issue, virtualFile, content)` | Derives the deterministic `FixPlan`, or `null` if no fixer applies |
| `apply(plan, virtualFile)` | Applies an already-derived plan (Tier-1 only) |
| `fix(...)` | Derive + apply |
| `fixVerified(...)` | Derive + apply behind Tier-1 **and** Tier-2 |
| `fixSupervised(...)` | The AI-supervised loop — see below |

## AI-supervised fixing — shipped, gated

`fixSupervised` (`FixEngine.kt:77`) is wired into production at `AnalysisOrchestrator.kt:497` — the default `fixVerified` argument inside `applyVerifiedFix`. That method's only production callers are the two native editor fix paths: the Alt+Enter quick-fix intention (`intentions/AegisQuickFixIntentionAction.kt:51`) and the local-inspection quick-fix (`inspections/AegisLocalInspection.kt:31`, `AegisLocalQuickFix.applyFix`). In 3.0.0 that whole path is gated: `applyVerifiedFix` blocks first, before deriving or applying anything (`AnalysisOrchestrator.kt:499`, `AegisCapabilityGate.blockIfGated` on `AegisCapability.FIX_APPLICATION`, which ships disabled). A user who triggers either quick-fix sees the roadmap dialog, not a fix — the engine is fully implemented and tested, just not reachable from the shipped UI yet.

The loop itself:

1. Try the deterministic plan first, through the Tier-2 gate.
2. If there is no deterministic plan, or the gate rejects it, ask the AI for a `FixPlan` via
   `aiService.proposeFixPlan(issue, content, feedback)`, up to `maxAiAttempts` (default 2).
3. Each rejection reason is fed back to the planner as `feedback` on the next attempt.
4. Every AI candidate goes through the *same* Tier-2 gate.
5. Return the first `Success`, or the last rejection.

Two properties worth stating plainly, because they are what make this safe:

- **The AI proposes a `FixPlan`, never raw fix code.** It composes the deterministic operations the
  engine already knows how to apply.
- **Acceptance is fully deterministic.** The AI only proposes and revises; the gate decides. With
  `aiService == null` the function reduces to exactly the deterministic verified path, so the feature
  is AI-optional.

**The NeuroMap webview's own "Apply Fix" button is a *separate* path and does not go through
`fixSupervised`.** `UIEvent.ApplyFixRequested` → `UIEventRouter.handleApplyFixRequested` calls
`FixEngine.fixVerified` directly (Tier-1 + Tier-2, no AI supervision, no fallback to the AI planner).
It is independently gated at dispatch — `UIEventRouter.handle` blocks `FixRequested`/`ApplyFixRequested`
before either handler runs (`UIEventRouter.kt:57-58`, via `gatedCapabilityFor`) — see [[UIEventRouter]].

## Key types

- `FixPlan.kt:7` — `data class FixPlan(issueId: String, operations: List<FixOperation>)`
- `FixOperation.kt:19` — sealed class with 17 subclasses (`ReplaceRange`, `InsertImport`,
  `ConvertToSafeCast`, `AddElvisDefault`, `WrapInSafeCall`, `SurroundWithNullCheck`, `AddAwait`,
  `AddPromiseCatch`, `AddExplicitConversion`, `SurroundWithTryCatch`, `RemoveRange`,
  `ReplaceExpression`, `InsertStatementBefore`, `InsertStatementAfter`, `CollapseBooleanReturn`,
  `ReplaceLines`, `InsertLinesAfter`)
- `CodeFixAdapter.kt:18` — `CodeFix.toFixPlan(content): FixPlan?`, the adapter that lets an existing
  `Fixer` feed the engine as a single-operation plan
- `FixPlanApplicator.kt` — owns both gates
- `FixVerifier.kt:24` — the default acceptance function
- `FixOperationCatalog.kt:9` — the operation vocabulary published to the AI planner, plus
  `serialNames()` for validating what comes back

## Complexity acceptance

`AEG-CPX-001` gets a dedicated acceptance function (`FixEngine.kt:138`) that dispatches on what the
candidate actually did: if it added a function, `ExtractMethodVerifier` checks per-function
decomposition; if it edited in place, `ComplexityVerifier` requires the file average to drop. The
threshold is read live from settings, the same source `ComplexityAnalyzer` uses. JS/TS candidates are
additionally checked for balanced delimiters (`JsTsStructuralCheck`) before measurement.

## Invariants

- A fixer produces PSI-valid output or returns `null` — it never guesses. See
  [[Deterministic_Fixers]].
- `ProcessCanceledException` is rethrown before any other exception handling. See
  [[Claude_Conventions]].
