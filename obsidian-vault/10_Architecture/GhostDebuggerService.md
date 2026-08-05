---
title: "GhostDebuggerService"
type: "architecture"
status: "active"
related_components:
  - "[[AnalysisOrchestrator]]"
  - "[[UIEventRouter]]"
  - "[[FileChangeWatcher]]"
  - "[[DebugSessionCoordinator]]"
aliases:
  - "Facade"
tags:
  - aegis-debug
  - kotlin
---

# GhostDebuggerService

`GhostDebuggerService` is the single source of truth facade for project state in Aegis Debug (post V1.5 refactor). It is registered as `@Service(Service.Level.PROJECT)`.

## Responsibilities
- Owns the core state: `currentIssues`, `issuesByFile`, `currentGraph`, `lastInMemoryGraph`, `suppressUntil`.
- Acts as a **thin facade** over four main collaborators: [[AnalysisOrchestrator]], [[UIEventRouter]], [[FileChangeWatcher]], and [[DebugSessionCoordinator]].
- Enforces strict state ownership: No collaborator can mutate state directly. All reads happen via `service.X` and all mutations happen via `service.updateIssues(...)`.

## Historical Context
In V1.4, this class was a massive god class (~918 LOC). It was refactored in V1.5 to shrink to ~150 LOC to prepare for V2's dynamic validation pass, ensuring a clean separation of concerns before V2 landed.
