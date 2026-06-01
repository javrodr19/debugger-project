# Fix Engine — Phase 2a: PSI-aware Operation Catalog (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Evolve `FixOperation` from content-only to a PSI-aware context, then add the first real semantic operations (`ConvertToSafeCast`, `InsertImport`) — the building blocks the Phase 2c AI planner will compose.

**Architecture:** Phase 1 shipped `FixOperation.toEdit(content: String)` with only `ReplaceRange`. Semantic Kotlin ops need the PSI (AST + types), per spec §3.1. This plan introduces `FixContext(content, psiFile)` and changes the op contract to `toEdit(ctx): TextEdit?`. `ReplaceRange` ignores the PSI; new ops use it. `FixPlanApplicator` resolves the PSI in a read action before composing. Operations remain deterministic and return null rather than emit an invalid edit.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform 2024.3.2, Kotlin PSI (`org.jetbrains.kotlin.psi.*`), kotlinx.serialization. `BasePlatformTestCase`/`AegisKotlinAnalysisTestCase` for PSI tests. JBR required for the test task.

**Phase 2 decomposition (this is plan 2a of 3+):**
- **2a (this plan):** PSI-aware op model + initial operations. The catalog grows in small follow-up plans (`WrapInSafeCall`, `SurroundWithTryCatch`, `AddTimerCleanup`, `AddElvisReturn`) — each one task, same pattern as Task 2 here.
- **2b:** Tier-2 re-analysis verify gate (issue-gone + no-new-issues), incl. the transient-document mechanism for Kotlin type resolution.
- **2c:** `FixPlanner` (AI emits a plan from the op-catalog schema), bounded orchestration loop with verify feedback, AI semantic review, wire into `FixEngine`, remove `suggestFix`/`parseFixResponse`.

**Build/test prelude (run once per shell before any `./gradlew test`):**
```bash
export JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)"
export PATH="$JAVA_HOME/bin:$PATH"
```

**Conventions:** Every `catch (Exception)` rethrows `ProcessCanceledException` first. Ops return null, never an invalid edit. Kotlin analysis/PSI access happens inside a read action.

---

### Task 1: Evolve `FixOperation` to a PSI-aware `FixContext`

This reworks Phase-1 code. After it, `toEdit` takes a `FixContext` (content + lazy PSI) instead of a raw `String`.

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/FixContext.kt`
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` (signature), `FixPlan.kt` (`toEdits`), `FixPlanApplicator.kt` (build context in a read action), `FixEngine.kt` (unaffected — it calls `applicator`/`planFor`, not `toEdit`)
- Modify tests: `FixOperationTest.kt`, `FixPlanTest.kt`, `CodeFixAdapterTest.kt` (calls to `toEdit`/`toEdits` now pass a `FixContext`)
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixContextTest.kt`

- [ ] **Step 1: Write the failing test** `src/test/kotlin/com/ghostdebugger/fix/engine/FixContextTest.kt`

```kotlin
package com.ghostdebugger.fix.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FixContextTest {
    @Test fun `exposes content and lazily resolves psiFile only when asked`() {
        var calls = 0
        val ctx = FixContext("val a = 1") { calls++; null }
        assertEquals("val a = 1", ctx.content)
        assertEquals(0, calls)            // not resolved yet
        assertNull(ctx.psiFile)
        assertNull(ctx.psiFile)
        assertEquals(1, calls)            // resolved once, memoized
    }
}
```

- [ ] **Step 2: Run, confirm FAIL** (`FixContext` unresolved): `...prelude...; ./gradlew test --tests "com.ghostdebugger.fix.engine.FixContextTest"`

- [ ] **Step 3: Create `FixContext.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.intellij.psi.PsiFile

/**
 * Inputs an operation may need to compute its edit: the current file [content] (always available)
 * and the file's PSI, resolved lazily and memoized (Kotlin/Java ops need the AST + types; content-only
 * ops like ReplaceRange never trigger resolution). Construct PSI access inside a read action.
 */
class FixContext(val content: String, private val psiProvider: () -> PsiFile?) {
    val psiFile: PsiFile? by lazy(psiProvider)
}
```

- [ ] **Step 4: Change the `FixOperation` contract.** In `FixOperation.kt`, change the abstract method and `ReplaceRange`:

```kotlin
@Serializable
sealed class FixOperation {
    abstract fun toEdit(ctx: FixContext): TextEdit?
}

@Serializable
@SerialName("replaceRange")
data class ReplaceRange(val startOffset: Int, val endOffset: Int, val text: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val content = ctx.content
        if (startOffset < 0 || endOffset > content.length || startOffset > endOffset) return null
        return TextEdit(startOffset, endOffset, text)
    }
}
```

In `FixPlan.kt`, change `toEdits`:

```kotlin
fun toEdits(ctx: FixContext): List<TextEdit>? {
    val edits = ArrayList<TextEdit>(operations.size)
    for (op in operations) edits.add(op.toEdit(ctx) ?: return null)
    return edits
}
```

- [ ] **Step 5: Update `FixPlanApplicator` to build the context in a read action.** Replace the early `plan.toEdits(document.text)` line with PSI-aware resolution (the rest of `apply` is unchanged):

```kotlin
// after obtaining `document` (read action):
val edits = ApplicationManager.getApplication().runReadAction<List<TextEdit>?> {
    val ctx = FixContext(document.text) {
        com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
    }
    plan.toEdits(ctx)
} ?: return FixApplyResult.Rejected("Plan does not apply to current content (stale offsets).")
```

- [ ] **Step 6: Update the Phase-1 tests to pass a `FixContext`.** The pure-logic tests have no PSI, so use a null provider. Make these exact replacements:

In `FixOperationTest.kt` — replace each `op.toEdit("...")` / `ReplaceRange(...).toEdit("...")` call so the string becomes a context, e.g.:
```kotlin
// before: assertEquals(TextEdit(0, 5, "hello"), op.toEdit("XXXXX world"))
assertEquals(TextEdit(0, 5, "hello"), op.toEdit(FixContext("XXXXX world") { null }))
// before: assertNull(ReplaceRange(0, 50, "x").toEdit("short"))
assertNull(ReplaceRange(0, 50, "x").toEdit(FixContext("short") { null }))
```
(apply to all three calls in `ReplaceRange returns null ...`).

In `FixPlanTest.kt` — wrap each `plan.toEdits("...")`:
```kotlin
// before: plan.toEdits("hello world")
plan.toEdits(FixContext("hello world") { null })
// the applyTo test:
val c = "XXXXX world"
assertEquals("hello world", plan.toEdits(FixContext(c) { null })!!.applyTo(c))
```

In `CodeFixAdapterTest.kt` — the `toFixPlan(content)` call is unchanged (the adapter still takes content), but the round-trip apply uses `toEdits`:
```kotlin
// before: assertEquals("val a = 1\nval b = 3\n", plan.toEdits(content)!!.applyTo(content))
assertEquals("val a = 1\nval b = 3\n", plan.toEdits(FixContext(content) { null })!!.applyTo(content))
```

- [ ] **Step 7: Run the full engine suite, confirm PASS**

Run: `...prelude...; ./gradlew test --tests "com.ghostdebugger.fix.engine.*"`
Expected: PASS (all Phase-1 engine tests + `FixContextTest`).

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/ src/test/kotlin/com/ghostdebugger/fix/engine/
git commit -m "refactor(fix-engine): make FixOperation PSI-aware via FixContext"
```

---

### Task 2: `ConvertToSafeCast` operation

Generalizes `KotlinUnsafeCastFixer`: given the offset of an unsafe `as` cast, rewrite it to `as? T <fallback>` where the fallback is chosen from the enclosing function's return type (`?: return null` for nullable return, `?: return` for Unit, else `?: throw IllegalStateException(...)`). Locates the `KtBinaryExpressionWithTypeRHS` whose `as` keyword sits at the given offset.

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` (add the op)
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/ConvertToSafeCastTest.kt`

- [ ] **Step 1: Write the failing test** (`AegisKotlinAnalysisTestCase` gives a project-bound `KtFile` with type resolution):

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.intellij.openapi.application.runReadAction
import org.jetbrains.kotlin.psi.KtFile

class ConvertToSafeCastTest : AegisKotlinAnalysisTestCase() {

    private fun ktFile(src: String): KtFile =
        myFixture.configureByText("A.kt", src) as KtFile

    fun testRewritesUnsafeCastInNonNullReturnToThrowFallback() {
        val src = "fun run(a: Any): String { return a as String }\n"
        val file = ktFile(src)
        val offset = src.indexOf(" as ") + 1     // offset of the `as` keyword
        val ctx = FixContext(src) { file }
        val edit = runReadAction { ConvertToSafeCast(offset).toEdit(ctx) }!!
        val result = listOf(edit).applyTo(src)
        assertTrue(result, result.contains("a as? String ?: throw IllegalStateException"))
        assertFalse(result, result.contains(" as String"))   // no bare unsafe cast remains
    }

    fun testReturnsNullWhenNoCastAtOffset() {
        val src = "fun run(): Int { return 1 }\n"
        val file = ktFile(src)
        val ctx = FixContext(src) { file }
        assertNull(runReadAction { ConvertToSafeCast(0).toEdit(ctx) })
    }
}
```

- [ ] **Step 2: Run, confirm FAIL** (`ConvertToSafeCast` unresolved): `...prelude...; ./gradlew test --tests "com.ghostdebugger.fix.engine.ConvertToSafeCastTest"`

- [ ] **Step 3: Add the op to `FixOperation.kt`** (imports at top of file):

```kotlin
// add imports:
// import com.intellij.psi.util.PsiTreeUtil
// import org.jetbrains.kotlin.lexer.KtTokens
// import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
// import org.jetbrains.kotlin.psi.KtFile
// import org.jetbrains.kotlin.psi.KtNamedFunction

/** Rewrite the unsafe `as` cast whose `as` keyword is at [asOffset] into `as? T <fallback>`. */
@Serializable
@SerialName("convertToSafeCast")
data class ConvertToSafeCast(val asOffset: Int) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val ktFile = ctx.psiFile as? KtFile ?: return null
        val cast = PsiTreeUtil.findChildrenOfType(ktFile, KtBinaryExpressionWithTypeRHS::class.java)
            .firstOrNull {
                it.operationReference.getReferencedNameElementType() == KtTokens.AS_KEYWORD &&
                it.operationReference.textRange.startOffset == asOffset
            } ?: return null
        val targetText = cast.right?.text ?: return null
        val receiverText = cast.left.text
        val function = PsiTreeUtil.getParentOfType(cast, KtNamedFunction::class.java)
        val fallback = when (val ret = function?.typeReference?.text?.trim()) {
            null -> "?: throw IllegalStateException(\"Cast failed\")"
            "Unit" -> "?: return"
            else -> if (ret.endsWith("?")) "?: return null" else "?: throw IllegalStateException(\"Cast failed\")"
        }
        return TextEdit(cast.textRange.startOffset, cast.textRange.endOffset, "$receiverText as? $targetText $fallback")
    }
}
```

- [ ] **Step 4: Run, confirm PASS** (2 tests): `...prelude...; ./gradlew test --tests "com.ghostdebugger.fix.engine.ConvertToSafeCastTest"`

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt src/test/kotlin/com/ghostdebugger/fix/engine/ConvertToSafeCastTest.kt
git commit -m "feat(fix-engine): ConvertToSafeCast operation"
```

---

### Task 3: `InsertImport` operation

Adds an import line if absent. Content-based (no type resolution needed): inserts after the last existing `import ` line, else after the `package` line, else at the top. Returns null if the import is already present.

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` (add the op)
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/InsertImportTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InsertImportTest {
    private fun ctx(content: String) = FixContext(content) { null }

    @Test fun `inserts after the last existing import`() {
        val src = "package p\n\nimport a.B\nimport a.C\n\nfun f() {}\n"
        val edit = InsertImport("a.D").toEdit(ctx(src))!!
        val out = listOf(edit).applyTo(src)
        assertEquals("package p\n\nimport a.B\nimport a.C\nimport a.D\n\nfun f() {}\n", out)
    }

    @Test fun `inserts after package when no imports`() {
        val src = "package p\n\nfun f() {}\n"
        val out = listOf(InsertImport("a.D").toEdit(ctx(src))!!).applyTo(src)
        assertEquals("package p\nimport a.D\n\nfun f() {}\n", out)
    }

    @Test fun `returns null when the import already exists`() {
        val src = "package p\n\nimport a.D\n\nfun f() {}\n"
        assertNull(InsertImport("a.D").toEdit(ctx(src)))
    }
}
```

- [ ] **Step 2: Run, confirm FAIL** (`InsertImport` unresolved): `...prelude...; ./gradlew test --tests "com.ghostdebugger.fix.engine.InsertImportTest"`

- [ ] **Step 3: Add the op to `FixOperation.kt`**

```kotlin
/** Insert `import [fqName]` if not already present (after the last import, else after package, else top). */
@Serializable
@SerialName("insertImport")
data class InsertImport(val fqName: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val content = ctx.content
        val lines = content.lines()
        val importLine = "import $fqName"
        if (lines.any { it.trim() == importLine }) return null   // already imported

        val starts = lineStartOffsets(content)   // from CodeFixAdapter.kt (same package)
        val lastImport = lines.indexOfLast { it.trim().startsWith("import ") }
        val pkg = lines.indexOfFirst { it.trim().startsWith("package ") }
        val anchorLine = when {
            lastImport >= 0 -> lastImport
            pkg >= 0 -> pkg
            else -> -1
        }
        // Insert at the END of the anchor line (before its newline); at offset 0 if no anchor.
        val insertOffset = if (anchorLine < 0) 0
            else if (anchorLine + 1 < starts.size) starts[anchorLine + 1] - 1 else content.length
        val text = if (anchorLine < 0) "$importLine\n" else "\n$importLine"
        return TextEdit(insertOffset, insertOffset, text)
    }
}
```

- [ ] **Step 4: Run, confirm PASS** (3 tests): `...prelude...; ./gradlew test --tests "com.ghostdebugger.fix.engine.InsertImportTest"`

- [ ] **Step 5: Run the FULL engine suite (regression after adding ops)**

Run: `...prelude...; ./gradlew test --tests "com.ghostdebugger.fix.engine.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt src/test/kotlin/com/ghostdebugger/fix/engine/InsertImportTest.kt
git commit -m "feat(fix-engine): InsertImport operation"
```

---

## Self-Review

- **Spec coverage (2a slice):** PSI-aware op model (spec §3.1 `edit(file: PsiFile)`) → Task 1 (`FixContext` + signature change). Initial semantic ops → `ConvertToSafeCast` (Task 2, generalizes `KotlinUnsafeCastFixer`) + `InsertImport` (Task 3). Remaining catalog ops (`WrapInSafeCall`, `SurroundWithTryCatch`, `AddTimerCleanup`, `AddElvisReturn`) are explicitly deferred to follow-up one-task plans (the spec says the catalog "starts small and grows") — not gaps, scoped growth. The Tier-2 gate and AI planner are plans 2b/2c.
- **Behavior preservation:** Task 1 reworks Phase-1 internals but is behavior-preserving — `ReplaceRange` still produces the same edit from `ctx.content`; `FixPlanApplicator` resolves PSI in a read action then applies identically; all Phase-1 engine tests are updated to pass a `FixContext` and must stay green (Step 7).
- **Type consistency:** `FixContext(content, () -> PsiFile?)` with lazy `psiFile`; `FixOperation.toEdit(ctx: FixContext)`; `FixPlan.toEdits(ctx)`; ops reuse `lineStartOffsets` (already `internal` in `CodeFixAdapter.kt`, same package). `ConvertToSafeCast(asOffset)` / `InsertImport(fqName)` are `@Serializable @SerialName(...)` like `ReplaceRange`, so the polymorphic-JSON contract (Phase-1 `FixOperationTest`) extends cleanly.
- **Placeholders:** none — every op has full code and tests; no "similar to" references.
- **Risk:** `ConvertToSafeCast` requires the PSI to resolve in-module (it uses the project-bound `KtFile`); the test uses `AegisKotlinAnalysisTestCase` for that. At runtime the op is fed the real file's `KtFile` via `FixPlanApplicator`'s `PsiManager.findFile`, so resolution works — the same property Phase 2b's gate will rely on.
