# Catalog Breadth — Batch 1: Targeting + Null-Safety Ops Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lay the catalog-breadth foundation — a `LineLocator` targeting helper and a backward-compatible `Fixer.generatePlan` op-emitting path — then add three null-safety operations (`WrapInSafeCall`, `AddElvisDefault`, `SurroundWithNullCheck`) and a `KotlinNullSafetyFixer` (`AEG-NULL-KT-001`) that composes them.

**Architecture:** New `FixOperation`s are language-dual (`ctx.psiFile is KtFile` → PSI path, else content path) and line-targeted (resolved at apply-time, null if unresolved). Deterministic fixers emit ops via a new `Fixer.generatePlan(issue, ctx): FixPlan?` that `FixDeriver.derivePlan` tries before the existing `CodeFix` path; `FixEngine.planFor` switches its seam from `deriveCodeFix` to `derivePlan`. Verification is unchanged (existing Tier-2 gate). See spec `docs/superpowers/specs/2026-06-02-fix-engine-catalog-breadth-design.md`.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform (IPGP 2.14.0, 2024.3.2), K2 Analysis API (PSI only — no type resolution needed here), kotlinx.serialization, JUnit4 `BasePlatformTestCase` + plain JUnit4 for pure helpers.

---

## Prerequisites (test prelude)

Tests need a JetBrains Runtime; shell env does NOT persist between Bash commands. Prefix EVERY gradlew call inline:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` — `@Serializable sealed class FixOperation { abstract fun toEdit(ctx: FixContext): TextEdit? }`; existing `@SerialName` ops; `internal fun lineStartOffsets(content)` lives in `CodeFixAdapter.kt`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixContext.kt` — `class FixContext(val content: String, psiProvider: () -> PsiFile?) { val psiFile by lazy }`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/TextEdit.kt` — `TextEdit(startOffset, endOffset, replacement)`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlan.kt` — `@Serializable data class FixPlan(issueId, operations)`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt` — `planFor` uses the `deriveCodeFix` seam → `?.toFixPlan(content)`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/CodeFixAdapter.kt` — `fun CodeFix.toFixPlan(content): FixPlan?`.
- `src/main/kotlin/com/ghostdebugger/fix/Fixer.kt` — interface (`ruleId`, `generateFix`, `generateFixFromPsi`).
- `src/main/kotlin/com/ghostdebugger/fix/FixDeriver.kt` — `derive(issue, vf, content): CodeFix?` (PSI-then-text).
- `src/main/kotlin/com/ghostdebugger/fix/KotlinUnsafeCastFixer.kt` — model for a PSI Kotlin fixer (line targeting via `document.getLineNumber`).
- `src/main/kotlin/com/ghostdebugger/analysis/analyzers/KotlinNullSafetyAnalyzer.kt` — `AEG-NULL-KT-001` flags a `KtDotQualifiedExpression` (nullable receiver); issue carries `line` + receiver name in the title.
- `src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineTest.kt`, `FixEngineSupervisedTest.kt` — inject the engine seam (currently `deriveCodeFix`); update in Task 2.

## File structure

- Create `fix/engine/LineLocator.kt`, `FixOperation` additions (in `FixOperation.kt` or new files alongside), `fix/KotlinNullSafetyFixer.kt`.
- Modify `fix/Fixer.kt`, `fix/FixDeriver.kt`, `fix/engine/FixEngine.kt`, and the two engine tests.
- Tests: `LineLocatorTest.kt`, `WrapInSafeCallTest.kt`, `AddElvisDefaultTest.kt`, `SurroundWithNullCheckTest.kt`, `KotlinNullSafetyFixerTest.kt`.

---

### Task 1: `LineLocator` — pure line/offset targeting helper

**Files:** Create `src/main/kotlin/com/ghostdebugger/fix/engine/LineLocator.kt`; Test `src/test/kotlin/com/ghostdebugger/fix/engine/LineLocatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineLocatorTest {
    private val src = "fun f() {\n    val x = user.name\n    return x\n}\n"

    @Test fun lineRangeReturnsCharSpanOfOneBasedLine() {
        // line 2 is "    val x = user.name"
        val r = LineLocator.lineRange(src, 2)!!
        assertEquals("    val x = user.name", src.substring(r.first, r.last + 1))
    }

    @Test fun lineRangeNullWhenOutOfRange() {
        assertNull(LineLocator.lineRange(src, 99))
    }

    @Test fun indexOfOnFindsTokenWithinLine() {
        val at = LineLocator.indexOfOn(src, 2, "user.")!!
        assertEquals("user.", src.substring(at, at + "user.".length))
    }

    @Test fun indexOfOnNullWhenTokenAbsentOnThatLine() {
        assertNull(LineLocator.indexOfOn(src, 3, "user."))
    }

    @Test fun lineAtMapsOffsetToOneBasedLine() {
        val at = src.indexOf("user.")
        assertEquals(2, LineLocator.lineAt(src, at))
    }
}
```

- [ ] **Step 2: Run → FAIL** (`LineLocator` unresolved): `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.LineLocatorTest"`

- [ ] **Step 3: Implement**

```kotlin
package com.ghostdebugger.fix.engine

/**
 * Pure line/offset targeting for [FixOperation]s. Lines are 1-based; offsets are 0-based char
 * indices into the file content. Robust to line shifts because targets are resolved at apply-time.
 */
object LineLocator {
    /** Half-open char range [first, last] (inclusive) of 1-based [line], excluding the trailing newline; null if out of range. */
    fun lineRange(content: String, line: Int): IntRange? {
        if (line < 1) return null
        var start = 0
        var current = 1
        while (current < line) {
            val nl = content.indexOf('\n', start)
            if (nl < 0) return null
            start = nl + 1
            current++
        }
        if (start > content.length) return null
        val nl = content.indexOf('\n', start)
        val endExclusive = if (nl < 0) content.length else nl
        return start until endExclusive  // empty range if blank line
    }

    /** Absolute offset of the first occurrence of [token] within 1-based [line], or null. */
    fun indexOfOn(content: String, line: Int, token: String): Int? {
        val range = lineRange(content, line) ?: return null
        val lineText = content.substring(range.first, (range.last + 1).coerceAtMost(content.length))
        val idx = lineText.indexOf(token)
        return if (idx < 0) null else range.first + idx
    }

    /** 1-based line number containing absolute [offset]. */
    fun lineAt(content: String, offset: Int): Int {
        val clamped = offset.coerceIn(0, content.length)
        return content.substring(0, clamped).count { it == '\n' } + 1
    }
}
```

> Note: `lineRange` returns `start until endExclusive`; for a non-empty line `r.last + 1 == endExclusive`, so `substring(r.first, r.last + 1)` yields the line text. For an empty line the range is empty (`first > last`); ops must handle that (treat as no match).

- [ ] **Step 4: Run → PASS** (5 tests).
- [ ] **Step 5: Commit** `git add ... && git commit -m "feat(fix-engine): LineLocator targeting helper"`

---

### Task 2: `Fixer.generatePlan` + `FixDeriver.derivePlan` + `FixEngine` seam

Backward-compatible: existing fixers (which don't override `generatePlan`) keep flowing through the `CodeFix` path.

**Files:** Modify `fix/Fixer.kt`, `fix/FixDeriver.kt`, `fix/engine/FixEngine.kt`, `src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineTest.kt`, `FixEngineSupervisedTest.kt`

- [ ] **Step 1: Run the existing engine tests for a green baseline**

`JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineTest" --tests "com.ghostdebugger.fix.engine.FixEngineSupervisedTest"` → note current pass counts.

- [ ] **Step 2: Add `generatePlan` to `Fixer`**

In `fix/Fixer.kt`, add imports `import com.ghostdebugger.fix.engine.FixContext` and `import com.ghostdebugger.fix.engine.FixPlan`, and this method (default null — existing fixers unaffected):

```kotlin
    /**
     * Optional op-emitting path: return a [FixPlan] of semantic [com.ghostdebugger.fix.engine.FixOperation]s.
     * [FixDeriver.derivePlan] tries this BEFORE the [generateFixFromPsi]/[generateFix] CodeFix path.
     * Called inside a read action; [ctx] exposes the file content and (lazily) its PSI. Return null to
     * decline (no safe op) and fall back. Default: unsupported.
     */
    fun generatePlan(issue: Issue, ctx: FixContext): FixPlan? = null
```

- [ ] **Step 3: Add `derivePlan` to `FixDeriver`**

Add imports `com.ghostdebugger.fix.engine.FixContext`, `com.ghostdebugger.fix.engine.FixPlan`, `com.ghostdebugger.fix.engine.toFixPlan`, and:

```kotlin
    /**
     * Plan-producing derivation: try the registered fixer's [Fixer.generatePlan] (op path) first;
     * on null fall back to the existing [derive] CodeFix path adapted via [toFixPlan].
     */
    fun derivePlan(issue: Issue, virtualFile: VirtualFile, fileContent: String): FixPlan? {
        val fixer = fixerLookup(issue) ?: return null
        val opPlan = ApplicationManager.getApplication().runReadAction<FixPlan?> {
            try {
                val ctx = FixContext(fileContent) { PsiManager.getInstance(project).findFile(virtualFile) }
                fixer.generatePlan(issue, ctx)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.warn("generatePlan failed for issue ${issue.id}; falling back to CodeFix path", e)
                null
            }
        }
        if (opPlan != null) return opPlan
        return derive(issue, virtualFile, fileContent)?.toFixPlan(fileContent)
    }
```

- [ ] **Step 4: Switch `FixEngine`'s seam from `deriveCodeFix` to `derivePlan`**

In `fix/engine/FixEngine.kt`: replace the constructor `deriveCodeFix` parameter and `planFor`:

```kotlin
class FixEngine(
    private val project: Project,
    private val derivePlan: (Issue, VirtualFile, String) -> FixPlan? =
        { issue, vf, content -> FixDeriver(project).derivePlan(issue, vf, content) },
    private val applicator: FixPlanApplicator = FixPlanApplicator(),
) {
    /** Derives the deterministic plan for [issue], or null if no fixer applies. */
    fun planFor(issue: Issue, virtualFile: VirtualFile, content: String): FixPlan? =
        derivePlan(issue, virtualFile, content)
    // ... apply(), fix(), fixVerified(), fixSupervised() unchanged ...
}
```

Remove the now-unused `import com.ghostdebugger.model.CodeFix` and `import com.ghostdebugger.fix.engine.toFixPlan` from `FixEngine.kt` if present (the `toFixPlan` adaptation now lives in `FixDeriver`). Verify with the compiler.

- [ ] **Step 5: Update the two engine tests to the new seam**

In `FixEngineTest.kt`, the case that injected `deriveCodeFix = { _, _, c -> CodeFix(...) }` becomes `derivePlan = { _, _, c -> CodeFix(...).toFixPlan(c) }` (import `com.ghostdebugger.fix.engine.toFixPlan`). The "no deterministic fixer" case that relied on the default seam is unaffected (default now returns a `FixPlan?`). In `FixEngineSupervisedTest.kt`, change `FixEngine(project, deriveCodeFix = { _, _, _ -> null })` to `FixEngine(project, derivePlan = { _, _, _ -> null })`.

- [ ] **Step 6: Run both engine test classes → PASS** (same counts as the Step-1 baseline). `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineTest" --tests "com.ghostdebugger.fix.engine.FixEngineSupervisedTest"`
- [ ] **Step 7: Commit** `git commit -m "refactor(fix-engine): Fixer.generatePlan op path + FixEngine derivePlan seam"`

---

### Task 3: `WrapInSafeCall` operation

`r.m` → `r?.m`. KT: rewrite the `KtDotQualifiedExpression` on the line whose receiver matches. TS: insert `?` before the receiver's dot on the line.

**Files:** Add to `fix/engine/FixOperation.kt`; Test `src/test/kotlin/com/ghostdebugger/fix/engine/WrapInSafeCallTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class WrapInSafeCallTest : BasePlatformTestCase() {

    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testKotlinRewritesDotAccessToSafeCall() {
        val content = "fun f(user: User?) {\n    val n = user.name\n}\n"
        val edit = runReadAction { WrapInSafeCall(line = 2, receiver = "user").toEdit(ctxFor("A.kt", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("user?.name"))
    }

    fun testTypeScriptInsertsOptionalChaining() {
        val content = "function f(user) {\n  const n = user.name;\n}\n"
        val edit = runReadAction { WrapInSafeCall(line = 2, receiver = "user").toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("user?.name"))
    }

    fun testNullWhenReceiverNotOnLine() {
        val content = "fun f(user: User?) {\n    val n = user.name\n}\n"
        assertNull(runReadAction { WrapInSafeCall(line = 1, receiver = "user").toEdit(ctxFor("A.kt", content)) })
    }
}
```

- [ ] **Step 2: Run → FAIL** (`WrapInSafeCall` unresolved).

- [ ] **Step 3: Implement** (add to `FixOperation.kt`; imports `org.jetbrains.kotlin.psi.KtFile`, `org.jetbrains.kotlin.psi.KtDotQualifiedExpression`, `com.intellij.psi.util.PsiTreeUtil`)

```kotlin
/** `receiver.member` → `receiver?.member` on [line]. KT rewrites the matching dot-qualified expression; TS inserts `?` before the dot. */
@Serializable
@SerialName("wrapInSafeCall")
data class WrapInSafeCall(val line: Int, val receiver: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val ktFile = ctx.psiFile as? KtFile
        if (ktFile != null) {
            val access = PsiTreeUtil.findChildrenOfType(ktFile, KtDotQualifiedExpression::class.java).firstOrNull {
                LineLocator.lineAt(ctx.content, it.textRange.startOffset) == line &&
                    it.receiverExpression.text == receiver &&
                    it.selectorExpression != null
            } ?: return null
            val selector = access.selectorExpression!!.text
            return TextEdit(access.textRange.startOffset, access.textRange.endOffset, "$receiver?.$selector")
        }
        // Content path (TS/JS): insert `?` before the receiver's dot on the line.
        val at = LineLocator.indexOfOn(ctx.content, line, "$receiver.") ?: return null
        val dot = at + receiver.length
        return TextEdit(dot, dot + 1, "?.")
    }
}
```

- [ ] **Step 4: Run → PASS** (3 tests).
- [ ] **Step 5: Commit** `git commit -m "feat(fix-engine): WrapInSafeCall operation (KT PSI + TS content)"`

---

### Task 4: `AddElvisDefault` operation

`expr` → `expr ?: default` (KT) / `expr ?? default` (TS), on [line], first occurrence of `expr`.

**Files:** Add to `fix/engine/FixOperation.kt`; Test `AddElvisDefaultTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AddElvisDefaultTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testKotlinAppendsElvis() {
        val content = "fun f(): String {\n    return name\n}\n"
        val edit = runReadAction { AddElvisDefault(line = 2, expr = "name", default = "\"\"").toEdit(ctxFor("A.kt", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("name ?: \"\""))
    }

    fun testTypeScriptUsesNullishCoalescing() {
        val content = "function f() {\n  return name;\n}\n"
        val edit = runReadAction { AddElvisDefault(line = 2, expr = "name", default = "''").toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("name ?? ''"))
    }

    fun testNullWhenExprAbsent() {
        val content = "fun f(): String {\n    return name\n}\n"
        assertNull(runReadAction { AddElvisDefault(line = 2, expr = "missing", default = "\"\"").toEdit(ctxFor("A.kt", content)) })
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** (add to `FixOperation.kt`; imports `org.jetbrains.kotlin.psi.KtFile`)

```kotlin
/** `expr` → `expr ?: default` (Kotlin) / `expr ?? default` (JS/TS), at the first occurrence of [expr] on [line]. */
@Serializable
@SerialName("addElvisDefault")
data class AddElvisDefault(val line: Int, val expr: String, val default: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val at = LineLocator.indexOfOn(ctx.content, line, expr) ?: return null
        val op = if (ctx.psiFile is KtFile) "?:" else "??"
        val end = at + expr.length
        return TextEdit(at, end, "$expr $op $default")
    }
}
```

- [ ] **Step 4: Run → PASS** (3 tests).
- [ ] **Step 5: Commit** `git commit -m "feat(fix-engine): AddElvisDefault operation"`

---

### Task 5: `SurroundWithNullCheck` operation

Wrap the (single-line) statement on [line] in `if (variable != null) { … }`, preserving indentation. Declines (null) if the line is blank.

**Files:** Add to `fix/engine/FixOperation.kt`; Test `SurroundWithNullCheckTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SurroundWithNullCheckTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testWrapsLineInNullGuardPreservingIndent() {
        val content = "fun f(user: User?) {\n    println(user.name)\n}\n"
        val edit = runReadAction { SurroundWithNullCheck(line = 2, variable = "user").toEdit(ctxFor("A.kt", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("if (user != null) {"))
        assertTrue(after, after.contains("println(user.name)"))
    }

    fun testNullWhenLineBlank() {
        val content = "fun f() {\n\n}\n"
        assertNull(runReadAction { SurroundWithNullCheck(line = 2, variable = "x").toEdit(ctxFor("A.kt", content)) })
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** (add to `FixOperation.kt`)

```kotlin
/** Wrap the single-line statement on [line] in `if ([variable] != null) { … }`, preserving leading indentation. */
@Serializable
@SerialName("surroundWithNullCheck")
data class SurroundWithNullCheck(val line: Int, val variable: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val range = LineLocator.lineRange(ctx.content, line) ?: return null
        if (range.isEmpty()) return null
        val lineText = ctx.content.substring(range.first, range.last + 1)
        if (lineText.isBlank()) return null
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        val body = lineText.trim()
        val wrapped = "${indent}if ($variable != null) {\n$indent    $body\n$indent}"
        return TextEdit(range.first, range.last + 1, wrapped)
    }
}
```

- [ ] **Step 4: Run → PASS** (2 tests).
- [ ] **Step 5: Commit** `git commit -m "feat(fix-engine): SurroundWithNullCheck operation"`

---

### Task 6: `KotlinNullSafetyFixer` (`AEG-NULL-KT-001`) + registration + end-to-end

The fixer's `generatePlan` re-finds the flagged `KtDotQualifiedExpression` on `issue.line` and emits a `WrapInSafeCall(line, receiver)`; declines (null) if it can't pin a single matching access.

**Files:** Create `fix/KotlinNullSafetyFixer.kt`; Modify `fix/FixerRegistry.kt`; Test `KotlinNullSafetyFixerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.WrapInSafeCall
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KotlinNullSafetyFixerTest : BasePlatformTestCase() {
    private fun issue(line: Int, title: String) = Issue(
        id = "i", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = title, description = "", filePath = "A.kt", line = line, ruleId = "AEG-NULL-KT-001"
    )
    private fun ctxFor(content: String): FixContext {
        val psi = myFixture.configureByText("A.kt", content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testEmitsWrapInSafeCallForFlaggedAccess() {
        val content = "fun f(user: User?) {\n    val n = user.name\n}\n"
        val plan = runReadAction {
            KotlinNullSafetyFixer().generatePlan(issue(2, "Nullable 'user' accessed without a null check"), ctxFor(content))
        }!!
        assertEquals(1, plan.operations.size)
        val op = plan.operations[0]
        assertTrue(op.toString(), op is WrapInSafeCall)
        assertEquals("user", (op as WrapInSafeCall).receiver)
        assertEquals(2, op.line)
    }

    fun testDeclinesWhenNoDotAccessOnLine() {
        val content = "fun f(user: User?) {\n    val n = 1\n}\n"
        assertNull(runReadAction {
            KotlinNullSafetyFixer().generatePlan(issue(2, "Nullable 'user' accessed without a null check"), ctxFor(content))
        })
    }
}
```

- [ ] **Step 2: Run → FAIL** (`KotlinNullSafetyFixer` unresolved).

- [ ] **Step 3: Implement the fixer**

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.FixPlan
import com.ghostdebugger.fix.engine.LineLocator
import com.ghostdebugger.fix.engine.WrapInSafeCall
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Deterministic fixer for Kotlin nullable access (`AEG-NULL-KT-001`): rewrites the flagged
 * `receiver.member` to a safe call `receiver?.member` via [WrapInSafeCall]. Declines (null) when it
 * cannot pin exactly one dot-qualified access on the issue line — the AI planner then takes over.
 */
class KotlinNullSafetyFixer : Fixer {
    override val ruleId = "AEG-NULL-KT-001"
    override val description = "Rewrites a flagged nullable `receiver.member` access to a safe call `receiver?.member`."

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? = null

    override fun generatePlan(issue: Issue, ctx: FixContext): FixPlan? {
        val ktFile = ctx.psiFile as? KtFile ?: return null
        val onLine = PsiTreeUtil.findChildrenOfType(ktFile, KtDotQualifiedExpression::class.java).filter {
            LineLocator.lineAt(ctx.content, it.textRange.startOffset) == issue.line && it.selectorExpression != null
        }
        // Pin a single access. Prefer one whose receiver name appears in the issue title; else require exactly one.
        val access = when {
            onLine.size == 1 -> onLine[0]
            else -> onLine.firstOrNull { issue.title.contains("'${it.receiverExpression.text}'") }
        } ?: return null
        return FixPlan(issue.id, listOf(WrapInSafeCall(issue.line, access.receiverExpression.text)))
    }
}
```

- [ ] **Step 4: Register the fixer**

In `fix/FixerRegistry.kt`, add `KotlinNullSafetyFixer()` to the `listOf(...)` of fixers.

- [ ] **Step 5: Run the fixer test → PASS** (2 tests). `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.KotlinNullSafetyFixerTest"`

- [ ] **Step 6: End-to-end supervised test (real analyzer → fix → gate accept)**

Create `src/test/kotlin/com/ghostdebugger/fix/engine/NullSafetyBreadthIntegrationTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.fix.FixApplyResult
import com.intellij.openapi.application.runReadAction
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile

/** Real AEG-NULL-KT-001 detection → KotlinNullSafetyFixer → WrapInSafeCall → Tier-2 gate accept. */
class NullSafetyBreadthIntegrationTest : AegisKotlinAnalysisTestCase() {
    fun testSafeCallFixIsDerivedAndVerified() {
        val code = "fun f(user: String?): Int { return user.length }\n"
        val vf = (myFixture.configureByText("A.kt", code) as KtFile).virtualFile
        val baseline = runBlocking { SingleFileStaticReanalysis(project).issuesFor(vf) }
        val target = baseline.first { it.ruleId == "AEG-NULL-KT-001" }
        val content = runReadAction { myFixture.getDocument(myFixture.file).text }

        val result = runBlocking {
            FixEngine(project).fixVerified(target, vf, content, baseline,
                reanalyze = { SingleFileStaticReanalysis(project).issuesFor(vf) })
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(myFixture.file).text }.contains("user?.length"))
    }
}
```

Run → PASS (1 test). Troubleshooting: if `user?.length` makes the return type nullable and the gate rejects (new TYPE error), the sample is wrong — `user.length` where the function returns `Int` and `user` is `String?`: `user?.length` is `Int?`, which would not match `Int` return → the gate *correctly* rejects. Use a sample where the safe call is type-valid, e.g. `fun f(user: String?) { println(user.length) }` (statement context, no return-type mismatch). Adjust the sample so the fix verifies, keeping the assertion on `user?.length`.

- [ ] **Step 7: Commit** `git commit -m "feat(fix-engine): KotlinNullSafetyFixer (AEG-NULL-KT-001) via WrapInSafeCall + e2e"`

---

## Final verification

- [ ] `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.*" --tests "com.ghostdebugger.fix.engine.*"` → all green.
- [ ] `JAVA_HOME=... ./gradlew test` → full suite green (the `FixEngine` seam change touches all fix call paths; confirm no regressions).

## Self-Review (completed during planning)

- **Spec coverage:** LineLocator + `Fixer.generatePlan` wiring (spec §3.1/§3.5) → Tasks 1-2; `WrapInSafeCall`/`AddElvisDefault`/`SurroundWithNullCheck` (§3.3) → Tasks 3-5; `KotlinNullSafetyFixer` (§3.5) → Task 6. `FixOperationCatalog`/prompt regen and the other domains are later batches (not here).
- **Backward compatibility:** `generatePlan` defaults to null so the 5 existing fixers are untouched; `FixDeriver.derivePlan` falls back to the `CodeFix` path; only the `FixEngine` seam name/type changes (2 tests updated).
- **Type consistency:** `FixContext(content) { psiProvider }`, `TextEdit(startOffset, endOffset, replacement)`, `FixPlan(issueId, operations)`, `WrapInSafeCall(line, receiver)`, `AddElvisDefault(line, expr, default)`, `SurroundWithNullCheck(line, variable)`, `LineLocator.{lineRange,indexOfOn,lineAt}` — used identically across tasks.
- **No-false-positive:** every op returns null on unresolved target / unsupported language; `KotlinNullSafetyFixer` declines when it can't pin a single access. The Tier-2 gate rejects any semantic-changing fix that introduces a new issue (Task 6 troubleshooting note).
- **Placeholders:** none — complete code per step; Task 6 names the one sample-correctness pitfall and its fix.
