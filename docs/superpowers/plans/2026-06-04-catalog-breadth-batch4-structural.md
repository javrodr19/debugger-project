# Catalog Breadth — Batch 4: Structural Ops Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four content-based, language-agnostic structural operations — `RemoveRange`, `ReplaceExpression`, `InsertStatementBefore`, `InsertStatementAfter` — as catalog primitives for the AI planner (no deterministic fixer; the spec lists none for this batch).

**Architecture:** Each op is pure content manipulation over `ctx.content` via `LineLocator` — no PSI, no language branch (works for KT and JS/TS alike). Each returns null when its target line(s) don't resolve. Because they never touch PSI, their unit tests construct `FixContext(content) { null }` and run as plain fast JUnit4 (no `BasePlatformTestCase`). These ops become AI-usable in Batch 5 (when `FixOperationCatalog` regenerates the `planFix` prompt); here they are added + unit-tested. Spec: `docs/superpowers/specs/2026-06-02-fix-engine-catalog-breadth-design.md`.

**Tech Stack:** Kotlin 2.0.21, kotlinx.serialization, plain JUnit4.

---

## Prerequisites (test prelude)

Tests need a JBR; shell env does NOT persist between Bash commands. Prefix EVERY gradlew call inline:
```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` — sealed `FixOperation`; Batch 1-3 ops show the `LineLocator` pattern.
- `src/main/kotlin/com/ghostdebugger/fix/engine/LineLocator.kt` — `lineRange(content,line): IntRange?` (`start until endExclusive`; `.last + 1` is the `\n` position or `content.length`); `indexOfOn(content,line,token): Int?`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixContext.kt` — `class FixContext(val content: String, psiProvider: () -> PsiFile?)`; pass `{ null }` in tests (ops don't use PSI).
- `src/main/kotlin/com/ghostdebugger/fix/engine/TextEdit.kt` — `TextEdit(startOffset, endOffset, replacement)`.

## File structure

- Add four ops to `fix/engine/FixOperation.kt`.
- Tests: `RemoveRangeTest.kt`, `ReplaceExpressionTest.kt`, `InsertStatementBeforeTest.kt`, `InsertStatementAfterTest.kt` (all in `src/test/kotlin/com/ghostdebugger/fix/engine/`).

---

### Task 1: `RemoveRange` operation

Delete whole lines `startLine`..`endLine` (consuming the trailing newline so no blank line remains).

**Files:** Add to `fix/engine/FixOperation.kt`; Test `RemoveRangeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoveRangeTest {
    private fun ctx(content: String) = FixContext(content) { null }
    private fun apply(content: String, op: FixOperation): String {
        val e = op.toEdit(ctx(content))!!
        return content.substring(0, e.startOffset) + e.replacement + content.substring(e.endOffset)
    }

    @Test fun removesASingleLineIncludingItsNewline() {
        assertEquals("a\nc\n", apply("a\nb\nc\n", RemoveRange(startLine = 2, endLine = 2)))
    }

    @Test fun removesAMultiLineRange() {
        assertEquals("a\n", apply("a\nb\nc\n", RemoveRange(startLine = 2, endLine = 3)))
    }

    @Test fun nullWhenStartAfterEnd() {
        assertNull(RemoveRange(startLine = 3, endLine = 2).toEdit(ctx("a\nb\nc\n")))
    }

    @Test fun nullWhenOutOfRange() {
        assertNull(RemoveRange(startLine = 5, endLine = 5).toEdit(ctx("a\nb\n")))
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** (add to `FixOperation.kt`)

```kotlin
/** Delete whole lines [startLine]..[endLine] inclusive (consuming the trailing newline). Content-based. */
@Serializable
@SerialName("removeRange")
data class RemoveRange(val startLine: Int, val endLine: Int) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val start = LineLocator.lineRange(ctx.content, startLine) ?: return null
        val end = LineLocator.lineRange(ctx.content, endLine) ?: return null
        if (start.first > end.last + 1) return null  // startLine after endLine
        val nlPos = end.last + 1  // the '\n' after endLine, or content.length
        val toExclusive = if (nlPos < ctx.content.length && ctx.content[nlPos] == '\n') nlPos + 1 else nlPos
        return TextEdit(start.first, toExclusive, "")
    }
}
```

- [ ] **Step 4: Run → PASS** (4 tests). **Step 5: Commit** `git commit -m "feat(fix-engine): RemoveRange operation"`

---

### Task 2: `ReplaceExpression` operation

Replace the first occurrence of `find` on `line` with `replacement`.

**Files:** Add to `fix/engine/FixOperation.kt`; Test `ReplaceExpressionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplaceExpressionTest {
    private fun ctx(content: String) = FixContext(content) { null }

    @Test fun replacesFirstOccurrenceOnLine() {
        val content = "fun f() {\n    val x = foo()\n}\n"
        val e = ReplaceExpression(line = 2, find = "foo()", replacement = "bar()").toEdit(ctx(content))!!
        val after = content.substring(0, e.startOffset) + e.replacement + content.substring(e.endOffset)
        assertEquals("fun f() {\n    val x = bar()\n}\n", after)
    }

    @Test fun nullWhenFindAbsentOnLine() {
        assertNull(ReplaceExpression(line = 1, find = "foo()", replacement = "bar()").toEdit(ctx("fun f() {\n    foo()\n}\n")))
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** (add to `FixOperation.kt`)

```kotlin
/** Replace the first occurrence of [find] on [line] with [replacement]. Content-based. */
@Serializable
@SerialName("replaceExpression")
data class ReplaceExpression(val line: Int, val find: String, val replacement: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val at = LineLocator.indexOfOn(ctx.content, line, find) ?: return null
        return TextEdit(at, at + find.length, replacement)
    }
}
```

- [ ] **Step 4: Run → PASS** (2 tests). **Step 5: Commit** `git commit -m "feat(fix-engine): ReplaceExpression operation"`

---

### Task 3: `InsertStatementBefore` operation

Insert `statement` as a new line before `line`, matching `line`'s indentation.

**Files:** Add to `fix/engine/FixOperation.kt`; Test `InsertStatementBeforeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsertStatementBeforeTest {
    private fun ctx(content: String) = FixContext(content) { null }

    @Test fun insertsIndentedLineBeforeTarget() {
        val content = "fun f() {\n    doThing()\n}\n"
        val e = InsertStatementBefore(line = 2, statement = "check()").toEdit(ctx(content))!!
        val after = content.substring(0, e.startOffset) + e.replacement + content.substring(e.endOffset)
        assertEquals("fun f() {\n    check()\n    doThing()\n}\n", after)
    }

    @Test fun nullWhenLineOutOfRange() {
        assertNull(InsertStatementBefore(line = 9, statement = "x()").toEdit(ctx("fun f() {}\n")))
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** (add to `FixOperation.kt`)

```kotlin
/** Insert [statement] as a new line immediately before [line], matching that line's indentation. */
@Serializable
@SerialName("insertStatementBefore")
data class InsertStatementBefore(val line: Int, val statement: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val range = LineLocator.lineRange(ctx.content, line) ?: return null
        val lineText = ctx.content.substring(range.first, (range.last + 1).coerceAtMost(ctx.content.length))
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        return TextEdit(range.first, range.first, "$indent$statement\n")
    }
}
```

- [ ] **Step 4: Run → PASS** (2 tests). **Step 5: Commit** `git commit -m "feat(fix-engine): InsertStatementBefore operation"`

---

### Task 4: `InsertStatementAfter` operation

Insert `statement` as a new line immediately after `line`, matching `line`'s indentation.

**Files:** Add to `fix/engine/FixOperation.kt`; Test `InsertStatementAfterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsertStatementAfterTest {
    private fun ctx(content: String) = FixContext(content) { null }

    @Test fun insertsIndentedLineAfterTarget() {
        val content = "fun f() {\n    doThing()\n}\n"
        val e = InsertStatementAfter(line = 2, statement = "cleanup()").toEdit(ctx(content))!!
        val after = content.substring(0, e.startOffset) + e.replacement + content.substring(e.endOffset)
        assertEquals("fun f() {\n    doThing()\n    cleanup()\n}\n", after)
    }

    @Test fun appendsAfterLastLineWithoutTrailingNewline() {
        val content = "line1"  // no trailing newline
        val e = InsertStatementAfter(line = 1, statement = "line2").toEdit(ctx(content))!!
        val after = content.substring(0, e.startOffset) + e.replacement + content.substring(e.endOffset)
        assertEquals("line1\nline2", after)
    }

    @Test fun nullWhenLineOutOfRange() {
        assertNull(InsertStatementAfter(line = 9, statement = "x()").toEdit(ctx("fun f() {}\n")))
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** (add to `FixOperation.kt`)

```kotlin
/** Insert [statement] as a new line immediately after [line], matching that line's indentation. */
@Serializable
@SerialName("insertStatementAfter")
data class InsertStatementAfter(val line: Int, val statement: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val range = LineLocator.lineRange(ctx.content, line) ?: return null
        val lineText = ctx.content.substring(range.first, (range.last + 1).coerceAtMost(ctx.content.length))
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        val nlPos = range.last + 1
        return if (nlPos < ctx.content.length && ctx.content[nlPos] == '\n') {
            TextEdit(nlPos + 1, nlPos + 1, "$indent$statement\n")  // start of next line
        } else {
            TextEdit(ctx.content.length, ctx.content.length, "\n$indent$statement")  // last line, no newline
        }
    }
}
```

- [ ] **Step 4: Run → PASS** (3 tests). **Step 5: Commit** `git commit -m "feat(fix-engine): InsertStatementAfter operation"`

---

## Final verification

- [ ] `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.*"` → green.
- [ ] `JAVA_HOME=... ./gradlew test 2>&1 | tail -6; echo EXIT=${PIPESTATUS[0]}` → full suite green, `EXIT=0`.

## Self-Review (completed during planning)

- **Spec coverage:** `RemoveRange`, `ReplaceExpression`, `InsertStatementBefore`, `InsertStatementAfter` (spec §3.3 structural) → Tasks 1-4. No fixer this batch (none specced). They become AI-usable in Batch 5.
- **Type consistency:** `RemoveRange(startLine, endLine)`, `ReplaceExpression(line, find, replacement)`, `InsertStatementBefore(line, statement)`, `InsertStatementAfter(line, statement)`; `LineLocator.{lineRange,indexOfOn}`; `TextEdit(start,end,replacement)` — consistent across tasks.
- **No-false-positive / robustness:** every op returns null when its line(s) don't resolve; `RemoveRange` rejects `startLine > endLine`; newline handling is explicit (consume after the block; append-on-last-line edge tested).
- **No PSI:** content-only → `FixContext(content){null}` in tests (plain JUnit4, fast); no `BasePlatformTestCase`, no registry change, no Analysis API.
- **Placeholders:** none — complete code + edge-case tests per op.
