---
title: "V2 Dynamic Validation"
type: "feature"
related_components:
  - "[[DebugSessionCoordinator]]"
  - "[[ProblemsViewCoordinator]]"
source_files:
  - "src/main/kotlin/com/ghostdebugger/store/TestRunObserver.kt"
  - "src/main/kotlin/com/ghostdebugger/store/SuppressionMemoryService.kt"
tags:
  - feature
  - dynamic-validation
  - aegis-debug
---

# V2 Dynamic Validation

V2 adds non-destructive runtime validation to correlate static analysis findings with live execution state, eliminating false positives and building developer trust.

> **Gating status (3.0.0) — mixed, not uniform.** Of the five mechanisms below, two are gated
> (Debugger Session Cross-Check, IntelliJ Problems Panel) and three are not (Test Runner
> Cross-Check, False-Positive Suppression Memory, and the `RUNTIME_CONFIRMED` tag itself as a
> mechanism). Do not describe the debugger and test-runner cross-checks as both gated, or both
> live — they are on opposite sides of the gate, and that exact conflation was made once already
> in this release per `docs/IMPLEMENTATION_STATUS.md`.

## Core Mechanisms
1. **`RUNTIME_CONFIRMED` Provenance Tier**: findings sitting on a failing-test stack trace frame
   receive the `RUNTIME_CONFIRMED` source tag and `confidence = 1.0`
   (`AnalysisOrchestrator.handleTestSuiteFinished`). Breakpoint-based confirmation uses the same
   tag conceptually but is currently unreachable — see (2) — so in this release the tag is only
   ever applied via the test-runner path, not via a paused debugger.
2. **Debugger Session Cross-Check** — **gated** under `AegisCapability.DEBUGGER_CROSS_CHECK`.
   `DebugObserver.evaluateRelevantFindingsAtCurrentFrame` (`store/DebugObserver.kt:73-74`) and
   `DebugSessionCoordinator.performDebugSessionCrossCheck` (`DebugSessionCoordinator.kt:153-154`)
   both open with `AegisCapabilityGate.skipIfGated(AegisCapability.DEBUGGER_CROSS_CHECK)` and
   return immediately. `DebugObserver`'s message-bus subscription still fires on every session
   pause, but the gate is the first line of the method that subscription calls, so no evaluation
   ever happens and no runtime evidence is ever produced from a debug session in 3.0.0. Verbatim
   from `AegisCapability.kt`: "Runtime confirmation from a paused debug session is not reachable on
   IntelliJ IDEA Community. Test-suite cross-check remains active."
3. **Test Runner Cross-Check — not gated, runs unconditionally.** `AegisTestStatusListener` and
   `TestRunObserver` (registered as a project service and started from
   `GhostDebuggerService.kt:122`) subscribe to `SMTRunnerEventsListener.TEST_STATUS` with no
   `AegisCapabilityGate` call anywhere in either file or in `TestRunCorrelation`/
   `AnalysisOrchestrator.handleTestSuiteFinished`. This is the one runtime-confirmation path that
   is actually live in 3.0.0.
4. **False-Positive Suppression Memory** — **not gated.** `SuppressionMemoryService` has no
   `AegisCapabilityGate` call; `SuppressFindingAction` reaches it directly and is listed in
   `docs/IMPLEMENTATION_STATUS.md`'s Works table.
5. **IntelliJ Problems Panel** — **gated** under `AegisCapability.PROBLEMS_VIEW_EMIT`.
   `ProblemsViewCoordinator.publishToProblemsView` (`ProblemsViewCoordinator.kt:38`) opens with
   `AegisCapabilityGate.skipIfGated(AegisCapability.PROBLEMS_VIEW_EMIT)` and returns before
   touching `WolfTheProblemSolver`. Verbatim from `AegisCapability.kt`: "Publishing to the native
   Problems view violates the platform's threading contract on 2024.3. Findings are available in
   the Aegis Debug tool window and in the editor."
