---
title: "GhostDebuggerService"
type: "architecture"
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
- Enforces strict state ownership, but the mechanism differs by field: `currentIssues`/`issuesByFile` are read via `service.X` and mutated only through `service.updateIssues(...)` (`GhostDebuggerService.kt:73-92`). `currentGraph`, `lastInMemoryGraph`, and `suppressUntil` are plain `internal var` / `var` fields (no `private set`) that collaborators assign directly instead — e.g. `svc.currentGraph = projectGraph` in `AnalysisOrchestrator`, `svc.suppressUntil = ...` in `UIEventRouter`. This is still facade-owned, collaborator-only state (never assigned from outside the facade's own collaborators), just not routed through a mutator method; the facade's own doc comment (`GhostDebuggerService.kt:31-35`) scopes the "mutate via `updateIssues`" rule to `currentIssues`/`issuesByFile` specifically, not to all state.

## Historical Context
In V1.4, this class was a massive god class (~918 LOC). It was refactored in V1.5 to shrink to ~150 LOC to prepare for V2's dynamic validation pass, ensuring a clean separation of concerns before V2 landed.
