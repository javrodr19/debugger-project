# Fix Engine Phase 2c-ii-b — Planner Preview + Retire Free-Form Fix Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the AI fix *suggestion/preview* path to render a planner (`proposeFixPlan`) result, then retire the free-form `suggestFix` / `parseFixResponse` code path entirely — completing the AI-supervised fix engine so the AI never authors raw fix code anywhere.

**Architecture:** `FixPlanPreview.render` turns a `FixPlan` into a before/after `CodeFix` by computing the plan's edits against the file content (read-only PSI for PSI-based ops) and applying them to a text copy. `UIEventRouter`'s AI fix-suggestion fallback (used when no deterministic fixer exists) calls `proposeFixPlan` + `FixPlanPreview.render` instead of `suggestFix`. Once that last caller is gone, `suggestFix` / `parseFixResponse` (and `PromptTemplates.suggestFix`) are deleted from `AIService` / `BaseAIService` / `PromptTemplates`, and the tests that pinned them are removed or re-pointed.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform (IPGP 2.14.0, 2024.3.2), kotlinx-coroutines, JUnit4 `BasePlatformTestCase` + JUnit5 (`org.junit.jupiter`) for the AI-service tests.

---

## Prerequisites (test prelude)

Tests require a JetBrains Runtime; shell env does NOT persist between Bash commands. Prefix EVERY gradlew invocation inline:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlan.kt` — `fun toEdits(ctx: FixContext): List<TextEdit>?`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixContext.kt` — `class FixContext(val content: String, psiProvider: () -> PsiFile?)`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/TextEdit.kt` (or in FixPlan/Operation file) — `TextEdit(startOffset, endOffset, replacement)`.
- `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt:58` — `CodeFix(id, issueId, description, originalCode, fixedCode, filePath, lineStart, lineEnd, isDeterministic=false, confidence=0.7)`.
- `src/main/kotlin/com/ghostdebugger/ai/AIService.kt` — `proposeFixPlan(...)` (2c-ii-a, default null) and the legacy `suggestFix(issue, codeSnippet): CodeFix` (line 25, to remove).
- `src/main/kotlin/com/ghostdebugger/ai/BaseAIService.kt` — `suggestFix` (≈123) and `protected fun parseFixResponse(...)` (≈166) to remove.
- `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt:82` — `suggestFix(...)` prompt to remove.
- `src/main/kotlin/com/ghostdebugger/UIEventRouter.kt:215-233` — the AI fix-suggestion fallback block (currently `ai.suggestFix`). Note the existing `catch (e: Exception)` there does NOT rethrow `ProcessCanceledException` — fix that while migrating.
- `src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt:169` — `fun sendFixSuggestion(fix: CodeFix)`.
- Tests pinning the legacy path: `src/test/kotlin/com/ghostdebugger/ai/OpenAIServiceTimeoutTest.kt` (only a `parseFixResponse` reflection test), `OllamaServiceParseTest.kt` (only a `parseFixResponse` reflection test), `BaseAIServiceTest.kt:84-93` (`suggestFix cache hit …`), `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineOllamaDelegationTest.kt:41` (fake `suggestFix` override), `src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineSupervisedTest.kt:26` (fake `suggestFix` override).

## File structure

- **Create** `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanPreview.kt`
- **Create** `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanPreviewTest.kt`
- **Modify** `src/main/kotlin/com/ghostdebugger/UIEventRouter.kt` — AI fallback uses the planner.
- **Modify** `src/main/kotlin/com/ghostdebugger/ai/AIService.kt`, `BaseAIService.kt`, `prompts/PromptTemplates.kt` — remove the legacy methods.
- **Delete** `src/test/kotlin/com/ghostdebugger/ai/OpenAIServiceTimeoutTest.kt`, `OllamaServiceParseTest.kt`.
- **Modify** `BaseAIServiceTest.kt`, `AnalysisEngineOllamaDelegationTest.kt`, `FixEngineSupervisedTest.kt` — drop/repoint legacy references.
- **Modify** `docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md` — §9 mark the arc complete.

---

### Task 1: `FixPlanPreview.render` — render a `FixPlan` as a before/after `CodeFix`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanPreview.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanPreviewTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FixPlanPreviewTest : BasePlatformTestCase() {

    fun testRendersPlanAsBeforeAfterCodeFix() {
        val content = "fun f(): Int { return 1 }\n"
        val vf = myFixture.configureByText("A.kt", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val start = text.indexOf("return 1")
        val plan = FixPlan("i1", listOf(ReplaceRange(start, start + "return 1".length, "return 2")))
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
            title = "t", description = "", filePath = vf.path, line = 1, ruleId = "AEG-X"
        )

        val cf = FixPlanPreview.render(plan, project, vf, content, issue)!!

        assertEquals("i1", cf.issueId)
        assertEquals(content, cf.originalCode)
        assertTrue(cf.fixedCode, cf.fixedCode.contains("return 2"))
        assertFalse(cf.fixedCode.contains("return 1"))
        assertFalse(cf.isDeterministic)
    }

    fun testReturnsNullWhenPlanDoesNotApply() {
        val content = "fun f() {}\n"
        val vf = myFixture.configureByText("A.kt", content).virtualFile
        // Offsets out of range -> ReplaceRange.toEdit returns null -> toEdits null -> render null.
        val plan = FixPlan("i1", listOf(ReplaceRange(9999, 10000, "x")))
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
            title = "t", description = "", filePath = vf.path, line = 1, ruleId = "AEG-X"
        )
        assertNull(FixPlanPreview.render(plan, project, vf, content, issue))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanPreviewTest"`
Expected: FAIL — `FixPlanPreview` unresolved.

- [ ] **Step 3: Implement `FixPlanPreview.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.util.UUID

/**
 * Renders a [FixPlan] as a before/after [CodeFix] for the webview fix-suggestion preview.
 * Computes the plan's edits against [content] (read-only PSI access for PSI-based operations such
 * as ConvertToSafeCast) and applies them to a text copy. Returns null when the plan does not apply
 * (stale offsets, pattern absent). The preview is advisory; applying the fix re-derives and verifies
 * through the Tier-2 gate, so a previewed plan and the eventually-applied plan may differ.
 */
object FixPlanPreview {
    fun render(
        plan: FixPlan,
        project: Project,
        virtualFile: VirtualFile,
        content: String,
        issue: Issue,
    ): CodeFix? {
        val edits = ApplicationManager.getApplication().runReadAction<List<TextEdit>?> {
            val ctx = FixContext(content) { PsiManager.getInstance(project).findFile(virtualFile) }
            plan.toEdits(ctx)
        } ?: return null

        val fixed = StringBuilder(content)
        for (edit in edits.sortedByDescending { it.startOffset }) {
            fixed.replace(edit.startOffset, edit.endOffset, edit.replacement)
        }

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = "AI-proposed fix (verified when applied)",
            originalCode = content,
            fixedCode = fixed.toString(),
            filePath = issue.filePath,
            lineStart = 1,
            lineEnd = content.lines().size,
            isDeterministic = false,
            confidence = 0.7,
        )
    }
}
```

> Implementer note: confirm `TextEdit`'s property names are `startOffset` / `endOffset` / `replacement` (used identically in `FixPlanApplicator`). If `FixContext`'s constructor differs, match its real signature (`FixContext(content) { psiProvider }`).

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanPreviewTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanPreview.kt src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanPreviewTest.kt
git commit -m "feat(fix-engine): FixPlanPreview renders a FixPlan as a before/after CodeFix"
```

---

### Task 2: Migrate the `UIEventRouter` AI fix-suggestion fallback to the planner

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/UIEventRouter.kt:215-233`

- [ ] **Step 1: Replace the AI fallback `scope.launch { … }` block**

Replace the block that currently calls `ai.suggestFix(issue, issue.codeSnippet)` (the `scope.launch { try { … } catch … }` at lines ≈215-233) with:

```kotlin
        scope.launch {
            try {
                val ai = aiService ?: resolveAiService() ?: run {
                    withContext(Dispatchers.Swing) {
                        svc.jcefBridge()?.sendError("AI provider not configured. Go to Settings → Tools → Aegis Debug")
                    }
                    return@launch
                }
                val content = try {
                    java.io.File(issue.filePath).readText()
                } catch (e: Exception) {
                    if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                    null
                }
                val vf = LocalFileSystem.getInstance().findFileByPath(issue.filePath)
                val preview = if (vf != null && content != null) {
                    ai.proposeFixPlan(issue, content)?.let {
                        com.ghostdebugger.fix.engine.FixPlanPreview.render(it, project, vf, content, issue)
                    }
                } else null
                withContext(Dispatchers.Swing) {
                    if (preview != null) {
                        svc.jcefBridge()?.sendFixSuggestion(preview)
                    } else {
                        svc.jcefBridge()?.sendError("Aegis Debug couldn't propose a fix for this issue.")
                    }
                }
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                log.error("Failed to generate fix suggestion", e)
                withContext(Dispatchers.Swing) {
                    svc.jcefBridge()?.sendError("Error generating fix: ${e.message}")
                }
            }
        }
```

This (a) replaces the free-form `suggestFix` with the planner + `FixPlanPreview`, and (b) adds the `ProcessCanceledException` rethrow the original block was missing (project convention). `LocalFileSystem`, `Dispatchers`, `withContext`, `log` are already imported/used in this file.

- [ ] **Step 2: Verify it compiles**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. (After this, `suggestFix` has no remaining production caller.)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/UIEventRouter.kt
git commit -m "feat(fix-engine): AI fix-suggestion preview uses the planner (proposeFixPlan + FixPlanPreview)"
```

---

### Task 3: Remove `suggestFix` / `parseFixResponse` and fix all references (atomic)

This task removes the legacy methods AND every reference in one commit, so the build never breaks mid-way.

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/ai/AIService.kt`, `ai/BaseAIService.kt`, `ai/prompts/PromptTemplates.kt`
- Delete: `src/test/kotlin/com/ghostdebugger/ai/OpenAIServiceTimeoutTest.kt`, `src/test/kotlin/com/ghostdebugger/ai/OllamaServiceParseTest.kt`
- Modify: `src/test/kotlin/com/ghostdebugger/ai/BaseAIServiceTest.kt`, `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineOllamaDelegationTest.kt`, `src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineSupervisedTest.kt`

- [ ] **Step 1: Remove the production methods**

1. `AIService.kt`: delete the `suggestFix(issue, codeSnippet): CodeFix` interface method. Then remove the now-unused `import com.ghostdebugger.model.CodeFix` (let the compiler confirm; `proposeFixPlan` returns `FixPlan`, `explainIssue` returns `String`, so `CodeFix` should be unused after).
2. `BaseAIService.kt`: delete the `override suspend fun suggestFix(...)` method and the `protected fun parseFixResponse(...)` method (its full body). Remove imports left unused as a result (e.g. `CodeFix`, and any regex/helper used only by `parseFixResponse`) — confirm via the compiler.
3. `PromptTemplates.kt`: delete the `fun suggestFix(issue, codeSnippet, impactContext): String` function.

- [ ] **Step 2: Fix every test reference**

1. **Delete** `src/test/kotlin/com/ghostdebugger/ai/OpenAIServiceTimeoutTest.kt` — it contains only the `parseFixResponse` reflection test (no other tests).
2. **Delete** `src/test/kotlin/com/ghostdebugger/ai/OllamaServiceParseTest.kt` — same (only the `parseFixResponse` reflection test).
3. `BaseAIServiceTest.kt`: replace the `` `suggestFix cache hit short-circuits callModel and re-parses cached response` `` test (≈lines 84-93) with an equivalent cache-hit test over the still-existing cached `explainIssue` path:

```kotlin
    @Test
    fun `explainIssue cache hit short-circuits callModel`() = runBlocking {
        val svc = RecordingService(cacheEnabled = true)
        val i = issue()
        val first = svc.explainIssue(i, "snippet")
        val second = svc.explainIssue(i, "snippet")
        assertEquals(1, svc.invocationCount.get(), "second call should hit cache")
        assertEquals(first, second)
    }
```

4. `AnalysisEngineOllamaDelegationTest.kt`: remove the `override suspend fun suggestFix(issue: Issue, codeSnippet: String) = …` override from the fake `AIService` (≈line 41). The fake no longer needs it (the interface method is gone).
5. `FixEngineSupervisedTest.kt`: remove the line `override suspend fun suggestFix(issue: Issue, codeSnippet: String) = throw UnsupportedOperationException()` from `FakeAI` (≈line 26).

> Implementer note: before finishing, grep the whole repo for any remaining `suggestFix` or `parseFixResponse` references: `grep -rn "suggestFix\|parseFixResponse" src/`. There should be ZERO matches after this task. If any other class implements `AIService` directly and overrides `suggestFix`, remove that override too (search `: AIService`).

- [ ] **Step 3: Compile, then run the affected suites**

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.ai.*" --tests "com.ghostdebugger.fix.engine.*" --tests "com.ghostdebugger.analysis.AnalysisEngineOllamaDelegationTest"
```
Expected: all green; no compile errors; no `suggestFix`/`parseFixResponse` references remain.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(fix-engine): retire free-form suggestFix/parseFixResponse path"
```

---

### Task 4: Document the AI-supervised fix engine as complete

**Files:**
- Modify: `docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md`

- [ ] **Step 1: Update §9 phasing**

Mark **Phase 2c-ii-b (DONE):** the AI fix suggestion/preview now renders a planner result (`proposeFixPlan` + `FixPlanPreview.render`) and the free-form `suggestFix` / `parseFixResponse` path (incl. `PromptTemplates.suggestFix`) is removed. Note that **Phase 2c — and the AI-supervised fix engine arc (Phases 1, 2a, 2b, 2c) — is complete**: the AI now acts purely as a planner/supervisor composing deterministic catalog operations that the engine applies and verifies; it never authors raw fix code anywhere in the system. If the doc has an overview/abstract that referred to a free-form AI fallback, update it to reflect that the fallback is now the supervised planner.

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md
git commit -m "docs(spec): AI-supervised fix engine complete (Phase 2c-ii-b)"
```

---

## Final verification

- [ ] **Confirm zero legacy references**

```bash
grep -rn "suggestFix\|parseFixResponse" src/ || echo "OK: no references remain"
```
Expected: `OK: no references remain`.

- [ ] **Run the full suite**

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test
```
Expected: all green.

---

## Self-Review (completed during planning)

- **Spec coverage:** preview migration → Tasks 1-2; retire `suggestFix`/`parseFixResponse` + `PromptTemplates.suggestFix` + all test references → Task 3; arc-complete doc → Task 4.
- **Build-green discipline:** Task 2 removes the last production caller of `suggestFix` before Task 3 removes the method; Task 3 removes the interface method and ALL overrides/tests in one atomic commit so the build never breaks between commits.
- **Type consistency:** `FixPlanPreview.render(plan, project, virtualFile, content, issue): CodeFix?`; `CodeFix(...)` fields match `AnalysisModels.kt`; `proposeFixPlan(issue, content)` is the 2c-ii-a signature; `TextEdit.startOffset/endOffset/replacement` match `FixPlanApplicator` usage.
- **PCE:** the migrated `UIEventRouter` block adds the previously-missing `ProcessCanceledException` rethrow in its `catch`.
- **Test coverage preserved:** the cache-hit behavior previously asserted via `suggestFix` is re-pinned on `explainIssue` (also cached); the deleted test files contained ONLY obsolete `parseFixResponse` reflection tests.
- **Placeholders:** none. Task 2 is an integration edit verified by compile + the final full suite; Tasks 1 and 3 carry complete code, and Task 3 includes a grep gate proving zero remaining references.
