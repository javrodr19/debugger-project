---
title: "V2 Dynamic Validation"
type: "feature"
status: "active"
related_components:
  - "[[DebugSessionCoordinator]]"
  - "[[TestRunObserver]]"
  - "[[SuppressionMemoryService]]"
  - "[[ProblemsViewCoordinator]]"
tags:
  - feature
  - dynamic-validation
  - aegis-debug
---

# V2 Dynamic Validation

V2 adds non-destructive runtime validation to correlate static analysis findings with live execution state, eliminating false positives and building developer trust.

## Core Mechanisms
1. **`RUNTIME_CONFIRMED` Provenance Tier**: Findings confirmed at runtime via breakpoints or failing test paths receive the `RUNTIME_CONFIRMED` source tag and rank higher in issue lists.
2. **Debugger Session Cross-Check**: `DebugObserver` and `DebugObservationLogic` inspect runtime variable values at breakpoints via `DebugSessionCoordinator` to confirm or demote null-safety findings.
3. **Test Runner Cross-Check**: `AegisTestStatusListener`, `TestRunObserver`, and `TestRunCorrelation` monitor test suite executions and tag findings sitting on failing-test stack traces.
4. **False-Positive Suppression Memory**: `SuppressionMemoryService` tracks user dismissals and auto-hides unconfirmed findings across subsequent re-analyses.
5. **IntelliJ Problems Panel**: `ProblemsViewCoordinator` mirrors active issues to IntelliJ's native `WolfTheProblemSolver` tool window.
