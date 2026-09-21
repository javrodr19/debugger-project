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

## Details
- If a fixer can't guarantee PSI validity it returns `null` rather than guessing. There are no "best effort" fixes.
- **Fix engine (V3, shipped):** fix application flows through `FixEngine`. A `Fixer`'s `CodeFix` is adapted to a single-op `FixPlan` and applied by `FixPlanApplicator` behind a Tier-1 PSI-validity gate and a Tier-2 re-analysis gate.
- When no deterministic plan applies, `FixEngine.fixSupervised` asks the AI for a `FixPlan` — the AI **proposes operations the engine already knows how to apply, and never authors raw fix code**. Acceptance stays with the deterministic gate; rejection reasons are fed back as planner feedback. See [[FixEngine]].

## Implementations
- Unsafe-cast fixer (Kotlin)
- Redundant-let fixer (Kotlin)
- Null-safety fixer (Kotlin)
- TS/JS fixers
