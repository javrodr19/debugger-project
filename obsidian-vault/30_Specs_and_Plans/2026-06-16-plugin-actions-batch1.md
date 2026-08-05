---
title: "Plugin Actions — Batch 1 — Implementation Plan (Stream D)"
type: "plan"
status: "active"
related_components: []
aliases: []
tags:
  - aegis-debug
---

# Plugin Actions — Batch 1 — Implementation Plan (Stream D)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or
> superpowers:executing-plans. Read `plans/2026-06-16-parallel-execution-coordination.md` first — this
> is **Stream D**. Work on branch `stream/plugin-actions`. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Expose already-shipped V2/V3 machinery as keyboard-reachable IDE actions — re-analyze the
current file, batch-apply verified fixes, navigate findings, and suppress — so users can act on a
finding without leaving the editor.

**Architecture:** Each action is a small `AnAction` (mirroring the existing `AnalyzeProjectAction`),
registered in `plugin.xml`'s `<actions>` block, that **reads facade state via
`GhostDebuggerService.getInstance(project)`** and triggers existing machinery (orchestrator,
`FixPlanApplicator`, `SuppressionMemoryService`). No new analysis/fix code. Spec:
`specs/2026-06-16-plugin-actions-product-roadmap-design.md`.

**Tech Stack:** IntelliJ `AnAction` / `AnActionEvent`, the `GhostDebuggerService` facade,
`fix/engine/FixPlanApplicator`, `store/SuppressionMemoryService`.

---

## ⚠️ Stream-D owned regions (do NOT edit outside these in shared files)

| Shared file | D edits ONLY | Owner of the rest |
|---|---|---|
| `src/main/resources/META-INF/plugin.xml` | the `<actions>` block (L308–331): add `<action>` entries | B (`<extensions>`) |
| `actions/ConfigureApiKeyAction.kt` + its `plugin.xml` `text=` | label rename only | — |

All other Stream-D files are **new** (`actions/*.kt`) — no conflict surface. D **reads** the facade /
fix-engine / suppression APIs; it does not modify A-owned fix-engine files.

---

## File map

| File | Create/Modify | Responsibility |
|---|---|---|
| `actions/ReanalyzeFileAction.kt` | Create | re-run analysis on the active file |
| `actions/ApplyAllFixesAction.kt` | Create | batch-apply deterministic fixes in the file (verify-gated, skip-on-fail) |
| `actions/NavigateFindingAction.kt` | Create | next/prev finding (two registered actions, one class param) |
| `actions/SuppressFindingAction.kt` | Create | suppress the finding under caret via `SuppressionMemoryService` |
| `actions/ConfigureApiKeyAction.kt` | Modify | label only (Track 0) |
| `plugin.xml` | Modify (`<actions>`) | register the new actions + editor-popup group |
| `src/test/.../actions/*Test.kt` | Create | behavior + registration tests |

---

## Task 0: Track-0 label fix — "Configure AI Provider"

**Files:** Modify `plugin.xml:328` (`text=`); optionally the action's own title constant

- [ ] **Step 1:** In `plugin.xml`'s `<actions>` block, change the `ConfigureApiKey` action's
  `text="Configure OpenAI API Key"` → `text="Configure AI Provider"` and the description to mention
  Ollama + OpenAI (it already manages both).

- [ ] **Step 2: Commit.**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "fix(actions): label 'Configure AI Provider' (manages Ollama + OpenAI, not just OpenAI)"
```

## Task 1: Re-analyze Current File

**Files:** Create `actions/ReanalyzeFileAction.kt`; Modify `plugin.xml` (`<actions>`); Test `…/ReanalyzeFileActionTest.kt`

- [ ] **Step 1: Failing registration+enablement test** (mirrors the existing
  `AegisLocalInspection` registration-test pattern):

```kotlin
class ReanalyzeFileActionTest : BasePlatformTestCase() {
    fun `test disabled with no open file, enabled for a supported file`() {
        val action = ReanalyzeFileAction()
        val noFile = TestActionEvent.createTestEvent(action)   // no editor
        action.update(noFile); assertFalse(noFile.presentation.isEnabled)

        myFixture.configureByText("A.kt", "fun f() {}")
        val withFile = TestActionEvent.createTestEvent(action, myFixture.editor.dataContext)
        action.update(withFile); assertTrue(withFile.presentation.isEnabled)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`ReanalyzeFileAction` undefined).

- [ ] **Step 3: Implement** `actions/ReanalyzeFileAction.kt` (reuse the orchestrator seam — if no
  single-file entry point exists yet, call the facade's analyze path scoped to the current
  `VirtualFile`; confirm the method name in `AnalysisOrchestrator` at execution):

```kotlin
package com.ghostdebugger.actions

import com.ghostdebugger.GhostDebuggerService
import com.intellij.openapi.actionSystem.*

class ReanalyzeFileAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = e.project != null && file != null &&
            file.extension in setOf("kt", "java", "ts", "js", "tsx", "jsx")
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        GhostDebuggerService.getInstance(project).analyzeFile(file)   // single-file path
    }
}
```

- [ ] **Step 4: Register** in `plugin.xml`'s `<actions>` block (Stream-D owned region) — add an
  editor-popup group so file actions surface where the cursor is:

```xml
        <action id="GhostDebugger.ReanalyzeFile"
                class="com.ghostdebugger.actions.ReanalyzeFileAction"
                text="Re-analyze Current File"
                description="Re-run Aegis analysis on the active file">
            <add-to-group group-id="EditorPopupMenu" anchor="last"/>
            <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt A"/>
        </action>
```

- [ ] **Step 5: Run → PASS.** If `analyzeFile` doesn't exist, add a thin single-file method to the
  facade/orchestrator (additive) delegating to the existing analysis path. **Commit.**

```bash
git add src/main/kotlin/com/ghostdebugger/actions/ReanalyzeFileAction.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/ghostdebugger/actions/ReanalyzeFileActionTest.kt
git commit -m "feat(actions): Re-analyze Current File (Ctrl+Alt+A, editor popup)"
```

## Task 2: Apply All Fixes in File (verify-gated, skip-on-fail)

**Files:** Create `actions/ApplyAllFixesAction.kt`; Modify `plugin.xml`; Test `…/ApplyAllFixesActionTest.kt`

- [ ] **Step 1: Failing test** — given a file with two deterministic-fixable findings, applying all
  fixes both deterministic findings; an unfixable/verify-failing one is **skipped, not half-applied**.

```kotlin
fun `test applies all deterministic fixes and skips a verify-failing one`() {
    val file = fixtureWith(twoFixable = true, oneVerifyFailing = true)
    val report = ApplyAllFixesAction().applyAll(project, file)
    assertEquals(2, report.applied)
    assertEquals(1, report.skipped)
    assertTrue(file.isPsiValid)          // no partial/broken edit
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** `ApplyAllFixesAction`: collect the file's findings from
  `GhostDebuggerService.getInstance(project).issuesByFile[file]`, filter to those with a
  **deterministic** fix (skip AI-supervised — those stay per-finding-with-review per the spec
  determinism boundary), and apply each via the existing `FixPlanApplicator` **inside one write
  action**, each independently verify-gated; collect `(applied, skipped)`. Show a `FixPlanPreview`
  before applying. Enable only when the file has fixable findings.

- [ ] **Step 4: Run → PASS** (2 applied, 1 skipped, PSI valid). **Register** in `plugin.xml`
  (`<actions>`, `EditorPopupMenu`). **Commit.**

```bash
git add src/main/kotlin/com/ghostdebugger/actions/ApplyAllFixesAction.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/ghostdebugger/actions/ApplyAllFixesActionTest.kt
git commit -m "feat(actions): Apply All Fixes in File (deterministic only, verify-gated, skip-on-fail)"
```

## Task 3: Next / Previous Finding navigation

**Files:** Create `actions/NavigateFindingAction.kt`; Modify `plugin.xml`; Test `…/NavigateFindingActionTest.kt`

- [ ] **Step 1: Failing test** — caret moves to the next finding's offset, ordered severity-then-offset.

```kotlin
fun `test next moves caret to the next finding offset`() {
    val file = fixtureWith(findingsAt = listOf(10, 40))
    myFixture.editor.caretModel.moveToOffset(0)
    NavigateFindingAction(forward = true).navigate(project, myFixture.editor, file)
    assertEquals(10, myFixture.editor.caretModel.offset)
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** `NavigateFindingAction(forward: Boolean)`: read
  `issuesByFile[file]` from the facade, sort by (severity, offset), move the caret to the
  next/previous relative to the current offset (wrap optional). Two registrations, one class.

- [ ] **Step 4: Run → PASS.** **Register** both directions in `plugin.xml`:

```xml
        <action id="GhostDebugger.NextFinding" class="com.ghostdebugger.actions.NavigateFindingAction"
                text="Next Aegis Finding" description="Jump to the next Aegis finding in this file">
            <keyboard-shortcut keymap="$default" first-keystroke="F2"/>
        </action>
        <action id="GhostDebugger.PrevFinding" class="com.ghostdebugger.actions.NavigateFindingAction"
                text="Previous Aegis Finding" description="Jump to the previous Aegis finding">
            <keyboard-shortcut keymap="$default" first-keystroke="shift F2"/>
        </action>
```
> If the no-arg constructor + per-id direction needs a different wiring than a ctor flag, read an
> existing two-id action at execution and follow its pattern. **Commit.**

```bash
git add src/main/kotlin/com/ghostdebugger/actions/NavigateFindingAction.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/ghostdebugger/actions/NavigateFindingActionTest.kt
git commit -m "feat(actions): Next/Previous Finding navigation (F2 / Shift+F2)"
```

## Task 4: Suppress Finding

**Files:** Create `actions/SuppressFindingAction.kt`; Modify `plugin.xml`; Test `…/SuppressFindingActionTest.kt`

- [ ] **Step 1: Failing test** — suppressing the finding under caret records it in
  `SuppressionMemoryService` so the next analysis hides it.

```kotlin
fun `test suppress records the finding in suppression memory`() {
    val file = fixtureWith(findingAt = 12)
    myFixture.editor.caretModel.moveToOffset(12)
    SuppressFindingAction().suppressAtCaret(project, myFixture.editor, file)
    assertTrue(SuppressionMemoryService.getInstance(project).isSuppressed(findingFingerprintAt(12)))
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** `SuppressFindingAction`: find the finding at the caret offset from the
  facade, call `SuppressionMemoryService.getInstance(project)`'s suppress API (confirm method name —
  the service "exposes active dismissals map" per its V2 settings UI), keyed by the finding
  fingerprint (+ rule id for `CUSTOM` findings). Enable only when a finding sits under the caret.

- [ ] **Step 4: Run → PASS.** **Register** in `plugin.xml` (`<actions>`, plus an Alt+Enter intention
  surface if trivial). **Commit.**

```bash
git add src/main/kotlin/com/ghostdebugger/actions/SuppressFindingAction.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/ghostdebugger/actions/SuppressFindingActionTest.kt
git commit -m "feat(actions): Suppress Finding (wires SuppressionMemoryService)"
```

## Task 5: Green bar + convention check

- [ ] **Step 1: Full green bar** (JBR env): `./gradlew test`, `./gradlew detekt`, `./gradlew verifyPlugin`.
  Expected: green / Compatible. `verifyPlugin` will flag any malformed `<action>` registration.

- [ ] **Step 2: Lens-1 convention check** — `aegis-convention-reviewer` over the branch diff. Expect no
  HIGH findings (facade read-only access; `ActionUpdateThread.BGT`; no PCE-swallowing catch).

- [ ] **Step 3: Final commit** (if any cleanup):

```bash
git commit --allow-empty -m "chore(actions): Batch 1 green bar (test + detekt + verifyPlugin)"
```

---

## Self-review (against the spec)

- Spec §3 Batch 1 (re-analyze file, apply-all, navigate, suppress) → Tasks 1–4 ✅
- Spec Track 0 (label fix) → Task 0 ✅
- Spec §2 principles: act-in-place (EditorPopupMenu + shortcuts) ✅; reuse machinery (FixPlanApplicator,
  SuppressionMemoryService) ✅; determinism boundary (apply-all = deterministic only, AI stays
  per-finding) ✅; `update()` discipline (`ActionUpdateThread.BGT`, enable-on-findings) ✅
- **Partition honored:** only the `<actions>` block + new `actions/*.kt` touched; `<extensions>` left to B.
- **Type consistency:** `issuesByFile`, `FixPlanApplicator`, `SuppressionMemoryService.getInstance`
  used consistently; facade accessed read-only via `getInstance`.

> Items needing an at-execution confirmation (honest gaps, additive if missing): a single-file
> `analyzeFile` entry on the facade/orchestrator (Task 1) and the exact `SuppressionMemoryService`
> suppress method name (Task 4) — both are small additive wirings, not A-owned-file edits.

## Execution handoff

Stream-D branch `stream/plugin-actions`. Integrate at coordination **Merge 4** (rebase on `main`
first; shares only the cleanly-partitioned `plugin.xml` `<actions>` section with B's `<extensions>`).
Subagent-driven recommended (one subagent per task).
