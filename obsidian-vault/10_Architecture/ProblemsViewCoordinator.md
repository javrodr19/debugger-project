---
title: "ProblemsViewCoordinator"
type: "architecture"
related_components:
  - "[[GhostDebuggerService]]"
  - "[[Roadmap]]"
aliases:
  - "ProblemsView"
tags:
  - aegis-debug
  - intellij
---

# ProblemsViewCoordinator

`ProblemsViewCoordinator` integrates Aegis Debug findings directly into IntelliJ IDEA's native **Problems** tool window.

## Purpose
- Allows keyboard-driven developers to view Aegis findings within standard IDE error trees without relying solely on the Aegis tool window.
- Listens to updates from [[GhostDebuggerService]] (`service.onIssuesUpdated`) and, when enabled, emits/removes problem descriptors dynamically via `WolfTheProblemSolver`.

## Gated in 3.0.0
`publishToProblemsView` (`ProblemsViewCoordinator.kt:37-38`) is a no-op behind `AegisCapability.PROBLEMS_VIEW_EMIT`, which ships disabled: "Publishing to the native Problems view violates the platform's threading contract on 2024.3." The listener `start()` registers (`ProblemsViewCoordinator.kt:27-31`) still fires on every issues update and still calls `publishToProblemsView`, but the gate check is the first line inside that function and short-circuits before touching `WolfTheProblemSolver`. In this release, findings are visible only in the Aegis Debug tool window and inline in the editor — not in the native Problems view.
