---
title: "AnalysisOrchestrator"
type: "architecture"
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
- Owns fix application: `applyVerifiedFix` (`AnalysisOrchestrator.kt:490`) routes a verified fix through `FixEngine.fixSupervised` (see [[FixEngine]]). In 3.0.0 this is gated behind `AegisCapability.FIX_APPLICATION`, which ships disabled — the guard at `AnalysisOrchestrator.kt:499` (`AegisCapabilityGate.blockIfGated`) shows the roadmap dialog and returns before any fix runs.

## Lifecycle Details
Analysis runs in static phases (e.g., syntax/compilation checking early, followed by deeper structural analyzers like Null Safety and Circular Dependencies), followed by an AI pass using `BaseAIService` when a provider is configured. In 3.0.0 that AI pass is additionally gated off unconditionally by `AegisCapability.AI_ANALYSIS` (`AnalysisEngine.kt:215`, inside `AnalysisEngine.runAiPass`) — regardless of settings, `analyze()` returns a static-only result with an `EngineStatus.DISABLED` status payload. So today "coordinates the AI augmentation passes" means invoking a pass that is currently a guaranteed no-op, not that AI findings appear.
