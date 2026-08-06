---
title: "Plugin Actions (Batches 1, 2, 3)"
type: "feature"
status: "active"
related_components:
  - "[[GhostDebuggerService]]"
  - "[[Rule_Packs]]"
tags:
  - feature
  - actions
  - aegis-debug
---

# Plugin Actions

Aegis Debug exposes editor and menu actions integrated into IntelliJ's keymap, `ToolsMenu`, and `EditorPopupMenu`.

## Action Batches
- **Track 0**: Renamed AI Provider configuration label (`Configure AI Provider`).
- **Batch 1 (Core Inner Loop)**:
  - `ReanalyzeFileAction` (`Ctrl+Alt+A`)
  - `ApplyAllFixesAction`
  - `NavigateFindingAction` (`F2` / `Shift+F2`)
  - `SuppressFindingAction`
- **Batch 2 (Surfacing & Control)**:
  - `ToggleRuleAction`
  - `ShowInNeuroMapAction`
  - `ExportReportAction`
  - `CopyFindingForAIAction`
- **Batch 3 (Debug-Time Bridge)**:
  - `ConfirmDenyFindingAction`
