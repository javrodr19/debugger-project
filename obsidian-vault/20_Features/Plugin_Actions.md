---
title: "Plugin Actions (Batches 1, 2, 3)"
type: "feature"
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

> **Corrected — shortcut claims.** Verified against `src/main/resources/META-INF/plugin.xml:333-410`:
> the *only* `<keyboard-shortcut>` element in the whole `<actions>` block is `ctrl alt G` on
> `GhostDebugger.Analyze` (`AnalyzeProjectAction`). None of `GhostDebugger.ReanalyzeFile`,
> `GhostDebugger.NextFinding`, or `GhostDebugger.PrevFinding` declares one — the two entries below
> that previously claimed `Ctrl+Alt+A` and `F2`/`Shift+F2` were wrong. All three actions are real
> and reachable via the Tools ▸ Aegis Debug menu / editor popup; they just have no bound key.

## Action Batches
- **Track 0**: Renamed AI Provider configuration label (`Configure AI Provider`).
- **Batch 1 (Core Inner Loop)**:
  - `ReanalyzeFileAction` — no keyboard shortcut registered.
  - `ApplyAllFixesAction` — registered and reachable, but **gated** under
    `AegisCapability.FIX_APPLICATION` in 3.0.0. Its entire `actionPerformed` body is now
    `AegisCapabilityGate.blockIfGated(e.project, AegisCapability.FIX_APPLICATION)`
    (`actions/ApplyAllFixesAction.kt:30`) — clicking it shows the roadmap dialog and does nothing
    else. See [[Deterministic_Fixers]].
  - `NextFindingAction` / `PrevFindingAction` — no keyboard shortcuts registered. Both extend the
    real base class `NavigateFindingAction` (`actions/NavigateFindingAction.kt`, `open class
    NavigateFindingAction(private val next: Boolean = true)`), which is itself not bound to any
    action id — only the two subclasses are registered (`GhostDebugger.NextFinding` /
    `GhostDebugger.PrevFinding`). The class name is real; the previously-claimed `F2`/`Shift+F2`
    shortcuts are not.
  - `SuppressFindingAction` — no keyboard shortcut registered; ungated and functional.
- **Batch 2 (Surfacing & Control)**:
  - `ToggleRuleAction`
  - `ShowInNeuroMapAction`
  - `ExportReportAction`
  - `CopyFindingForAIAction`
- **Batch 3 (Debug-Time Bridge)**:
  - `ConfirmDenyFindingAction`
