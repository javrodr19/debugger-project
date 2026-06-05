# AI Extract-Method C1 — Building Blocks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the deterministic building blocks the AI extract-method gate (C2) will consume: per-function complexity measurement, two general line-based ops the AI composes to author an extraction, and their catalog/codec exposure — with no behavior change to any existing rule.

**Architecture:** `PerFunctionComplexity.measure` parses Kotlin content into an in-memory `KtFile` and maps each `KtNamedFunction` to its own complexity via `GraphBuilder().estimateComplexity(body, 1)` (the file-level metric at function granularity — single-sourced), keyed by `name/arity`. `ReplaceLines` and `InsertLinesAfter` are verbatim, line-based `FixOperation`s (offsets are unreliable for an LLM; lines aren't). Both are added to `FixOperationCatalog` so the planner can compose them; `FixPlanCodec` decodes them via the existing closed-polymorphic sealed hierarchy (no registration).

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform PSI (`PsiFileFactory`, `KtNamedFunction`, `org.jetbrains.kotlin.idea.KotlinLanguage`), kotlinx.serialization (sealed `FixOperation`, discriminator `type`), JUnit4 + `BasePlatformTestCase`.

**Spec:** `docs/superpowers/specs/2026-06-05-ai-extract-method-design.md` (§3.1 PerFunctionComplexity, §3.2 the two ops, §6 phase C1).

---

## Prerequisites (test prelude)

Tests need a JetBrains Runtime; the shell env does **not** persist between Bash calls. Prefix **every** gradlew invocation inline:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

A piped gradle run masks the build exit code. When you need the real status, append `; echo EXIT=${PIPESTATUS[0]}` and treat any `BUILD FAILED` or non-zero `EXIT` as failure regardless of tail output. Test runs take a few minutes; that is normal.

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` — the sealed `FixOperation` (15 ops after B2). Each is `@Serializable @SerialName(...)` with `toEdit(ctx: FixContext): TextEdit?` (null = does not apply, never an invalid edit). The new ops append at the END (after `CollapseBooleanReturn`, the current last op). The file already imports `kotlinx.serialization.SerialName`, `kotlinx.serialization.Serializable`, and references `TextEdit`, `FixContext`, `LineLocator` (same package).
- `src/main/kotlin/com/ghostdebugger/fix/engine/LineLocator.kt` — `lineRange(content, line): IntRange?` returns `start until endExclusive` (the line's chars, **excluding** the trailing `\n`); so `range.first` = first char offset of the line and `range.last + 1` = the offset of the line's terminating `\n` (or `content.length` for the last line). 1-based lines; null if out of range.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixContext.kt` — `FixContext(val content: String, psiProvider: () -> PsiFile?)`. Content-only ops construct it as `FixContext(content) { null }` in tests.
- `src/main/kotlin/com/ghostdebugger/graph/GraphBuilder.kt:121` — `internal fun estimateComplexity(content, functionCount): Int = 1 + decisionPoints/functionCount` is a **member** of `GraphBuilder` (call `GraphBuilder().estimateComplexity(body, 1)`); B2 uses exactly this form. `estimateComplexity(body, 1) = 1 + decisionPointsInBody`.
- `src/main/kotlin/com/ghostdebugger/parser/JavaPsiSymbolExtractor.kt:42-57` — the canonical "parse content → in-memory PSI in a read action" idiom: `PsiFileFactory.getInstance(project).createFileFromText(name, JavaLanguage.INSTANCE, content) as? PsiJavaFile`, wrapped in `ApplicationManager.getApplication().runReadAction { ... }`. C1 mirrors this with `KotlinLanguage.INSTANCE` / `as? KtFile`.
- `src/main/kotlin/com/ghostdebugger/parser/KotlinPsiSymbolExtractor.kt:125,133` — `PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction::class.java)` and `.valueParameters` (PSI accessor). `KtNamedFunction` exposes `.name: String?`, `.valueParameters: List<KtParameter>` (PSI, no Analysis API), and `.bodyExpression: KtExpression?` (the block `{…}` for block bodies, or the RHS for expression bodies).
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalog.kt` — `entries: List<String>` (15 JSON-schema lines after B2) + `serialNames()`; `PromptTemplates.planFix` renders these.
- `src/test/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalogTest.kt` — `entriesAreNonEmptyAndTypePrefixed` asserts `entries.size == 15`; `everySealedOperationHasExactlyOneCatalogEntry` derives the registered set dynamically.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanCodec.kt` — closed-polymorphic decode (discriminator `type`); a new sealed subclass is decoded with no registration.
- B2 reference tests to mirror style: `src/test/kotlin/com/ghostdebugger/fix/engine/CollapseBooleanReturnTest.kt` (op test via `toEdit` + substring), `FixPlanCodecCollapseTest.kt` (codec round-trip), `FunctionCounterTest.kt` (plain JUnit, content helper).

## File structure

- **Create** `src/main/kotlin/com/ghostdebugger/fix/engine/PerFunctionComplexity.kt` — per-function complexity map (one responsibility: measure).
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` — append `ReplaceLines` + `InsertLinesAfter`.
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalog.kt` — add the two entries.
- **Modify** `src/test/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalogTest.kt` — count 15 → 17.
- Tests created alongside each (paths per task).

---

### Task 1: `PerFunctionComplexity`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/PerFunctionComplexity.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/PerFunctionComplexityTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/fix/engine/PerFunctionComplexityTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PerFunctionComplexityTest : BasePlatformTestCase() {
    fun testMeasuresEachFunctionsOwnComplexity() {
        // f body has two `if` + one `&&` = 3 decision points -> 1+3 = 4 ; g has none -> 1
        val content = "fun f(a: Boolean, b: Boolean) {\n" +
            "    if (a) {}\n" +
            "    if (b && a) {}\n" +
            "}\n" +
            "fun g() { println(1) }\n"
        val r = PerFunctionComplexity.measure(project, content)
        assertFalse(r.collision)
        assertEquals(4, r.byKey["f/2"])
        assertEquals(1, r.byKey["g/0"])
    }

    fun testExpressionBodyMeasured() {
        val content = "fun h(a: Boolean): Int = if (a) 1 else 2\n"
        val r = PerFunctionComplexity.measure(project, content)
        // expression body `if (a) 1 else 2` -> one `if` (else not counted) -> 1+1 = 2
        assertEquals(2, r.byKey["h/1"])
    }

    fun testDuplicateNameAndArityFlagsCollision() {
        val content = "fun f(a: Int) {}\nfun f(b: Int) {}\n"
        val r = PerFunctionComplexity.measure(project, content)
        assertTrue(r.collision)
    }

    fun testFunctionWithoutBodyIsSkipped() {
        // abstract fun has no body -> not in the map; the concrete one is
        val content = "abstract class C {\n    abstract fun a()\n    fun b() { if (true) {} }\n}\n"
        val r = PerFunctionComplexity.measure(project, content)
        assertNull(r.byKey["a/0"])
        assertEquals(2, r.byKey["b/0"])
    }
}
```

- [ ] **Step 2: Run → FAIL** (`PerFunctionComplexity` unresolved).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.PerFunctionComplexityTest"`

- [ ] **Step 3: Implement `PerFunctionComplexity.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.graph.GraphBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Per-function complexity for Kotlin content: each [KtNamedFunction]'s own complexity, computed as
 * `GraphBuilder.estimateComplexity(body, 1) = 1 + decisionPointsInBody` (the file-level metric at
 * function granularity — single-sourced in [GraphBuilder]). Keyed by `"name/arity"` so a function
 * matches itself across an edit (extraction does not change the source's signature).
 *
 * Parses [content] into an in-memory [KtFile] inside a read action — structural PSI only, no Analysis
 * API, so it is safe off-EDT and never resolves types. Consumed by `ExtractMethodVerifier` (C2) to
 * compare original vs candidate.
 */
object PerFunctionComplexity {
    /** Per-function complexities by `name/arity`; [collision] is true if two functions shared a key. */
    data class Result(val byKey: Map<String, Int>, val collision: Boolean)

    fun measure(project: Project, content: String): Result {
        return ApplicationManager.getApplication().runReadAction<Result> {
            try {
                val ktFile = PsiFileFactory.getInstance(project)
                    .createFileFromText("temp.kt", KotlinLanguage.INSTANCE, content) as? KtFile
                    ?: return@runReadAction Result(emptyMap(), collision = false)
                val graphBuilder = GraphBuilder()
                val map = HashMap<String, Int>()
                var collision = false
                for (fn in PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction::class.java)) {
                    val body = fn.bodyExpression ?: continue
                    val key = "${fn.name ?: "?"}/${fn.valueParameters.size}"
                    if (map.containsKey(key)) collision = true
                    map[key] = graphBuilder.estimateComplexity(body.text, 1)
                }
                Result(map, collision)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                Result(emptyMap(), collision = false)
            }
        }
    }
}
```

- [ ] **Step 4: Run → PASS** (4 tests).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.PerFunctionComplexityTest"`

Expected: all green. If `testMeasuresEachFunctionsOwnComplexity` reports a different number, log `r.byKey` — confirm `estimateComplexity(body.text, 1)` counts `if`/`&&` over the body text (it masks strings/comments first); the body text includes the enclosing `{ … }` which adds no decision keywords.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/PerFunctionComplexity.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/PerFunctionComplexityTest.kt
git commit -m "feat(fix-engine): PerFunctionComplexity (per-function metric for extract-method gate)"
```

---

### Task 2: `ReplaceLines` + `InsertLinesAfter` ops

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` (append both ops after `CollapseBooleanReturn`)
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/ExtractOpsTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/fix/engine/ExtractOpsTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractOpsTest {
    private fun apply(content: String, op: FixOperation): String? {
        val edit = op.toEdit(FixContext(content) { null }) ?: return null
        return content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
    }

    @Test fun replaceLinesReplacesRangeVerbatim() {
        val c = "a\nb\nc\nd\n"
        assertEquals("a\nX\nd\n", apply(c, ReplaceLines(2, 3, "X")))
    }

    @Test fun replaceLinesSingleLine() {
        val c = "a\nb\nc\n"
        assertEquals("a\n  val r = g()\nc\n", apply(c, ReplaceLines(2, 2, "  val r = g()")))
    }

    @Test fun replaceLinesOutOfRangeReturnsNull() {
        assertNull(ReplaceLines(2, 9, "X").toEdit(FixContext("a\nb\n") { null }))
    }

    @Test fun replaceLinesStartAfterEndReturnsNull() {
        assertNull(ReplaceLines(3, 2, "X").toEdit(FixContext("a\nb\nc\nd\n") { null }))
    }

    @Test fun insertLinesAfterAddsBlankSeparatedBlock() {
        val c = "a\nb\n"
        assertEquals("a\n\nNEW\nb\n", apply(c, InsertLinesAfter(1, "NEW")))
    }

    @Test fun insertLinesAfterMultiLineText() {
        val c = "a\nb\n"
        // insert point is the offset of line 2's terminating '\n', so that original '\n' is preserved
        // AFTER the inserted text — the extracted function ends up newline-terminated.
        assertEquals("a\nb\n\nfun g() {\n    h()\n}\n", apply(c, InsertLinesAfter(2, "fun g() {\n    h()\n}")))
    }

    @Test fun insertLinesAfterOutOfRangeReturnsNull() {
        assertNull(InsertLinesAfter(9, "NEW").toEdit(FixContext("a\n") { null }))
    }
}
```

- [ ] **Step 2: Run → FAIL** (`ReplaceLines` / `InsertLinesAfter` unresolved).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.ExtractOpsTest"`

- [ ] **Step 3: Append both ops to `FixOperation.kt`** (at the end of the file, after the `CollapseBooleanReturn` data class)

```kotlin
/**
 * Replace whole lines [startLine]..[endLine] (inclusive, 1-based) with [text] verbatim — [text] is
 * authored with its own indentation; the line's trailing newline is preserved. Content-based.
 * Returns null if either line is out of range or [startLine] is after [endLine].
 */
@Serializable
@SerialName("replaceLines")
data class ReplaceLines(val startLine: Int, val endLine: Int, val text: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val start = LineLocator.lineRange(ctx.content, startLine) ?: return null
        val end = LineLocator.lineRange(ctx.content, endLine) ?: return null
        if (start.first > end.last + 1) return null  // startLine after endLine
        return TextEdit(start.first, end.last + 1, text)
    }
}

/**
 * Insert [text] verbatim as a blank-line-separated block immediately after [afterLine] (1-based) —
 * e.g. a newly extracted function placed after its source function's closing brace. Content-based.
 * Returns null if [afterLine] is out of range.
 */
@Serializable
@SerialName("insertLinesAfter")
data class InsertLinesAfter(val afterLine: Int, val text: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val range = LineLocator.lineRange(ctx.content, afterLine) ?: return null
        val insertAt = range.last + 1  // offset of the line's terminating '\n' (or content end)
        return TextEdit(insertAt, insertAt, "\n\n$text")
    }
}
```

- [ ] **Step 4: Run → PASS** (7 tests).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.ExtractOpsTest"`

Expected: all green. (Note: the sealed-coverage test `FixOperationCatalogTest.everySealedOperationHasExactlyOneCatalogEntry` is now temporarily red — the two new subclasses have no catalog entry yet — Task 3 fixes it. Do NOT run the full suite here.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/ExtractOpsTest.kt
git commit -m "feat(fix-engine): ReplaceLines + InsertLinesAfter line-based ops"
```

---

### Task 3: Catalog exposure (15 → 17) + codec round-trip

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalog.kt`
- Modify: `src/test/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalogTest.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanCodecExtractOpsTest.kt`

- [ ] **Step 1: Update the coverage count + add the codec round-trip test**

In `FixOperationCatalogTest.kt`, change the size assertion from `15` to `17` (the line currently reads `assertEquals(15, FixOperationCatalog.entries.size)`):
```kotlin
        assertEquals(17, FixOperationCatalog.entries.size)
```

Create `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanCodecExtractOpsTest.kt`:
```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixPlanCodecExtractOpsTest {
    @Test fun decodesReplaceLinesAndInsertLinesAfter() {
        val raw = """{"issueId":"i1","operations":[""" +
            """{"type":"replaceLines","startLine":3,"endLine":5,"text":"  val r = g(a)"},""" +
            """{"type":"insertLinesAfter","afterLine":9,"text":"fun g(a: Int) = a"}""" +
            """]}"""
        val plan = FixPlanCodec.decode(raw)!!
        assertEquals("i1", plan.issueId)
        assertEquals(2, plan.operations.size)
        val op0 = plan.operations[0]
        val op1 = plan.operations[1]
        assertTrue(op0.toString(), op0 is ReplaceLines)
        assertTrue(op1.toString(), op1 is InsertLinesAfter)
        assertEquals(3, (op0 as ReplaceLines).startLine)
        assertEquals(5, op0.endLine)
        assertEquals(9, (op1 as InsertLinesAfter).afterLine)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`entriesAreNonEmptyAndTypePrefixed` fails 15≠17; `everySealedOperationHasExactlyOneCatalogEntry` fails — `replaceLines`/`insertLinesAfter` subclasses have no catalog entry; the codec test passes already since decoding is auto-polymorphic).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixOperationCatalogTest" --tests "com.ghostdebugger.fix.engine.FixPlanCodecExtractOpsTest"`

- [ ] **Step 3: Add the two catalog entries**

In `FixOperationCatalog.kt`, append these as the last two elements of the `entries` list (after the `collapseBooleanReturn` line, preserving the trailing-comma style):
```kotlin
        """{"type":"replaceLines","startLine":<int>,"endLine":<int>,"text":"<verbatim lines>"} // replace whole lines startLine..endLine (1-based) with text; e.g. swap an extracted block for its call""",
        """{"type":"insertLinesAfter","afterLine":<int>,"text":"<verbatim lines>"} // insert text as a blank-line-separated block after afterLine; e.g. define a newly extracted function""",
```

- [ ] **Step 4: Run → PASS**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixOperationCatalogTest" --tests "com.ghostdebugger.fix.engine.FixPlanCodecExtractOpsTest" --tests "com.ghostdebugger.ai.PromptTemplatesPlanFixTest"`

Expected: all green. `PromptTemplatesPlanFixTest.everyCatalogOpAppearsInThePrompt` iterates `serialNames()`, so it now also confirms `replaceLines`/`insertLinesAfter` render into the planner prompt — no edit needed there.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalog.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalogTest.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanCodecExtractOpsTest.kt
git commit -m "feat(fix-engine): expose replaceLines + insertLinesAfter in the AI op catalog"
```

---

## Final verification

- [ ] Targeted suites green:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test \
  --tests "com.ghostdebugger.fix.engine.*" --tests "com.ghostdebugger.ai.PromptTemplatesPlanFixTest"
```

- [ ] Full suite green with real exit status:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test 2>&1 | tail -8; echo EXIT=${PIPESTATUS[0]}
```

Expected: `BUILD SUCCESSFUL`, `EXIT=0`. (If the pipe hides a failure, inspect `build/test-results/test/*.xml` for `<testcase>` entries with `<failure>`.)

## Self-Review (completed during planning)

**1. Spec coverage**
- §3.1 `PerFunctionComplexity` (in-memory KtFile parse, no Analysis API; `estimateComplexity(body,1)`; `name/arity` key; duplicate-key collision flag) → Task 1.
- §3.2 `ReplaceLines` + `InsertLinesAfter` (verbatim, line-based, null on out-of-range) + catalog exposure (15 → 17) + codec → Tasks 2-3.
- §6 phase C1 (building blocks, independently testable, no behavior change) → all three tasks; no existing rule is touched (the new ops are inert until C2's gate/prompt consume them; `PerFunctionComplexity` has no caller yet — that is intentional, it is the C2 dependency, and it is fully tested standalone).
- Not in C1 (deferred to C2, correctly): `ExtractMethodVerifier`, `FixEngine` dispatch, the `planFix` extract-method prompt section, the e2e. C1 is blocks only.

**2. Placeholder scan** — none. Every code step shows complete file content or an exact insertion; every run step has the exact `--tests` command + expected outcome. `KotlinLanguage.INSTANCE` is confirmed present (`instrumented-kotlin-compiler-embeddable-2.0.21.jar`).

**3. Type consistency**
- `PerFunctionComplexity.measure(project, content): Result` with `Result(byKey: Map<String,Int>, collision: Boolean)` — keys `"name/arity"` (e.g. `"f/2"`), values from `GraphBuilder().estimateComplexity(body.text, 1)`. C2's `ExtractMethodVerifier` will consume exactly this shape.
- `ReplaceLines(startLine, endLine, text)` `@SerialName("replaceLines")` and `InsertLinesAfter(afterLine, text)` `@SerialName("insertLinesAfter")` — identical discriminators in the ops (Task 2), the catalog entries + codec test (Task 3).
- `LineLocator.lineRange` semantics used consistently: `range.first` (line start), `range.last + 1` (terminating `\n` offset / content end) — the ReplaceLines end bound and the InsertLinesAfter insert point both rely on this, matching the existing `RemoveRange`/`insertStatementAfter` usage.
- Catalog count 15 → 17 (Task 3) matches the `FixOperationCatalogTest` bump; the two `@SerialName`s exactly match the two new entries' `type` values, satisfying `everySealedOperationHasExactlyOneCatalogEntry`.

**Verification of the two op edits (traced):**
- `ReplaceLines(2,3,"X")` on `"a\nb\nc\nd\n"`: lineRange(2)=`[2,3)`→first 2; lineRange(3)=`[4,5)`→last 4; edit `[2,5)`→"X" ⇒ `"a\nX\nd\n"`. ✓
- `InsertLinesAfter(1,"NEW")` on `"a\nb\n"`: lineRange(1)=`[0,1)`→last 0; insertAt 1; insert `"\n\nNEW"` ⇒ `"a\n\nNEW\nb\n"`. ✓
- `InsertLinesAfter(2,"fun g()…")` on `"a\nb\n"`: line 2 is `b`, terminating `\n` at index 3 → lineRange(2)=`[2,3)`→last 2; insertAt 3; insert `"\n\n…}"` before index 3, original `\n` preserved after ⇒ `"a\nb\n\nfun g() {\n    h()\n}\n"`. ✓
