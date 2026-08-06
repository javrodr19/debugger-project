---
title: "Fix-Preview UX (V3.3)"
type: "feature"
status: "active"
related_components:
  - "[[Deterministic_Fixers]]"
tags:
  - feature
  - fix-preview
  - aegis-debug
---

# Fix-Preview UX (V3.3)

The Fix-Preview UX provides interactive, color-coded line and hunk diff previews before applying code modifications across single or multiple files.

## Core Components
- **`FixDiffGenerator`**: Generates line-by-line and side-by-side diff hunks comparing original source code against fixed output.
- **`BatchFixPreview`**: Computes multi-file batch fix diffs in memory without mutating files on disk.
- **`FixPreviewDialog`**: Swing `DialogWrapper` providing side-by-side and unified diff views with keyboard navigation (`Enter`/`Alt+A` to Apply, `Esc` to Skip).
