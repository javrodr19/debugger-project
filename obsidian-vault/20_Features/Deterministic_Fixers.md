---
title: "Deterministic Fixers"
type: "feature"
related_components:
  - "[[AnalysisOrchestrator]]"
aliases:
  - "Fixers"
tags:
  - aegis-debug
---

# Deterministic Fixers

A core pillar of Aegis Debug is **Deterministic fixes only**.

> **Gating status (3.0.0):** the engine described below is real, implemented, and tested — but fix
> *application* is gated off by `AegisCapability.FIX_APPLICATION`. `AnalysisOrchestrator.applyVerifiedFix`
> checks `AegisCapabilityGate.blockIfGated(project, AegisCapability.FIX_APPLICATION)` first
> (`AnalysisOrchestrator.kt:499`) and returns a no-op job if gated, before `fixSupervised` is ever
> called. `ApplyAllFixesAction.actionPerformed` (`actions/ApplyAllFixesAction.kt:30`) does the same —
> its entire body is now just the gate check; the action shows the roadmap dialog and does nothing
> else. Verbatim from `AegisCapability.kt`: "Aegis Debug 3.0 ships as a read-only analysis tool. The
> fix engine is implemented and tested, but fix application is not enabled in this release." With a
> null `AIService` the flow below still reduces correctly to the deterministic-only path — that part
> of the design is unaffected by the gate — but no fix, deterministic or AI-planned, is currently
> written to a file through the product UI.

## Details
- If a fixer can't guarantee PSI validity it returns `null` rather than guessing. There are no "best effort" fixes.
- **Fix engine (V3, shipped):** fix application flows through `FixEngine`. A `Fixer`'s `CodeFix` is adapted to a single-op `FixPlan` and applied by `FixPlanApplicator` behind a Tier-1 PSI-validity gate and a Tier-2 re-analysis gate.
- When no deterministic plan applies, `FixEngine.fixSupervised` asks the AI for a `FixPlan` — the AI **proposes operations the engine already knows how to apply, and never authors raw fix code**. Acceptance stays with the deterministic gate; rejection reasons are fed back as planner feedback. See [[FixEngine]].

## Implementations
8 fixers are registered in `FixerRegistry.kt`, keyed by `ruleId`:
- `KotlinUnsafeCastFixer` (`AEG-CAST-KT-001`)
- `KotlinRedundantLetFixer` (`AEG-REDUNDANT-LET-KT-001`)
- `KotlinNullSafetyFixer` (`AEG-NULL-KT-001`)
- `KotlinTypeMismatchFixer` (`AEG-TYPE-KT-001`)
- `ComplexitySimplifierFixer` (`AEG-CPX-001`)
- `NullSafetyFixer` (`AEG-NULL-001`, JS/TS)
- `StateInitFixer` (`AEG-STATE-001`, JS/TS)
- `AsyncFlowFixer` (`AEG-ASYNC-001`, JS/TS)
