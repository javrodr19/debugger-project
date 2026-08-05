---
title: "Aegis Debug — Plugin Actions Product Roadmap ("real useful actions")"
type: "spec"
status: "active"
related_components: []
aliases: []
tags:
  - aegis-debug
---

# Aegis Debug — Plugin Actions Product Roadmap ("real useful actions")

**Date:** 2026-06-16
**Status:** Draft — forward-looking product roadmap for the IDE `AnAction` surface. Not infra
hygiene (that is the companion `…repo-git-actions-cleanup-design.md`); this is *what the user can
do* from menus, shortcuts, gutter, and Alt+Enter.
**Version target:** incremental, post-2.0.0. No binding acceptance criteria — a scope roadmap.

---

## 0. Summary

Aegis exposes only **three** IDE actions today — `AnalyzeProjectAction`, `ExplainSystemAction`,
`ConfigureApiKeyAction` — all under one "Aegis Debug" menu group. Meanwhile the V2/V3 work built a
lot of capability that has **no action surface**: a verified batch-fix engine, suppression memory, a
NeuroMap graph, a report exporter, inspection toggles. "Real useful actions" means **exposing the
machinery that already exists** as fast, keyboard-reachable actions where developers actually work —
not building new analysis.

The guiding test for every proposed action: **does it let the user act on a finding without leaving
the editor, and does it reuse already-built, already-safe machinery?**

---

## 1. Current inventory

| Action | id | Surface | Notes |
|---|---|---|---|
| Analyze Project | `GhostDebugger.Analyze` | `GhostDebugger.Menu` | whole-project only — no *current-file* re-run |
| Explain System | `GhostDebugger.ExplainSystem` | `GhostDebugger.Menu` | AI architecture explanation |
| Configure OpenAI API Key | `GhostDebugger.ConfigureApiKey` | `GhostDebugger.Menu` | **stale label** — provider is Ollama *or* OpenAI |

Existing surfaces to build on (already shipped): `intentions/AegisQuickFixIntentionAction` (Alt+Enter
per-finding fix), `ProblemsViewCoordinator` (native Problems panel), `AegisLocalInspection` (profile
toggles), the `fix/engine/` apply+verify path, `store/SuppressionMemoryService`, `ReportExporter` /
`ReportGenerator`, the `graph/` NeuroMap, and the `GhostDebuggerService` facade
(`currentIssues` / `issuesByFile`) that drives enablement.

---

## 2. Design principles

- **Act in place.** The highest-value actions live in the editor (gutter, Alt+Enter, context menu)
  and the Problems panel — not buried in a top-level menu.
- **Reuse built, safe machinery.** Batch fixes route through the same `FixPlanApplicator` verify gate
  as everything else; suppression uses `SuppressionMemoryService`; nothing here invents new analysis.
- **Respect the four principles.** Batch-apply applies **only deterministic/verified** fixes; AI
  fixes stay per-finding-with-review (no auto-apply, the standing non-goal). Provenance + confidence
  stay visible on every finding an action touches.
- **Correct `update()` discipline.** File-scoped actions enable only for a supported file with
  findings; `getActionUpdateThread() = BGT` to avoid EDT stalls reading facade state.

---

## 3. Proposed actions (prioritized)

### Batch 1 — Core workflow (highest leverage, all reuse shipped machinery)

| Action | What | Reuses | Surface / shortcut |
|---|---|---|---|
| **Re-analyze Current File** | Incremental re-run on the active file — the fast inner loop missing today | `AnalysisOrchestrator` single-file path | editor context menu + gutter; `Ctrl+Alt+A` |
| **Apply All Fixes in File** | Batch-apply every *deterministic* finding's fix in the file; each independently verified, **skip-on-fail** (never half-applies the batch) | `FixEngine` / `FixPlanApplicator` verify gate, `FixPlanPreview` | editor context menu; preview dialog first |
| **Next / Previous Finding** | Move caret to the next/prev finding in the file, severity-then-offset ordered | `GhostDebuggerService.issuesByFile` | `F2` / `Shift+F2` (re-mapped from default error-nav scope) |
| **Suppress Finding / Show Hidden** | Suppress the finding under caret; a toggle to reveal suppressed | `store/SuppressionMemoryService` | Alt+Enter + gutter; tool-window toggle |

### Batch 2 — Surfacing & control

| Action | What | Reuses | Surface |
|---|---|---|---|
| **Toggle Rule (Inspection)** | Enable/disable the originating rule from the finding | `AegisLocalInspection` profile | Alt+Enter submenu |
| **Show in NeuroMap** | Open the graph focused on the current file's / symbol's node | `graph/` NeuroMap tool window | editor context menu |
| **Export Report** | Export the current analysis as a report | `ReportExporter` / `ReportGenerator` | `GhostDebugger.Menu` + Problems-panel toolbar |
| **Copy Finding for AI** | Copy finding + surrounding context to the clipboard (revive the old "Copy for AI" affordance as a first-class action) | facade `currentIssues` | Alt+Enter + detail panel |

### Batch 3 — Debug-time (bridges into V4)

| Action | What | Reuses | Notes |
|---|---|---|---|
| **Confirm / Deny at Breakpoint** | While paused, confirm or demote the finding on the current line from observed runtime state | `DebugSessionCoordinator` / `DebugObserver` | Explicitly a **V4 bridge** — sequenced last; depends on V4 debug-UX work |

### Track 0 — A tiny cleanup (do alongside Batch 1)

- Rename `ConfigureApiKeyAction`'s label **"Configure OpenAI API Key" → "Configure AI Provider"**
  (it already manages Ollama *and* OpenAI — the label is stale and narrows perceived scope).

---

## 4. Cross-cutting concerns

- **Action IDs & groups.** Keep the `GhostDebugger.<Verb>` id convention. Add two non-menu groups:
  `GhostDebugger.EditorPopup` (registered into `EditorPopupMenu`) and gutter/intention surfaces, so
  file-scoped actions live where the cursor is, not only in the top menu.
- **Keymap.** Ship sensible defaults that don't collide with platform bindings; all reassignable.
  Finding-navigation re-scopes `F2` only within Aegis context, falling back to platform error-nav.
- **Enablement (`update()`).** Enable file actions only for TS/JS/Kotlin/Java files; enable
  fix/suppress/navigate only when `issuesByFile[file]` is non-empty; `ActionUpdateThread.BGT`.
- **Determinism boundary.** "Apply All Fixes" filters to deterministic-fix findings; AI-supervised
  fixes remain individual, reviewed applies (`AegisQuickFixIntentionAction`). This keeps the
  "deterministic fixes only / no auto-apply" guarantees intact at the batch level.
- **Single-writer state.** Actions read facade state via `GhostDebuggerService.getInstance(project)`
  and mutate only through its mutators (CLAUDE.md facade-ownership invariant) — an action is a
  *reader/trigger*, never a new state owner.

---

## 5. Sequencing & rationale

```
Batch 1 (core loop: re-analyze-file, apply-all, navigate, suppress) + Track 0 label fix
   → Batch 2 (surfacing: toggle-rule, NeuroMap, export, copy-for-AI)
       → Batch 3 (debug-time confirm/deny — gated on V4)
```

- **Batch 1 first** — it closes the everyday loop (find → navigate → fix/suppress → re-check) that
  three project-level menu actions can't, and every item reuses already-shipped, already-safe code.
- **Batch 2 second** — surfacing/control polish on top of the core loop.
- **Batch 3 last** — it depends on V4's debug-time UX; listed here so the action surface is designed
  with the runtime bridge in mind, not retrofitted.

Each batch is independently shippable and testable behind its own writing-plans cycle.

---

## 6. Success criteria

1. From a finding in the editor, the user can re-analyze the file, jump between findings, apply a
   verified fix (or all of them), and suppress — all by keyboard, without the top-level menu.
2. "Apply All Fixes" never half-applies: each fix is independently verified; failures are skipped and
   reported, not silently dropped or partially written.
3. Every new action reuses existing machinery (no new analyzer/fixer/graph code) and respects the
   facade single-writer + determinism + no-auto-apply invariants.
4. The stale "OpenAI API Key" label is gone.

---

## 7. Non-goals

- **Auto-applying fixes without review** — standing roadmap non-goal; batch-apply still previews.
- **Actions that need unbuilt features** — custom-rule actions wait on V3.1; full debug-time UX is V4.
- **New analyzers / fixers / languages** — covered by other roadmaps; this is purely the *action*
  surface over existing capability.
- **Re-skinning the tool window** — UX-chrome work, out of scope for the action roadmap.

---

## 8. Hand-off

Batch 1 + Track 0 is the natural first writing-plans target (highest value, lowest risk, all reuse).
Next step: invoke writing-plans for **Batch 1** when this roadmap is approved. Batches 2–3 follow as
their own cycles; Batch 3 is sequenced after V4's debug-time work begins.
