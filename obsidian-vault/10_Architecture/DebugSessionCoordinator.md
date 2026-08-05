---
title: "DebugSessionCoordinator"
type: "architecture"
status: "active"
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
