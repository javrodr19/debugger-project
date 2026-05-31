# Fix Engine — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce the deterministic Fix Engine seam — `FixOperation`/`FixPlan`/`FixEngine` plus a plan-based applicator with the PSI-validity gate — and route the *apply* path through it, preserving today's behavior. (Spec: `docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md`.)

**Architecture:** A `FixPlan` is an ordered list of `FixOperation`s; Phase 1 ships one operation, `ReplaceRange`. The existing deterministic fixers still produce a `CodeFix` (via `FixDeriver`); an adapter wraps that `CodeFix` into a single-op `FixPlan`. `FixPlanApplicator` applies a plan's text edits to the file's Document inside a write action, keeping `FixApplicator`'s proven parse-check-and-revert (Tier-1 / PSI-validity gate). `FixEngine` is the single entry point (`planFor` + `apply`). The Tier-2 re-analysis gate and the richer operation catalog arrive in Phase 2 with the AI planner.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform 2024.3.2, kotlinx.serialization, JUnit (kotlin.test for pure-logic tests; `BasePlatformTestCase` for PSI/Document tests). Build/test require `JAVA_HOME` on the bundled JBR.

**Conventions:** Every `catch (Exception)` rethrows `ProcessCanceledException` first. Operations return null rather than emit an invalid edit. Frequent commits. New files live under `src/main/kotlin/com/ghostdebugger/fix/engine/`.

**Build/test prelude (run once per shell before any `./gradlew test`):**
```bash
export JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)"
export PATH="$JAVA_HOME/bin:$PATH"
```

---

### Task 1: `TextEdit` value type + `applyTo`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/TextEdit.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/TextEditTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class TextEditTest {
    @Test fun `applies a single edit`() {
        val out = listOf(TextEdit(0, 5, "hello")).applyTo("XXXXX world")
        assertEquals("hello world", out)
    }

    @Test fun `applies multiple non-overlapping edits regardless of list order`() {
        // edits given low-to-high; must apply high-to-low internally so offsets stay valid
        val edits = listOf(TextEdit(0, 1, "A"), TextEdit(6, 7, "B"))
        assertEquals("Aello Borld", edits.applyTo("hello world"))
    }

    @Test fun `replacement that changes length does not corrupt later offsets`() {
        val edits = listOf(TextEdit(0, 1, "LONG"), TextEdit(6, 7, "B"))
        assertEquals("LONGello Borld", edits.applyTo("hello world"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.TextEditTest"`
Expected: FAIL — `TextEdit` / `applyTo` unresolved (compilation error).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.ghostdebugger.fix.engine

/** A single text replacement: replace [startOffset, endOffset) with [replacement]. */
data class TextEdit(val startOffset: Int, val endOffset: Int, val replacement: String)

/**
 * Applies all edits to [content]. Edits are applied in descending start-offset order so that
 * earlier offsets remain valid as later text is replaced. Assumes edits do not overlap.
 */
fun List<TextEdit>.applyTo(content: String): String {
    val sb = StringBuilder(content)
    for (edit in sortedByDescending { it.startOffset }) {
        sb.replace(edit.startOffset, edit.endOffset, edit.replacement)
    }
    return sb.toString()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.TextEditTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/TextEdit.kt src/test/kotlin/com/ghostdebugger/fix/engine/TextEditTest.kt
git commit -m "feat(fix-engine): TextEdit value type + applyTo"
```

---

### Task 2: `FixOperation` sealed catalog + `ReplaceRange`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixOperationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FixOperationTest {
    @Test fun `ReplaceRange produces the matching edit when offsets are in range`() {
        val op = ReplaceRange(0, 5, "hello")
        assertEquals(TextEdit(0, 5, "hello"), op.toEdit("XXXXX world"))
    }

    @Test fun `ReplaceRange returns null when offsets are out of range`() {
        assertNull(ReplaceRange(0, 50, "x").toEdit("short"))
        assertNull(ReplaceRange(-1, 2, "x").toEdit("short"))
        assertNull(ReplaceRange(3, 2, "x").toEdit("short")) // start > end
    }

    @Test fun `FixOperation round-trips through polymorphic JSON`() {
        // Locks the JSON contract the Phase 2 AI planner will emit.
        val op: FixOperation = ReplaceRange(1, 4, "abc")
        val json = Json.encodeToString(FixOperation.serializer(), op)
        assertEquals(op, Json.decodeFromString(FixOperation.serializer(), json))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixOperationTest"`
Expected: FAIL — `FixOperation` / `ReplaceRange` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.ghostdebugger.fix.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A deterministic, PSI-valid-by-construction semantic transformation. Each operation converts to a
 * [TextEdit] against the file content, or returns null if it does not apply (offset out of range,
 * pattern absent) — never an invalid edit (CLAUDE.md > Fixer principle). Serializable so the Phase 2
 * AI planner can emit a plan as JSON. Phase 1 ships only [ReplaceRange]; the catalog grows in Phase 2.
 */
@Serializable
sealed class FixOperation {
    abstract fun toEdit(content: String): TextEdit?
}

/** Replace the half-open range [startOffset, endOffset) with [text]. */
@Serializable
@SerialName("replaceRange")
data class ReplaceRange(val startOffset: Int, val endOffset: Int, val text: String) : FixOperation() {
    override fun toEdit(content: String): TextEdit? {
        if (startOffset < 0 || endOffset > content.length || startOffset > endOffset) return null
        return TextEdit(startOffset, endOffset, text)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixOperationTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt src/test/kotlin/com/ghostdebugger/fix/engine/FixOperationTest.kt
git commit -m "feat(fix-engine): FixOperation sealed catalog + ReplaceRange"
```

---

### Task 3: `FixPlan` + `toEdits`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlan.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FixPlanTest {
    @Test fun `toEdits returns one edit per applicable operation`() {
        val plan = FixPlan("issue-1", listOf(ReplaceRange(0, 1, "A"), ReplaceRange(6, 7, "B")))
        assertEquals(listOf(TextEdit(0, 1, "A"), TextEdit(6, 7, "B")), plan.toEdits("hello world"))
    }

    @Test fun `toEdits returns null if any operation is inapplicable`() {
        val plan = FixPlan("issue-1", listOf(ReplaceRange(0, 1, "A"), ReplaceRange(99, 100, "B")))
        assertNull(plan.toEdits("hello world"))
    }

    @Test fun `applyTo composes the whole plan onto content`() {
        val plan = FixPlan("issue-1", listOf(ReplaceRange(0, 5, "hello")))
        assertEquals("hello world", plan.toEdits("XXXXX world")!!.applyTo("XXXXX world"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanTest"`
Expected: FAIL — `FixPlan` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.ghostdebugger.fix.engine

import kotlinx.serialization.Serializable

/** An ordered recipe of [FixOperation]s targeting one issue. Serializable for the Phase 2 AI planner. */
@Serializable
data class FixPlan(val issueId: String, val operations: List<FixOperation>) {
    /** Resolves every operation against [content]; returns null if ANY operation does not apply. */
    fun toEdits(content: String): List<TextEdit>? {
        val edits = ArrayList<TextEdit>(operations.size)
        for (op in operations) edits.add(op.toEdit(content) ?: return null)
        return edits
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixPlan.kt src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanTest.kt
git commit -m "feat(fix-engine): FixPlan + toEdits composition"
```

---

### Task 4: `CodeFix` → `FixPlan` adapter (line→offset)

Context: `CodeFix` carries `lineStart`/`lineEnd` (1-based, inclusive) and `fixedCode` (the replacement for those whole lines). `FixApplicator.Default` replaces `[getLineStartOffset(lineStart-1), getLineEndOffset(lineEnd-1)]`. The adapter reproduces that offset math on the file content (which is the `\n`-normalized Document text, so offsets match).

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/CodeFixAdapter.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/CodeFixAdapterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.CodeFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodeFixAdapterTest {
    private fun codeFix(lineStart: Int, lineEnd: Int, fixed: String) = CodeFix(
        id = "f1", issueId = "i1", description = "d",
        originalCode = "", fixedCode = fixed, filePath = "/x.kt",
        lineStart = lineStart, lineEnd = lineEnd, isDeterministic = true, confidence = 1.0
    )

    @Test fun `lineStartOffsets marks each line start`() {
        // "a\nbb\nc" -> line0@0, line1@2, line2@5
        assertEquals(listOf(0, 2, 5), lineStartOffsets("a\nbb\nc").toList())
    }

    @Test fun `wraps a single-line CodeFix into one ReplaceRange covering that line`() {
        val content = "val a = 1\nval b = 2\n"          // line2 = "val b = 2" at offset 10..19
        val plan = codeFix(2, 2, "val b = 3").toFixPlan(content)!!
        assertEquals(FixPlan("i1", listOf(ReplaceRange(10, 19, "val b = 3"))), plan)
        // sanity: applying it yields the intended content
        assertEquals("val a = 1\nval b = 3\n", plan.toEdits(content)!!.applyTo(content))
    }

    @Test fun `returns null when the CodeFix line range is out of bounds`() {
        assertNull(codeFix(5, 6, "x").toFixPlan("only\ntwo\n"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.CodeFixAdapterTest"`
Expected: FAIL — `lineStartOffsets` / `toFixPlan` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.CodeFix

/** Offset of the start of each 0-based line in [content] (assumed `\n`-normalized, matching the Document). */
internal fun lineStartOffsets(content: String): IntArray {
    val starts = ArrayList<Int>()
    starts.add(0)
    content.forEachIndexed { i, c -> if (c == '\n') starts.add(i + 1) }
    return starts.toIntArray()
}

/**
 * Wraps a deterministic [CodeFix] (whole-line replacement) into a single-op [FixPlan]. Mirrors
 * FixApplicator's line math: replace [lineStart..lineEnd] content (excluding the trailing newline of
 * lineEnd) with fixedCode. Returns null if the line range is out of bounds for [content].
 */
fun CodeFix.toFixPlan(content: String): FixPlan? {
    val starts = lineStartOffsets(content)
    val startIdx = lineStart - 1
    val endIdx = lineEnd - 1
    if (startIdx < 0 || endIdx >= starts.size || startIdx > endIdx) return null
    val startOffset = starts[startIdx]
    // End of the lineEnd line = position before its newline, or content length for the last line.
    val endOffset = if (endIdx + 1 < starts.size) starts[endIdx + 1] - 1 else content.length
    return FixPlan(issueId, listOf(ReplaceRange(startOffset, endOffset, fixedCode)))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.CodeFixAdapterTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/CodeFixAdapter.kt src/test/kotlin/com/ghostdebugger/fix/engine/CodeFixAdapterTest.kt
git commit -m "feat(fix-engine): CodeFix -> FixPlan adapter (line->offset)"
```

---

### Task 5: `FixPlanApplicator` (apply edits + PSI-validity gate + revert)

This ports `FixApplicator.Default`'s proven pattern (apply → commit → check `PsiErrorElement` → save or revert) to operate on a `FixPlan`'s `TextEdit`s. It is the Tier-1 gate and the apply seam Phase 2 reuses. Reuses the existing `FixApplyResult` sealed class from `com.ghostdebugger.fix`.

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FixPlanApplicatorTest : BasePlatformTestCase() {

    fun testAppliesAValidPlanAndSaves() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        // replace "return 1" region with "return 2"
        val start = content.indexOf("return 1")
        val plan = FixPlan("i1", listOf(ReplaceRange(start, start + "return 1".length, "return 2")))

        val result = FixPlanApplicator().apply(plan, vf, project)

        assertTrue(result.toString(), result is FixApplyResult.Success)
        val after = runReadAction { myFixture.getDocument(psi).text }
        assertTrue(after.contains("return 2"))
    }

    fun testRejectsAPlanThatProducesInvalidKotlinAndRevertsTheDocument() {
        val original = "fun f(): Int { return 1 }\n"
        val psi = myFixture.configureByText("A.kt", original)
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        val start = content.indexOf("return 1")
        // unbalanced brace -> PSI error
        val plan = FixPlan("i1", listOf(ReplaceRange(start, start + "return 1".length, "return 1 }}}")))

        val result = FixPlanApplicator().apply(plan, vf, project)

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        val after = runReadAction { myFixture.getDocument(psi).text }
        assertEquals(original, after)   // reverted
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanApplicatorTest"`
Expected: FAIL — `FixPlanApplicator` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Applies a [FixPlan] to a file's Document inside a write action, then enforces the Tier-1
 * PSI-validity gate: commit the PSI and, if a [PsiErrorElement] appears, revert and reject. Mirrors
 * FixApplicator.Default's parse-check-and-revert. (For languages without a Community PSI parser,
 * e.g. TS/JS, no error element appears — same behavior as today.)
 */
class FixPlanApplicator {
    private val log = logger<FixPlanApplicator>()

    fun apply(plan: FixPlan, virtualFile: VirtualFile, project: Project): FixApplyResult {
        return try {
            val fdm = FileDocumentManager.getInstance()
            val document: Document = ApplicationManager.getApplication().runReadAction<Document?> {
                fdm.getDocument(virtualFile)
            } ?: return FixApplyResult.Rejected("No document for ${virtualFile.path}")

            val edits = plan.toEdits(document.text)
                ?: return FixApplyResult.Rejected("Plan does not apply to current content (stale offsets).")

            var succeeded = false
            WriteCommandAction.runWriteCommandAction(project, "Apply Aegis Debug Fix", null, Runnable {
                val original = document.text
                for (edit in edits.sortedByDescending { it.startOffset }) {
                    document.replaceString(edit.startOffset, edit.endOffset, edit.replacement)
                }
                val psiDocMgr = PsiDocumentManager.getInstance(project)
                psiDocMgr.commitDocument(document)

                val psiFile = psiDocMgr.getPsiFile(document)
                val firstError = psiFile?.let { PsiTreeUtil.findChildOfType(it, PsiErrorElement::class.java) }
                if (firstError != null) {
                    log.warn("Fix rejected: PSI error after apply for ${plan.issueId}: ${firstError.errorDescription}")
                    document.setText(original)
                    psiDocMgr.commitDocument(document)
                    succeeded = false
                } else {
                    fdm.saveDocument(document)
                    succeeded = true
                }
            })

            if (succeeded) FixApplyResult.Success
            else FixApplyResult.Rejected("The proposed fix would produce invalid code and was not applied.")
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            FixApplyResult.Failed(t)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanApplicatorTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicatorTest.kt
git commit -m "feat(fix-engine): FixPlanApplicator with Tier-1 PSI-validity gate"
```

---

### Task 6: `FixEngine` (single entry point)

`FixEngine` ties it together: `planFor` derives a deterministic `CodeFix` via the existing `FixDeriver` and adapts it to a `FixPlan`; `apply` runs the plan through `FixPlanApplicator`; `fix` does both. No AI in Phase 1.

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FixEngineTest : BasePlatformTestCase() {

    private fun issueAt(path: String) = Issue(
        id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = "t", description = "d", filePath = path, line = 1, ruleId = "AEG-NULL-001"
    )

    fun testFixDerivesAPlanFromTheDeterministicFixerAndAppliesIt() {
        val psi = myFixture.configureByText("A.kt", "val a = 1\nval b = 2\n")
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        // Stub the deriver: a known CodeFix that rewrites line 2.
        val codeFix = CodeFix(
            id = "f1", issueId = "i1", description = "d", originalCode = "val b = 2",
            fixedCode = "val b = 3", filePath = vf.path, lineStart = 2, lineEnd = 2,
            isDeterministic = true, confidence = 1.0
        )
        val engine = FixEngine(project, deriveCodeFix = { _, _, _ -> codeFix })

        val result = engine.fix(issueAt(vf.path), vf, content)

        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(psi).text }.contains("val b = 3"))
    }

    fun testFixReturnsRejectedWhenNoDeterministicFixerApplies() {
        val psi = myFixture.configureByText("A.kt", "val a = 1\n")
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        val engine = FixEngine(project, deriveCodeFix = { _, _, _ -> null })

        val result = engine.fix(issueAt(vf.path), vf, content)

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineTest"`
Expected: FAIL — `FixEngine` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.fix.FixDeriver
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Single entry point for deterministic fixing. Phase 1: derive a [CodeFix] from the registered
 * fixer (via [FixDeriver]), adapt it to a single-op [FixPlan], and apply through [FixPlanApplicator]
 * (Tier-1 PSI-validity gate). The [deriveCodeFix] seam is injectable for tests and is where the
 * Phase 2 AI planner will additionally contribute plans.
 */
class FixEngine(
    private val project: Project,
    private val deriveCodeFix: (Issue, VirtualFile, String) -> CodeFix? =
        { issue, vf, content -> FixDeriver(project).derive(issue, vf, content) },
    private val applicator: FixPlanApplicator = FixPlanApplicator(),
) {
    /** Derives the deterministic plan for [issue], or null if no fixer applies. */
    fun planFor(issue: Issue, virtualFile: VirtualFile, content: String): FixPlan? =
        deriveCodeFix(issue, virtualFile, content)?.toFixPlan(content)

    /** Applies an already-derived [plan]. The apply seam Phase 2 also uses. */
    fun apply(plan: FixPlan, virtualFile: VirtualFile): FixApplyResult =
        applicator.apply(plan, virtualFile, project)

    /** Derive + apply. Returns Rejected when no deterministic fixer produces an applicable plan. */
    fun fix(issue: Issue, virtualFile: VirtualFile, content: String): FixApplyResult {
        val plan = planFor(issue, virtualFile, content)
            ?: return FixApplyResult.Rejected("No deterministic fix available for ${issue.ruleId}.")
        return apply(plan, virtualFile)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineTest.kt
git commit -m "feat(fix-engine): FixEngine single entry point (derive + apply)"
```

---

### Task 7: Route the apply path through `FixEngine`

Replace the direct `FixDeriver` + `FixApplicator` apply calls with `FixEngine`. Three call sites. The *suggestion* path and the AI fallback are unchanged (Phase 2). Behavior is preserved: the same deterministic fixes apply, now through the engine seam.

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/UIEventRouter.kt` (`handleApplyFixRequested`)
- Modify: `src/main/kotlin/com/ghostdebugger/intentions/AegisQuickFixIntentionAction.kt` (`invoke`)
- Modify: `src/main/kotlin/com/ghostdebugger/inspections/AegisLocalInspection.kt` (`AegisLocalQuickFix.applyFix`)

- [ ] **Step 1: Run the existing fix tests to capture the green baseline**

Run: `./gradlew test --tests "*AegisQuickFixIntentionActionTest" --tests "*AegisLocalInspectionTest" --tests "*UIEventRouter*"`
Expected: PASS (these guard the apply behavior we must preserve). Note which exist.

- [ ] **Step 2: Route `UIEventRouter.handleApplyFixRequested` through `FixEngine`**

In `handleApplyFixRequested`, the block that re-derives a `CodeFix` and calls `fixApplicator.apply(fix, project)` becomes a single `FixEngine` call. Replace the body after the `issue` is resolved:

```kotlin
// after: val issue = svc.currentIssues.firstOrNull { it.id == issueId } ?: run { ...; return }
svc.suppressUntil = System.currentTimeMillis() + 3000
scope.launch {
    val content = try {
        java.io.File(issue.filePath).readText()
    } catch (e: Exception) {
        if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
        null
    }
    val vf = LocalFileSystem.getInstance().findFileByPath(issue.filePath)
    val applied = if (vf != null && content != null) {
        FixEngine(project).fix(issue, vf, content)
    } else {
        FixApplyResult.Rejected("Could not read file for fix: ${issue.filePath}")
    }
    if (applied is FixApplyResult.Success) {
        withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendFixApplied(issueId) }
        AnalysisOrchestrator.getInstance(project).reanalyzeFile(issue.filePath)
    } else {
        val msg = (applied as? FixApplyResult.Rejected)?.reason ?: "Fix application failed for issue $issueId."
        withContext(Dispatchers.Swing) { svc.jcefBridge()?.sendError(msg) }
    }
}
```

Add import `import com.ghostdebugger.fix.engine.FixEngine` (keep `FixApplyResult`; remove the now-unused `FixDeriver`/`fixApplicator` references if no longer used elsewhere in the file — verify with the compiler).

- [ ] **Step 3: Route `AegisQuickFixIntentionAction.invoke` through `FixEngine`**

Replace the derive+apply tail of `invoke`:

```kotlin
val issue = findFixableIssue(project, editor, element) ?: return
val content = psiFile.text
val result = com.ghostdebugger.fix.engine.FixEngine(project).fix(issue, virtualFile, content)
if (result is com.ghostdebugger.fix.FixApplyResult.Success) {
    AnalysisOrchestrator.getInstance(project).reanalyzeFile(virtualFile.path)
}
```

Remove the now-unused `FixDeriver`/`FixApplicator` imports if unused.

- [ ] **Step 4: Route `AegisLocalQuickFix.applyFix` through `FixEngine`**

`AegisLocalQuickFix` already holds a derived `CodeFix` (`fix`). Apply it via the engine's adapter + apply seam:

```kotlin
override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val vf = descriptor.psiElement?.containingFile?.virtualFile ?: return
    val content = com.intellij.openapi.application.ApplicationManager.getApplication()
        .runReadAction<String?> { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)?.text }
        ?: return
    val plan = fix.toFixPlan(content) ?: return
    val result = com.ghostdebugger.fix.engine.FixEngine(project).apply(plan, vf)
    if (result is com.ghostdebugger.fix.FixApplyResult.Success) {
        AnalysisOrchestrator.getInstance(project).reanalyzeFile(vf.path)
    }
}
```

Add imports `com.ghostdebugger.fix.engine.FixEngine` and `com.ghostdebugger.fix.engine.toFixPlan`. (This also fixes that the old code ignored the apply result; now it only reanalyzes on success — matching the other paths.)

- [ ] **Step 5: Compile, then run the full suite**

Run:
```bash
./gradlew compileKotlin compileTestKotlin
./gradlew test
```
Expected: BUILD SUCCESSFUL. If `AegisLocalInspectionTest` / intention / router tests assert on the old apply path, update them to the engine path (they should still observe the same end state: file changed + reanalyze). Show the failing assertion and adjust to the new call only if it tests an internal seam, not behavior.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/UIEventRouter.kt \
        src/main/kotlin/com/ghostdebugger/intentions/AegisQuickFixIntentionAction.kt \
        src/main/kotlin/com/ghostdebugger/inspections/AegisLocalInspection.kt
git commit -m "refactor(fix): route the apply path through FixEngine (Phase 1 seam)"
```

---

## Self-Review

- **Spec coverage (Phase 1 scope):** `TextEdit` (T1), `FixOperation`+`ReplaceRange` (T2), `FixPlan` (T3), `CodeFix`→plan adapter for the existing fixers (T4), Tier-1 PSI-validity gate via `FixPlanApplicator` (T5), `FixEngine` single entry point (T6), re-route fix entry points (T7). Deferred-to-Phase-2 items (semantic op catalog, Tier-2 re-analysis gate, `FixPlanner`, AI review, removing `suggestFix`) are explicitly out of Phase 1 per the approved scoping. ✅
- **Behavior preservation:** the same deterministic `CodeFix`es are produced (`FixDeriver` unchanged) and applied with the same PSI-validity-and-revert guarantee (ported in T5). The suggestion path + AI fallback are untouched. ✅
- **Type consistency:** `FixApplyResult` reused from `com.ghostdebugger.fix`; `CodeFix` fields (`lineStart`,`lineEnd`,`fixedCode`,`issueId`,`filePath`) match usage; `FixEngine.fix/planFor/apply` names consistent across T6 and T7; `toFixPlan`/`toEdits`/`applyTo`/`lineStartOffsets` names consistent across T1-T7.
- **Risks:** offset math assumes `content` equals the Document text (`\n`-normalized) — true because callers read `content` from the Document/`File` and the applicator re-derives edits from `document.text` at apply time, so stale offsets are caught (`toEdits` → null → Rejected). T7 may require small test updates where existing tests assert the old internal call path.
