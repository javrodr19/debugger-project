---
title: "AnalysisOrchestrator"
type: "architecture"
status: "active"
related_components:
  - "[[GhostDebuggerService]]"
aliases: []
tags:
  - aegis-debug
  - kotlin
---

# AnalysisOrchestrator

`AnalysisOrchestrator` is one of the four main collaborators managed by [[GhostDebuggerService]].

## Responsibilities
- Handles the analysis lifecycle.
- Manages the dependent cascade analysis on `reanalyzeFile`.
- Encapsulates test seams for analyzers.
- Executes both the static analysis (early and late phases) and coordinates the AI augmentation passes.
- Must ensure that it writes its findings back through `GhostDebuggerService.updateIssues(...)` rather than mutating state directly.

## Lifecycle Details
Analysis runs in static phases (e.g., syntax/compilation checking early, followed by deeper structural analyzers like Null Safety and Circular Dependencies), followed by an optional AI pass using `BaseAIService`.
