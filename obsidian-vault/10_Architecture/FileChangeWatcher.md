---
title: "FileChangeWatcher"
type: "architecture"
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
- Debounces relevant content-change events (7s, `scheduleAutoRefresh`, `FileChangeWatcher.kt:77-86`) and then triggers a full `AnalysisOrchestrator.analyzeProject()` re-analysis — **not** the dependent-cascade path. The cascade (`AnalysisOrchestrator.reanalyzeFile` → `cascadeDependents`) is a separate mechanism invoked elsewhere (e.g. after a verified fix is applied), not by this watcher.
- The re-analysis it triggers picks up unsaved editor changes (a bug fixed in V1.1.1), but the sourcing fix itself lives in `FileScanner.parsedFiles()` (`parser/FileScanner.kt:83`, reading from the live IDE `Document` via `FileDocumentManager` when one is open) — not in this class.
