# Catalog Breadth — Batch 3: Type / Conversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `AddExplicitConversion` operation and a `KotlinTypeMismatchFixer` (`AEG-TYPE-KT-001`) restricted to **lossless numeric widening** — the only provably intent-preserving type-mismatch fix.

**Architecture:** `AddExplicitConversion` is a content-based, language-agnostic op (suffix form `expr.toLong()` when `conversion` starts with `.`, else wrapper form `String(expr)`). `KotlinTypeMismatchFixer.generatePlan` parses the declared/actual types from the analyzer's issue description, accepts only widening pairs, locates the `KtProperty` initializer via plain PSI (no Analysis API → thread-safe on any call path), and emits `AddExplicitConversion`; it declines everything else (no-false-positive). Verification unchanged (Tier-2 gate). Spec: `docs/superpowers/specs/2026-06-02-fix-engine-catalog-breadth-design.md`.

**Scope decisions (deviations from the spec, approved):** the deterministic type-mismatch fixer is **widening-only** (the analyzer's own note says general type-mismatch fixes are undecidable; `.toString()`/`.toIntOrNull()` guess at intent and can mask a real bug). The spec's "extend JS/TS `NullSafetyFixer`" item is **dropped** — its existing `generateFix` (string/comment masking + double-`?.` guard) is more careful than the `WrapInSafeCall` op; routing it through the op would regress quality. Hardening the op's TS path is optional future work.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform (IPGP 2.14.0, 2024.3.2), kotlinx.serialization, JUnit4 `BasePlatformTestCase`.

---

## Prerequisites (test prelude)

Tests need a JBR; shell env does NOT persist between Bash commands. Prefix EVERY gradlew call inline:
```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` — sealed `FixOperation`; Batch 1/2 ops show the `LineLocator`-based pattern. `KtFile` already imported.
- `src/main/kotlin/com/ghostdebugger/fix/engine/LineLocator.kt` — `indexOfOn(content,line,token)`, `lineAt(content,offset)`.
- `src/main/kotlin/com/ghostdebugger/analysis/analyzers/KotlinTypeMismatchAnalyzer.kt` — `AEG-TYPE-KT-001`; flags `val x: T = expr`; issue `type = COMPILATION_ERROR`, `ruleId = AEG-TYPE-KT-001`, title `"Type mismatch on '<name>'"`, **description ends** `"… Declared: <declared>. Initializer: <actual>."` (types are `KaType.toString()`, may be FQ like `kotlin.Long`).
- `src/main/kotlin/com/ghostdebugger/fix/Fixer.kt` — `generatePlan(issue, ctx): FixPlan? = null` (Batch 1).
- `src/main/kotlin/com/ghostdebugger/fix/KotlinNullSafetyFixer.kt` — model for a PSI fixer using `generatePlan` + `LineLocator.lineAt`.
- `src/main/kotlin/com/ghostdebugger/fix/FixerRegistry.kt` — fixer list (currently 6); `src/test/kotlin/com/ghostdebugger/fix/FixerRegistryTest.kt` asserts the count.
- Note: like `AEG-NULL-KT-001`, a type mismatch is a compile error, so the static pipeline's early pass shadows `AEG-TYPE-KT-001` in single-file re-analysis — the e2e supplies the target directly + stubs `reanalyze` (Batch 1 pattern).

## File structure

- Add `AddExplicitConversion` to `fix/engine/FixOperation.kt`.
- Create `fix/KotlinTypeMismatchFixer.kt`; register in `fix/FixerRegistry.kt`; bump `FixerRegistryTest` count 6 → 7.
- Tests: `AddExplicitConversionTest.kt`, `KotlinTypeMismatchFixerTest.kt`, `TypeMismatchBreadthIntegrationTest.kt`.

---

### Task 1: `AddExplicitConversion` operation (KT + TS)

**Files:** Add to `fix/engine/FixOperation.kt`; Test `src/test/kotlin/com/ghostdebugger/fix/engine/AddExplicitConversionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AddExplicitConversionTest : BasePlatformTestCase() {
    private fun ctxFor(fileName: String, content: String): FixContext {
        val psi = myFixture.configureByText(fileName, content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testSuffixFormAppendsConversion() {
        val content = "fun f(n: Int): Long {\n    val x: Long = n\n}\n"
        val edit = runReadAction { AddExplicitConversion(line = 2, expr = "n", conversion = ".toLong()").toEdit(ctxFor("A.kt", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("val x: Long = n.toLong()"))
    }

    fun testWrapperFormWrapsExpression() {
        val content = "function f(n) {\n  const s = n;\n}\n"
        val edit = runReadAction { AddExplicitConversion(line = 2, expr = "n", conversion = "String").toEdit(ctxFor("a.ts", content)) }!!
        val after = content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
        assertTrue(after, after.contains("String(n)"))
    }

    fun testNullWhenExprAbsentOnLine() {
        val content = "fun f(): Long {\n    val x: Long = 1\n}\n"
        assertNull(runReadAction { AddExplicitConversion(line = 2, expr = "missing", conversion = ".toLong()").toEdit(ctxFor("A.kt", content)) })
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** (add to `FixOperation.kt`)

```kotlin
/**
 * Convert the first occurrence of [expr] on [line] using [conversion]: suffix form when [conversion]
 * starts with `.` (`expr` → `expr.toLong()`), else wrapper form (`expr` → `String(expr)`).
 * Language-agnostic (content-based). Returns null if [expr] is absent on [line].
 */
@Serializable
@SerialName("addExplicitConversion")
data class AddExplicitConversion(val line: Int, val expr: String, val conversion: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val at = LineLocator.indexOfOn(ctx.content, line, expr) ?: return null
        val end = at + expr.length
        val wrapped = if (conversion.startsWith(".")) "$expr$conversion" else "$conversion($expr)"
        return TextEdit(at, end, wrapped)
    }
}
```

- [ ] **Step 4: Run → PASS** (3 tests). **Step 5: Commit** `git commit -m "feat(fix-engine): AddExplicitConversion operation"`

---

### Task 2: `KotlinTypeMismatchFixer` (widening-only) + registration + e2e

**Files:** Create `fix/KotlinTypeMismatchFixer.kt`; Modify `fix/FixerRegistry.kt`, `src/test/kotlin/com/ghostdebugger/fix/FixerRegistryTest.kt`; Tests `KotlinTypeMismatchFixerTest.kt`, `src/test/kotlin/com/ghostdebugger/fix/engine/TypeMismatchBreadthIntegrationTest.kt`

- [ ] **Step 1: Write the failing fixer test**

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.AddExplicitConversion
import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KotlinTypeMismatchFixerTest : BasePlatformTestCase() {
    private fun issue(line: Int, declared: String, actual: String) = Issue(
        id = "i", type = IssueType.COMPILATION_ERROR, severity = IssueSeverity.ERROR,
        title = "Type mismatch on 'y'",
        description = "Declared type is not assignable from the initializer's type. Declared: $declared. Initializer: $actual.",
        filePath = "A.kt", line = line, ruleId = "AEG-TYPE-KT-001"
    )
    private fun ctxFor(content: String): FixContext {
        val psi = myFixture.configureByText("A.kt", content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testEmitsWideningConversionForIntToLong() {
        val content = "fun f(n: Int) {\n    val y: Long = n\n}\n"
        val plan = runReadAction { KotlinTypeMismatchFixer().generatePlan(issue(2, "Long", "Int"), ctxFor(content)) }!!
        val op = plan.operations.single()
        assertTrue(op.toString(), op is AddExplicitConversion)
        op as AddExplicitConversion
        assertEquals("n", op.expr)
        assertEquals(".toLong()", op.conversion)
    }

    fun testHandlesFullyQualifiedTypeNames() {
        val content = "fun f(n: Int) {\n    val y: Long = n\n}\n"
        val plan = runReadAction { KotlinTypeMismatchFixer().generatePlan(issue(2, "kotlin.Long", "kotlin.Int"), ctxFor(content)) }!!
        assertEquals(".toLong()", (plan.operations.single() as AddExplicitConversion).conversion)
    }

    fun testDeclinesNonWideningMismatch() {
        val content = "fun f(n: Int) {\n    val y: String = n\n}\n"
        assertNull(runReadAction { KotlinTypeMismatchFixer().generatePlan(issue(2, "String", "Int"), ctxFor(content)) })
    }
}
```

- [ ] **Step 2: Run → FAIL** (`KotlinTypeMismatchFixer` unresolved).

- [ ] **Step 3: Implement the fixer**

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.AddExplicitConversion
import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.FixPlan
import com.ghostdebugger.fix.engine.LineLocator
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Deterministic fixer for `AEG-TYPE-KT-001` restricted to **lossless numeric widening** (e.g.
 * `Int → Long` via `.toLong()`), the only provably intent-preserving type-mismatch fix. Parses the
 * declared/actual types from the issue description and declines anything that is not a widening pair
 * — broader conversions (`.toString()`, `.toIntOrNull()`, …) guess at intent and are left to the AI
 * planner (gate-verified). PSI-only (no Analysis API), so safe on any call thread.
 */
class KotlinTypeMismatchFixer : Fixer {
    override val ruleId = "AEG-TYPE-KT-001"
    override val description = "Applies a lossless numeric widening conversion for an assignable type mismatch; declines non-widening mismatches."

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? = null

    override fun generatePlan(issue: Issue, ctx: FixContext): FixPlan? {
        val ktFile = ctx.psiFile as? KtFile ?: return null
        val m = DESC_REGEX.find(issue.description) ?: return null
        val declared = simpleName(m.groupValues[1])
        val actual = simpleName(m.groupValues[2])
        if (declared !in (WIDENING[actual] ?: emptySet())) return null

        val prop = PsiTreeUtil.findChildrenOfType(ktFile, KtProperty::class.java).firstOrNull {
            LineLocator.lineAt(ctx.content, it.textOffset) == issue.line && it.initializer != null
        } ?: return null
        val initText = prop.initializer!!.text
        return FixPlan(issue.id, listOf(AddExplicitConversion(issue.line, initText, ".to$declared()")))
    }

    private fun simpleName(type: String): String = type.trim().substringAfterLast('.').removeSuffix("?")

    companion object {
        private val DESC_REGEX = Regex("""Declared: (.+?)\. Initializer: (.+)\.""")
        // actual → set of declared types reachable by a lossless widening conversion.
        private val WIDENING: Map<String, Set<String>> = mapOf(
            "Byte" to setOf("Short", "Int", "Long", "Float", "Double"),
            "Short" to setOf("Int", "Long", "Float", "Double"),
            "Char" to setOf("Int", "Long", "Float", "Double"),
            "Int" to setOf("Long", "Float", "Double"),
            "Long" to setOf("Float", "Double"),
            "Float" to setOf("Double"),
        )
    }
}
```

- [ ] **Step 4: Register + bump the count**

In `fix/FixerRegistry.kt`, add `KotlinTypeMismatchFixer()` to the `listOf(...)`. In `src/test/kotlin/com/ghostdebugger/fix/FixerRegistryTest.kt`, change the count assertion from `6` to `7` (rename the test accordingly).

- [ ] **Step 5: Run the fixer + registry tests → PASS**

`JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.KotlinTypeMismatchFixerTest" --tests "com.ghostdebugger.fix.FixerRegistryTest"` → green (3 + the registry suite).

- [ ] **Step 6: End-to-end (deterministic; manual target + stubbed reanalyze, Batch-1 pattern)**

Create `src/test/kotlin/com/ghostdebugger/fix/engine/TypeMismatchBreadthIntegrationTest.kt`:

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
 * Integration: registered KotlinTypeMismatchFixer.generatePlan → AddExplicitConversion → applyVerified
 * applies the widening conversion. Target supplied directly (a type mismatch is a compile error, so the
 * static pipeline's early pass shadows AEG-TYPE-KT-001 in single-file re-analysis) and reanalyze stubbed
 * (gate verdict covered by 2b/2c-ii-a). PSI-only fixer → BasePlatformTestCase + Unconfined.
 */
class TypeMismatchBreadthIntegrationTest : BasePlatformTestCase() {
    fun testRegisteredFixerWidensThroughTheEngine() {
        val code = "fun f(n: Int) {\n    val y: Long = n\n}\n"
        val vf = myFixture.configureByText("A.kt", code).virtualFile
        val content = runReadAction { myFixture.getDocument(myFixture.file).text }
        val target = Issue(
            id = "t1", type = IssueType.COMPILATION_ERROR, severity = IssueSeverity.ERROR,
            title = "Type mismatch on 'y'",
            description = "Declared type is not assignable from the initializer's type. Declared: Long. Initializer: Int.",
            filePath = vf.path, line = 2, ruleId = "AEG-TYPE-KT-001"
        )
        val result = runBlocking {
            FixEngine(project).fixVerified(
                target, vf, content, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }
        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(myFixture.file).text }.contains("val y: Long = n.toLong()"))
    }
}
```

Run → PASS (1 test).

- [ ] **Step 7: Commit** `git commit -m "feat(fix-engine): KotlinTypeMismatchFixer widening-only (AEG-TYPE-KT-001) + e2e"`

---

## Final verification

- [ ] `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.*" --tests "com.ghostdebugger.fix.engine.*"` → green.
- [ ] `JAVA_HOME=... ./gradlew test 2>&1 | tail -6; echo EXIT=${PIPESTATUS[0]}` → full suite green, `EXIT=0` (the registry-count change is the regression risk; verify `FixerRegistryTest` passes).

## Self-Review (completed during planning)

- **Spec coverage:** `AddExplicitConversion` (spec §3.3) → Task 1; `KotlinTypeMismatchFixer` (§3.5, narrowed to widening per the approved decision) → Task 2. JS/TS `NullSafetyFixer` extension intentionally dropped (rationale in header).
- **No-false-positive:** the fixer only acts on widening pairs (canonical, lossless, intent-preserving) and declines all else; `AddExplicitConversion` returns null when `expr` is absent; type-name parsing failure → no match → decline.
- **Type consistency:** `AddExplicitConversion(line, expr, conversion)`, `WIDENING[actual] → declared set`, `FixPlan(issueId, operations)`, `LineLocator.{indexOfOn,lineAt}` — consistent across tasks. Conversion suffix `.to$declared()` maps a widening target (`Long`→`.toLong()`, `Double`→`.toDouble()`, etc.).
- **Threading:** fixer is PSI-only (no Analysis API) → safe whether `derivePlan`'s read action runs on EDT (intention) or off-EDT (orchestrator). The e2e is deterministic (no Analysis API, Unconfined).
- **Registry:** count assertion bumped 6 → 7 (Task 4) — the lesson from Batch 1.
- **Placeholders:** none — complete code per step; the early-pass-shadowing caveat and its e2e workaround are stated.
