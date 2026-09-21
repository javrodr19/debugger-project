---
title: "DebugSessionCoordinator"
type: "architecture"
related_components:
  - "[[GhostDebuggerService]]"
aliases: []
tags:
  - aegis-debug
  - kotlin
---

# DebugSessionCoordinator

`DebugSessionCoordinator` is one of the four main collaborators managed by [[GhostDebuggerService]].

## Responsibilities
- Plumbs into the `XDebuggerManagerListener` to observe active debug sessions.
- Acts as the primary mechanism for V2's **Dynamic Validation Pass**.
- Observes variable values at breakpoints to confirm or deny relevant static analysis findings in real-time (e.g., verifying if a variable flagged as potentially null is actually null).
- Elevates static findings to the `RUNTIME_CONFIRMED` provenance tier if proven during a debug session, or demotes them if the runtime state disproves the finding.

## Gated in 3.0.0
The cross-check itself — `performDebugSessionCrossCheck` (`DebugSessionCoordinator.kt:153-154`) — is a no-op behind `AegisCapability.DEBUGGER_CROSS_CHECK`, which ships disabled: its `why` states that runtime confirmation from a paused session "is not reachable on IntelliJ IDEA Community." The debug-frame plumbing that calls it (`sendCurrentDebugFrame`: pushing current file/line/variables to the NeuroMap while paused) is itself ungated and still runs; only the promote/demote step is blocked. The *other* dynamic-validation path — promoting findings to `RUNTIME_CONFIRMED` from failed-test stack traces (`AnalysisOrchestrator.handleTestSuiteFinished`) — is a separate, ungated mechanism and remains active, per the same capability's `why` text ("Test-suite cross-check remains active").
