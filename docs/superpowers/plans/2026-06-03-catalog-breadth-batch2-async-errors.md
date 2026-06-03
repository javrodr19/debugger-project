# Catalog Breadth — Batch 2: Async + Error Ops Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three operations — `AddAwait`, `AddPromiseCatch`, `SurroundWithTryCatch` — and extend `AsyncFlowFixer` to emit them via `generatePlan`, covering the `UNHANDLED_PROMISE` and `MISSING_ERROR_HANDLING` variants of `AEG-ASYNC-001`.

**Architecture:** Continues Batch 1's line-targeted, language-dual op pattern (`ctx.psiFile is KtFile` → KT, else content). `AddAwait`/`AddPromiseCatch` are TS/JS-only (return null on Kotlin). `SurroundWithTryCatch` supports both. `AsyncFlowFixer` gains a `generatePlan` that dispatches by `issue.type`; its existing text `generateFix` stays as the fallback. Verification unchanged (Tier-2 gate; JS/TS via content re-analysis). Spec: `docs/superpowers/specs/2026-06-02-fix-engine-catalog-breadth-design.md`.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform (IPGP 2.14.0, 2024.3.2), kotlinx.serialization, JUnit4 `BasePlatformTestCase`.

---

## Prerequisites (test prelude)

Tests need a JBR; shell env does NOT persist between Bash commands. Prefix EVERY gradlew call inline:
```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` — sealed `FixOperation`; Batch-1 ops (`WrapInSafeCall`, `AddElvisDefault`, `SurroundWithNullCheck`) show the line-targeted/language-dual + `LineLocator` pattern.
- `src/main/kotlin/com/ghostdebugger/fix/engine/LineLocator.kt` — `lineRange(content,line): IntRange?`, `indexOfOn(content,line,token): Int?`, `lineAt`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixContext.kt`, `TextEdit.kt`, `FixPlan.kt`.
- `src/main/kotlin/com/ghostdebugger/fix/AsyncFlowFixer.kt` — current fixer: `canFix` = `ruleId == AEG-ASYNC-001 && type == UNHANDLED_PROMISE`; `generateFix` appends `.catch(console.error)` to a `.then(...);` line.
- `src/main/kotlin/com/ghostdebugger/analysis/analyzers/AsyncFlowAnalyzer.kt` — emits `UNHANDLED_PROMISE` (`.then` w/o `.catch`, title "Unhandled promise rejection"), `MISSING_ERROR_HANDLING` (fetch/`response.json()` w/o guard, title "Missing error handling in fetch/async call"), `MEMORY_LEAK` (timer w/o cleanup). All `ruleId = AEG-ASYNC-001`.
- `src/main/kotlin/com/ghostdebugger/fix/Fixer.kt` — `generatePlan(issue, ctx): FixPlan? = null` (Batch 1).
- Check for an existing `AsyncFlowFixerTest` and keep it green (Task 4).

## File structure

- Add the three ops to `fix/engine/FixOperation.kt`.
- Modify `fix/AsyncFlowFixer.kt` (broaden `canFix`, add `generatePlan`).
- Tests: `AddAwaitTest.kt`, `AddPromiseCatchTest.kt`, `SurroundWithTryCatchTest.kt`, extend/keep `AsyncFlowFixerTest`, add `AsyncFlowBreadthIntegrationTest.kt`.

---

### Task 1: `AddAwait` operation (TS/JS)

**Files:** Add to `fix/engine/FixOperation.kt`; Test `src/test/kotlin/com/ghostdebugger/fix/engine/AddAwaitTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AddAwaitTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testPrefixesCallWithAwait() {
        val content = "async function f() {\n  const r = fetch(url);\n}\n"
        val edit = runReadAction { AddAwait(line = 2, call = "fetch(").toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("await fetch("))
    }

    fun testDeclinesWhenAlreadyAwaited() {
        val content = "async function f() {\n  const r = await fetch(url);\n}\n"
        assertNull(runReadAction { AddAwait(line = 2, call = "fetch(").toEdit(ctxFor("a.ts", content)) })
    }

    fun testNullOnKotlin() {
        val content = "fun f() {\n    val r = fetch()\n}\n"
        assertNull(runReadAction { AddAwait(line = 2, call = "fetch(").toEdit(ctxFor("A.kt", content)) })
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** (add to `FixOperation.kt`; `KtFile` already imported)

```kotlin
/** Prefix the first occurrence of [call] on [line] with `await ` (JS/TS only). Declines on Kotlin or if already awaited. */
@Serializable
@SerialName("addAwait")
data class AddAwait(val line: Int, val call: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        if (ctx.psiFile is KtFile) return null
        val at = LineLocator.indexOfOn(ctx.content, line, call) ?: return null
        val before = ctx.content.substring((at - 6).coerceAtLeast(0), at)
        if (before.endsWith("await ")) return null
        return TextEdit(at, at, "await ")
    }
}
```

- [ ] **Step 4: Run → PASS** (3 tests). **Step 5: Commit** `git commit -m "feat(fix-engine): AddAwait operation"`

---

### Task 2: `AddPromiseCatch` operation (TS/JS)

**Files:** Add to `fix/engine/FixOperation.kt`; Test `AddPromiseCatchTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AddPromiseCatchTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testAppendsCatchBeforeTrailingSemicolon() {
        val content = "function f() {\n  doThing().then(handle);\n}\n"
        val edit = runReadAction { AddPromiseCatch(line = 2).toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains(".then(handle).catch(console.error);"))
    }

    fun testNullWhenLineDoesNotEndWithCallSemicolon() {
        val content = "function f() {\n  const x = 1\n}\n"
        assertNull(runReadAction { AddPromiseCatch(line = 2).toEdit(ctxFor("a.ts", content)) })
    }

    fun testNullOnKotlin() {
        val content = "fun f() {\n    doThing().then(handle);\n}\n"
        assertNull(runReadAction { AddPromiseCatch(line = 2).toEdit(ctxFor("A.kt", content)) })
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** (add to `FixOperation.kt`)

```kotlin
/** Insert `.catch([handler])` before the trailing `;` of a `...);` chain on [line] (JS/TS only). */
@Serializable
@SerialName("addPromiseCatch")
data class AddPromiseCatch(val line: Int, val handler: String = "console.error") : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        if (ctx.psiFile is KtFile) return null
        val range = LineLocator.lineRange(ctx.content, line) ?: return null
        if (range.isEmpty()) return null
        val lineText = ctx.content.substring(range.first, range.last + 1)
        val trimmed = lineText.trimEnd()
        if (!trimmed.endsWith(");")) return null
        if (trimmed.contains(".catch(")) return null
        val semicolonAbs = range.first + (trimmed.length - 1)  // the ';'
        return TextEdit(semicolonAbs, semicolonAbs, ".catch($handler)")
    }
}
```

- [ ] **Step 4: Run → PASS** (3 tests). **Step 5: Commit** `git commit -m "feat(fix-engine): AddPromiseCatch operation"`

---

### Task 3: `SurroundWithTryCatch` operation (KT + TS)

**Files:** Add to `fix/engine/FixOperation.kt`; Test `SurroundWithTryCatchTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SurroundWithTryCatchTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testWrapsTypeScriptLineWithTryCatch() {
        val content = "function f() {\n  return res.json();\n}\n"
        val edit = runReadAction { SurroundWithTryCatch(startLine = 2, endLine = 2).toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("try {"))
        assertTrue(after, after.contains("} catch (e) {"))
        assertTrue(after, after.contains("console.error(e)"))
        assertTrue(after, after.contains("return res.json();"))
    }

    fun testWrapsKotlinLineWithTypedCatch() {
        val content = "fun f() {\n    risky()\n}\n"
        val edit = runReadAction { SurroundWithTryCatch(startLine = 2, endLine = 2).toEdit(ctxFor("A.kt", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("} catch (e: Exception) {"))
        assertTrue(after, after.contains("risky()"))
    }

    fun testNullWhenRangeBlank() {
        val content = "fun f() {\n\n}\n"
        assertNull(runReadAction { SurroundWithTryCatch(startLine = 2, endLine = 2).toEdit(ctxFor("A.kt", content)) })
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** (add to `FixOperation.kt`)

```kotlin
/** Wrap the lines [startLine]..[endLine] in `try { … } catch (…) { … }`, preserving indentation. KT uses a typed catch. */
@Serializable
@SerialName("surroundWithTryCatch")
data class SurroundWithTryCatch(
    val startLine: Int,
    val endLine: Int,
    val catchBody: String? = null,
) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val start = LineLocator.lineRange(ctx.content, startLine) ?: return null
        val end = LineLocator.lineRange(ctx.content, endLine) ?: return null
        if (start.first > end.last + 1) return null
        val block = ctx.content.substring(start.first, end.last + 1)
        if (block.isBlank()) return null
        val indent = block.takeWhile { it == ' ' || it == '\t' }
        val isKt = ctx.psiFile is KtFile
        val catchClause = if (isKt) "catch (e: Exception)" else "catch (e)"
        val body = catchBody ?: if (isKt) "e.printStackTrace()" else "console.error(e)"
        val inner = block.lines().joinToString("\n") { if (it.isBlank()) it else "    $it" }
        val wrapped = "${indent}try {\n$inner\n$indent} $catchClause {\n$indent    $body\n$indent}"
        return TextEdit(start.first, end.last + 1, wrapped)
    }
}
```

- [ ] **Step 4: Run → PASS** (3 tests). **Step 5: Commit** `git commit -m "feat(fix-engine): SurroundWithTryCatch operation (KT + TS)"`

---

### Task 4: Extend `AsyncFlowFixer` + tests + e2e

**Files:** Modify `fix/AsyncFlowFixer.kt`; Test (extend) `AsyncFlowFixerTest`; add `src/test/kotlin/com/ghostdebugger/fix/engine/AsyncFlowBreadthIntegrationTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/ghostdebugger/fix/AsyncFlowFixerPlanTest.kt`:

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.AddPromiseCatch
import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.SurroundWithTryCatch
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AsyncFlowFixerPlanTest : BasePlatformTestCase() {
    private fun issue(type: IssueType, line: Int) = Issue(
        id = "i", type = type, severity = IssueSeverity.ERROR, title = "t", description = "",
        filePath = "a.ts", line = line, ruleId = "AEG-ASYNC-001"
    )
    private fun ctxFor(content: String): FixContext {
        val psi = myFixture.configureByText("a.ts", content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testUnhandledPromiseEmitsAddPromiseCatch() {
        val content = "function f() {\n  doThing().then(handle);\n}\n"
        val plan = runReadAction {
            AsyncFlowFixer().generatePlan(issue(IssueType.UNHANDLED_PROMISE, 2), ctxFor(content))
        }!!
        assertTrue(plan.operations.single() is AddPromiseCatch)
    }

    fun testMissingErrorHandlingEmitsSurroundWithTryCatch() {
        val content = "async function f() {\n  return res.json();\n}\n"
        val plan = runReadAction {
            AsyncFlowFixer().generatePlan(issue(IssueType.MISSING_ERROR_HANDLING, 2), ctxFor(content))
        }!!
        assertTrue(plan.operations.single() is SurroundWithTryCatch)
    }

    fun testMemoryLeakDeclined() {
        val content = "useEffect(() => {\n  setInterval(tick, 1000);\n}, []);\n"
        assertNull(runReadAction {
            AsyncFlowFixer().generatePlan(issue(IssueType.MEMORY_LEAK, 2), ctxFor(content))
        })
    }
}
```

- [ ] **Step 2: Run → FAIL** (`generatePlan` returns null by default → `single()`/asserts fail).

- [ ] **Step 3: Extend `AsyncFlowFixer`**

Broaden `canFix` and add `generatePlan` (keep the existing `generateFix` as the text fallback). Add imports `com.ghostdebugger.fix.engine.*`:

```kotlin
    override fun canFix(issue: Issue): Boolean =
        issue.ruleId == ruleId &&
            issue.type in setOf(IssueType.UNHANDLED_PROMISE, IssueType.MISSING_ERROR_HANDLING)

    override fun generatePlan(issue: Issue, ctx: com.ghostdebugger.fix.engine.FixContext): com.ghostdebugger.fix.engine.FixPlan? {
        if (!canFix(issue)) return null
        val op: com.ghostdebugger.fix.engine.FixOperation? = when (issue.type) {
            IssueType.UNHANDLED_PROMISE -> com.ghostdebugger.fix.engine.AddPromiseCatch(issue.line)
            IssueType.MISSING_ERROR_HANDLING -> com.ghostdebugger.fix.engine.SurroundWithTryCatch(issue.line, issue.line)
            else -> null
        }
        // Confirm the op actually applies to the current content before proposing it (no-false-positive).
        val edit = op?.toEdit(ctx) ?: return null
        @Suppress("UNUSED_VARIABLE") val ignored = edit
        return com.ghostdebugger.fix.engine.FixPlan(issue.id, listOf(op))
    }
```

> Note: the `op.toEdit(ctx)` pre-check makes the fixer decline (null) when the op can't resolve (e.g. a `MISSING_ERROR_HANDLING` line that is blank, or an `UNHANDLED_PROMISE` line not ending in `);`), so a bad plan is never proposed. `generateFix` (unchanged) remains the text fallback for `UNHANDLED_PROMISE`.

- [ ] **Step 4: Run the plan test → PASS** (3 tests). Then run any existing `AsyncFlowFixerTest`: `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.AsyncFlowFixer*"`. If a pre-existing test asserted `canFix` is false for `MISSING_ERROR_HANDLING`, update it to reflect the broadened scope (now true). Keep the `generateFix`/`UNHANDLED_PROMISE` assertions intact.

- [ ] **Step 5: End-to-end (deterministic, mirrors Batch 1)**

Create `src/test/kotlin/com/ghostdebugger/fix/engine/AsyncFlowBreadthIntegrationTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Integration: registered AsyncFlowFixer.generatePlan → AddPromiseCatch → applyVerified applies the
 * `.catch(...)` edit. reanalyze is stubbed (gate verdict covered by 2b/2c-ii-a); content-based op, so
 * BasePlatformTestCase + Unconfined is sufficient.
 */
class AsyncFlowBreadthIntegrationTest : BasePlatformTestCase() {
    fun testRegisteredFixerAddsCatchThroughTheEngine() {
        val code = "function f() {\n  doThing().then(handle);\n}\n"
        val vf = myFixture.configureByText("a.ts", code).virtualFile
        val content = runReadAction { myFixture.getDocument(myFixture.file).text }
        val target = Issue(
            id = "p1", type = IssueType.UNHANDLED_PROMISE, severity = IssueSeverity.ERROR,
            title = "Unhandled promise rejection", description = "",
            filePath = vf.path, line = 2, ruleId = "AEG-ASYNC-001"
        )
        val result = runBlocking {
            FixEngine(project).fixVerified(
                target, vf, content, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }
        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(myFixture.file).text }.contains(".catch(console.error);"))
    }
}
```

Run → PASS (1 test).

- [ ] **Step 6: Commit** `git commit -m "feat(fix-engine): AsyncFlowFixer emits AddPromiseCatch/SurroundWithTryCatch via generatePlan + e2e"`

---

## Final verification

- [ ] `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.*" --tests "com.ghostdebugger.fix.engine.*"` → green.
- [ ] `JAVA_HOME=... ./gradlew test` → full suite green (capture gradlew's real exit, not the pipe's: append `; echo EXIT=${PIPESTATUS[0]}` if piping to `tail`).

## Self-Review (completed during planning)

- **Spec coverage:** `AddAwait`/`AddPromiseCatch`/`SurroundWithTryCatch` (spec §3.3) → Tasks 1-3; extended `AsyncFlowFixer` (§3.5) → Task 4. `MEMORY_LEAK` (timer cleanup) has no op in this batch — fixer declines it; an `AddTimerCleanup` op is future work.
- **Type consistency:** `AddAwait(line, call)`, `AddPromiseCatch(line, handler="console.error")`, `SurroundWithTryCatch(startLine, endLine, catchBody?)`, `LineLocator.{lineRange,indexOfOn}`, `FixPlan(issueId, operations)` — used identically across tasks.
- **No-false-positive:** TS-only ops return null on Kotlin; `AddPromiseCatch` requires a `);`-terminated chain and declines if `.catch(` already present; `AddAwait` declines if already awaited; `SurroundWithTryCatch` declines on a blank range; the fixer's `generatePlan` pre-checks `op.toEdit(ctx)` and declines if it can't apply.
- **Backward-compat:** `AsyncFlowFixer.generateFix` is unchanged (still the text fallback); only `canFix` broadens and `generatePlan` is added. Task 4 updates any pre-existing `canFix`-scope assertion.
- **Placeholders:** none — complete code per step; Task 4 names the one pre-existing-test-scope pitfall.
