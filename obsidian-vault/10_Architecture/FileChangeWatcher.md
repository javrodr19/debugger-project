---
title: "FileChangeWatcher"
type: "architecture"
status: "active"
related_components:
  - "[[GhostDebuggerService]]"
  - "[[AnalysisOrchestrator]]"
aliases: []
tags:
  - aegis-debug
  - kotlin
---

# FileChangeWatcher

`FileChangeWatcher` is one of the four main collaborators managed by [[GhostDebuggerService]].

## Responsibilities
- Listens to the Virtual File System (VFS) events from IntelliJ IDEA.
- Triggers auto-refresh or dependent cascade re-analysis when files are changed on disk or in the editor.
- Ensures that unsaved editor changes are also reflected in the analysis (a bug fixed in V1.1.1 now correctly sources file text from the live IDE `Document`).
