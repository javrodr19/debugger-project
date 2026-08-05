---
title: "Deterministic Fixers"
type: "feature"
status: "active"
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
- If a fixer can't guarantee PSI validity, it returns `null` and the AI fallback handles it. There are no "best effort" fixes.
- **Fix engine (V3):** Fix application flows through `FixEngine`. A `Fixer`'s `CodeFix` is adapted to a single-op `FixPlan` and applied by `FixPlanApplicator` through a PSI-validity gate.
- With V3, the AI becomes a planner/supervisor that composes deterministic engine operations and verifies them, instead of authoring raw fix code.

## Implementations
- Unsafe-cast fixer (Kotlin)
- Redundant-let fixer (Kotlin)
- Null-safety fixer (Kotlin)
- TS/JS fixers
