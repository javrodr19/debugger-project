---
title: "ProblemsViewCoordinator"
type: "architecture"
status: "active"
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
- Listens to updates from [[GhostDebuggerService]] and emits/removes problem descriptors dynamically.
