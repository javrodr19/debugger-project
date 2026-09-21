---
title: "Fix-Preview UX (V3.3)"
type: "feature"
related_components:
  - "[[Deterministic_Fixers]]"
tags:
  - feature
  - fix-preview
  - aegis-debug
---

# Fix-Preview UX (V3.3)

The Fix-Preview UX computes color-coded line and hunk diffs comparing original source against a
proposed fix, across single or multiple files.

> **Status (3.0.0): implemented, no consumer — and the description below was previously wrong.**
> All three components are real, tested code with **zero callers outside their own file** anywhere
> in `src/main` — nothing in the product invokes them (`docs/IMPLEMENTATION_STATUS.md`, "Implemented,
> no consumer"). Read against `fix/engine/FixPreviewDialog.kt` directly: there is no `JSplitPane` and
> no side-by-side rendering anywhere in the file. `createCenterPanel` builds one `JTextPane` and
> `appendDiffToDocument`/`appendDiffLine` write a single scrollable **unified** diff into it —
> `+`/`-`/space-prefixed lines, colored green/red/gray (`FixPreviewDialog.kt:48-77`). There is no
> `KeyListener`, no `registerKeyboardAction`, and no mnemonic anywhere in the class — `Alt+A` does
> not exist as a binding. `Enter`/`Esc` do work, but only because `DialogWrapper` (the IntelliJ
> Platform base class) wires them to `okAction`/`cancelAction` for every dialog by default; this
> class adds no keyboard handling of its own beyond `createActions() = arrayOf(okAction,
> cancelAction)`. Even if this were wired up, it would currently open onto nothing to apply: fix
> *application* itself is gated (`FIX_APPLICATION` — see [[Deterministic_Fixers]]).

## Core Components
- **`FixDiffGenerator`**: Computes a **unified**, line-by-line diff — `DiffLine`/`DiffHunk` models
  tagged `ADDED`/`DELETED`/`UNCHANGED` — comparing original source against fixed output. There is no
  side-by-side (dual-pane) data model anywhere in this file; it produces one linear sequence of
  lines, not two aligned columns.
- **`BatchFixPreview`**: Computes multi-file batch fix diffs in memory (via `FixDiffGenerator`)
  without mutating files on disk.
- **`FixPreviewDialog`**: Swing `DialogWrapper` rendering the unified diff from `FixDiffGenerator`
  in a single scrollable `JTextPane`. No side-by-side view and no custom keyboard navigation exist
  in the current implementation — see the status note above.
