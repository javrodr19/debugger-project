# Simplification B2 — CollapseBooleanReturn + ComplexitySimplifierFixer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make deterministic code-simplification live end-to-end: a `CollapseBooleanReturn` operation that rewrites `if (C) return true else return false` → `return C` (negated → `return !C`), a `ComplexitySimplifierFixer` that scans an `AEG-CPX-001`-flagged file and emits one collapse per site, and the `FixEngine` wiring that routes `AEG-CPX-001` through B1's complexity-aware acceptance gate.

**Architecture:** A single `internal BooleanReturnCollapse` helper holds the detection logic (Kotlin PSI primary via `KtIfExpression`; JS/TS anchored-regex best-effort) and exposes two surfaces — `sites(ctx)` (all collapsible lines, for the fixer) and `collapseOnLine(ctx, line)` (the resolved edit, for the op). The serializable `CollapseBooleanReturn` op and the `ComplexitySimplifierFixer` are thin delegates. `FixEngine` selects B1's `ComplexityVerifier` acceptance for rule `AEG-CPX-001`, deriving `functionCount` from content via a small `FunctionCounter`; every other rule's call stays byte-for-byte unchanged (uses `applyVerified`'s built-in `FixVerifier` default).

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform (Kotlin PSI: `KtIfExpression`/`KtReturnExpression`/`KtConstantExpression`/`KtBlockExpression`), kotlinx.serialization (closed-polymorphic sealed `FixOperation`, discriminator `type`), kotlinx-coroutines, JUnit4 + `BasePlatformTestCase`.

**Spec:** `docs/superpowers/specs/2026-06-04-code-simplification-design.md` (§3.3 op, §3.4 fixer, §3.5 wiring, §4 verification, §5 testing).

---

## Prerequisites (test prelude)

Tests need a JetBrains Runtime; the shell env does **not** persist between Bash calls. Prefix **every** gradlew invocation inline:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

A piped gradle run masks the build exit code (`./gradlew … | tail` returns tail's 0). When you need the real status, append `; echo EXIT=${PIPESTATUS[0]}` and treat a non-zero `EXIT` (or any `BUILD FAILED`) as failure regardless of tail output.

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` — the sealed `FixOperation` (14 ops). Each is `@Serializable @SerialName(...)` and implements `toEdit(ctx: FixContext): TextEdit?` (return null = does not apply, never an invalid edit). New op appended here.
- `src/main/kotlin/com/ghostdebugger/fix/engine/LineLocator.kt` — `lineRange`, `indexOfOn`, `lineAt(content, offset): Int` (1-based). Used for line↔offset mapping.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixContext.kt` — `FixContext(val content: String, psiProvider)`, `psiFile: PsiFile?` lazy. `psiFile is KtFile` ⇒ Kotlin path; else content/regex path.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalog.kt` — `entries: List<String>` (one JSON-schema line per op) + `serialNames()`. `PromptTemplates.planFix` renders these.
- `src/test/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalogTest.kt` — `entriesAreNonEmptyAndTypePrefixed` asserts `entries.size == 14`; `everySealedOperationHasExactlyOneCatalogEntry` derives the registered set dynamically.
- `src/main/kotlin/com/ghostdebugger/fix/engine/ComplexityVerifier.kt` (B1) — `ComplexityVerifier(functionCount).decide(target, baselineForFile, originalContent, candidateContent, candidateForFile): VerifyDecision`. Accepts iff no other-rule regression **and** `estimateComplexity` strictly decreases.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt` (B1) — `applyVerified(..., acceptance: (orig, cand, candIssues) -> VerifyDecision = <FixVerifier default>, edtContext)`. The `acceptance` seam is how B2 injects `ComplexityVerifier`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt` — `fixVerified(...)` and `fixSupervised(...)`. Both reach `applicator.applyVerified(...)`; B2 adds the CPX acceptance selection here.
- `src/main/kotlin/com/ghostdebugger/fix/Fixer.kt` — `generatePlan(issue, ctx: FixContext): FixPlan? = null` (op path; tried first by `FixDeriver.derivePlan`). `generateFix(issue, fileContent): CodeFix?` (legacy; new fixer returns null).
- `src/main/kotlin/com/ghostdebugger/fix/KotlinNullSafetyFixer.kt` — the canonical op-only fixer to mirror (`generateFix` → null, all logic in `generatePlan`, PSI-only, no Analysis API).
- `src/main/kotlin/com/ghostdebugger/fix/FixerRegistry.kt` — `fixers` list (currently 7). New fixer registered here.
- `src/test/kotlin/com/ghostdebugger/fix/FixerRegistryTest.kt` — `` `all() returns exactly 7 entries` `` asserts count 7 → bump to 8.
- `src/main/kotlin/com/ghostdebugger/graph/GraphBuilder.kt:121` — `internal fun estimateComplexity(content, functionCount)` (member of `GraphBuilder`; companion holds `COMPLEXITY_PATTERNS`). `GraphBuilder.build` computes `estimateComplexity(file.content, file.functions.size)`.
- `src/main/kotlin/com/ghostdebugger/parser/TsJsRegexSymbolExtractor.kt:103` — `internal fun maskStringsAndComments(src: String): String` (reused so control-flow keywords inside strings/comments aren't counted).
- `src/test/kotlin/com/ghostdebugger/fix/engine/NullSafetyBreadthIntegrationTest.kt` and `TypeMismatchBreadthIntegrationTest.kt` — the Batch e2e pattern: `BasePlatformTestCase`, `FixEngine(project).fixVerified(target, vf, content, baselineForFile = listOf(target), reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined)`, assert `Success` + edited text.
- `src/test/kotlin/com/ghostdebugger/fix/engine/ApplyVerifiedComplexityTest.kt` (B1) — shows the complexity acceptance lambda shape and the `cpxTarget` issue.

## Kotlin PSI house style (confirmed in repo)

`KotlinAnalysisHelpers.kt:145-147` uses `ifExpr.condition` and `ifExpr.then` as **properties**; `else` is the keyword-escaped property `` ifExpr.`else` ``. `KotlinRedundantLetFixer.kt:75` uses `body.statements.singleOrNull()` on a `KtBlockExpression`. A `KtIfExpression`'s `textRange.startOffset` points at the `if` keyword (leading indentation is **not** included), so replacing `[startOffset, endOffset)` preserves the line's indent.

## File structure

- **Create** `src/main/kotlin/com/ghostdebugger/fix/engine/BooleanReturnCollapse.kt` — detection helper (Kotlin PSI + JS/TS regex).
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` — append `CollapseBooleanReturn` op (thin delegate).
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalog.kt` — add the catalog entry.
- **Create** `src/main/kotlin/com/ghostdebugger/fix/engine/FunctionCounter.kt` — content-based function counter.
- **Create** `src/main/kotlin/com/ghostdebugger/fix/ComplexitySimplifierFixer.kt` — `AEG-CPX-001` fixer.
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/FixerRegistry.kt` — register the fixer.
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt` — route `AEG-CPX-001` through `ComplexityVerifier`.
- Tests created alongside each (paths in each task).

---

### Task 1: `BooleanReturnCollapse` helper + `CollapseBooleanReturn` op

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/BooleanReturnCollapse.kt`
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` (append the op)
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/CollapseBooleanReturnTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/fix/engine/CollapseBooleanReturnTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CollapseBooleanReturnTest : BasePlatformTestCase() {
    private fun ktCtx(content: String): FixContext {
        val psi = myFixture.configureByText("A.kt", content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    // Pure content ctx for the JS/TS regex path (no PSI).
    private fun tsCtx(content: String) = FixContext(content) { null }

    private fun applied(content: String, ctx: FixContext, line: Int): String? {
        val edit = runReadAction { CollapseBooleanReturn(line).toEdit(ctx) } ?: return null
        return content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
    }

    fun testKtPositiveInline() {
        val c = "fun f(a: Boolean): Boolean {\n    if (a) return true else return false\n}\n"
        assertEquals(
            "fun f(a: Boolean): Boolean {\n    return a\n}\n",
            applied(c, ktCtx(c), 2)
        )
    }

    fun testKtNegatedInline() {
        val c = "fun f(a: Boolean): Boolean {\n    if (a) return false else return true\n}\n"
        assertEquals(
            "fun f(a: Boolean): Boolean {\n    return !a\n}\n",
            applied(c, ktCtx(c), 2)
        )
    }

    fun testKtNegatedCompoundConditionGetsParens() {
        val c = "fun f(a: Boolean, b: Boolean): Boolean {\n    if (a && b) return false else return true\n}\n"
        assertEquals(
            "fun f(a: Boolean, b: Boolean): Boolean {\n    return !(a && b)\n}\n",
            applied(c, ktCtx(c), 2)
        )
    }

    fun testKtBlockBodies() {
        val c = "fun f(a: Boolean): Boolean {\n    if (a) {\n        return true\n    } else {\n        return false\n    }\n}\n"
        assertEquals(
            "fun f(a: Boolean): Boolean {\n    return a\n}\n",
            applied(c, ktCtx(c), 2)
        )
    }

    fun testKtDeclinesWhenNotBooleanReturns() {
        val c = "fun f(a: Boolean): Int {\n    if (a) return 1 else return 2\n}\n"
        assertNull(runReadAction { CollapseBooleanReturn(2).toEdit(ktCtx(c)) })
    }

    fun testKtDeclinesWhenNoElse() {
        val c = "fun f(a: Boolean): Boolean {\n    if (a) return true\n    return false\n}\n"
        assertNull(runReadAction { CollapseBooleanReturn(2).toEdit(ktCtx(c)) })
    }

    fun testSitesFindsEveryCollapsibleLine() {
        val c = "fun f(a: Boolean, b: Boolean) {\n" +
            "    if (a) return true else return false\n" +
            "    println(1)\n" +
            "    if (b) return false else return true\n" +
            "}\n"
        val sites = runReadAction { BooleanReturnCollapse.sites(ktCtx(c)) }
        assertEquals(listOf(2, 4), sites)
    }

    fun testTsInlinePositive() {
        val c = "function f(a) {\n  if (a) return true; else return false;\n}\n"
        assertEquals(
            "function f(a) {\n  return a;\n}\n",
            applied(c, tsCtx(c), 2)
        )
    }
}
```

- [ ] **Step 2: Run → FAIL** (`BooleanReturnCollapse` / `CollapseBooleanReturn` unresolved).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.CollapseBooleanReturnTest"`

- [ ] **Step 3: Implement `BooleanReturnCollapse.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression

/**
 * Detection + resolution for the boolean-return collapse simplification:
 *   `if (C) return true else return false` -> `return C`
 *   `if (C) return false else return true` -> `return !C`
 *
 * Kotlin is the primary, PSI-verified path (a `KtIfExpression` whose both branches are
 * boolean-literal returns). JS/TS has no Community PSI, so it is a conservative anchored-regex
 * best-effort (a malformed collapse is caught by the engine's Tier-1 / complexity gate). Shared by
 * the [CollapseBooleanReturn] op (one line) and [com.ghostdebugger.fix.ComplexitySimplifierFixer]
 * (whole-file scan), so both agree on what is collapsible.
 */
internal object BooleanReturnCollapse {

    /** A resolved collapse: replace the half-open range [[startOffset], [endOffset]) with [replacement]. */
    data class Collapse(val startOffset: Int, val endOffset: Int, val replacement: String)

    /** Every distinct 1-based line that begins a collapsible boolean-return if/else, in source order. */
    fun sites(ctx: FixContext): List<Int> {
        val ktFile = ctx.psiFile as? KtFile
        return if (ktFile != null) {
            PsiTreeUtil.findChildrenOfType(ktFile, KtIfExpression::class.java)
                .filter { ktCollapse(it) != null }
                .map { LineLocator.lineAt(ctx.content, it.textRange.startOffset) }
                .distinct()
        } else {
            JS_RE.findAll(ctx.content)
                .filter { it.groupValues[2] != it.groupValues[3] }
                .map { LineLocator.lineAt(ctx.content, it.range.first) }
                .distinct()
                .toList()
        }
    }

    /** The collapse anchored at the if/else starting on 1-based [line], or null if none/ambiguous. */
    fun collapseOnLine(ctx: FixContext, line: Int): Collapse? {
        val ktFile = ctx.psiFile as? KtFile
        if (ktFile != null) {
            val ifExpr = PsiTreeUtil.findChildrenOfType(ktFile, KtIfExpression::class.java).firstOrNull {
                LineLocator.lineAt(ctx.content, it.textRange.startOffset) == line && ktCollapse(it) != null
            } ?: return null
            val replacement = ktCollapse(ifExpr) ?: return null
            return Collapse(ifExpr.textRange.startOffset, ifExpr.textRange.endOffset, replacement)
        }
        val m = JS_RE.findAll(ctx.content).firstOrNull {
            LineLocator.lineAt(ctx.content, it.range.first) == line && it.groupValues[2] != it.groupValues[3]
        } ?: return null
        val cond = m.groupValues[1].trim()
        val replacement = if (m.groupValues[2] == "true") "return $cond;" else "return !($cond);"
        return Collapse(m.range.first, m.range.last + 1, replacement)
    }

    // --- Kotlin PSI ---

    /** The collapsed `return …` text for [ifExpr], or null if it is not a boolean-return if/else. */
    private fun ktCollapse(ifExpr: KtIfExpression): String? {
        val thenBool = branchBool(ifExpr.then) ?: return null
        val elseBool = branchBool(ifExpr.`else`) ?: return null
        if (thenBool == elseBool) return null  // `if (C) return true else return true` is not a clean collapse
        val cond = ifExpr.condition ?: return null
        return if (thenBool) "return ${cond.text}" else "return ${negate(cond)}"
    }

    /** true/false if [branch] is (a block wrapping) a `return true` / `return false`; else null. */
    private fun branchBool(branch: KtExpression?): Boolean? {
        val ret = when (branch) {
            is KtReturnExpression -> branch
            is KtBlockExpression -> branch.statements.singleOrNull() as? KtReturnExpression
            else -> null
        } ?: return null
        return when ((ret.returnedExpression as? KtConstantExpression)?.text) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /** `!cond` for a trivial condition; `!(cond)` for a compound one (keeps the result parse-clean). */
    private fun negate(cond: KtExpression): String {
        val trivial = cond is KtNameReferenceExpression || cond is KtCallExpression ||
            cond is KtDotQualifiedExpression || cond is KtParenthesizedExpression
        return if (trivial) "!${cond.text}" else "!(${cond.text})"
    }

    // --- JS/TS regex (best-effort) ---
    // group1 = condition, group2 = then-literal, group3 = else-literal. Braces and semicolons optional;
    // DOT_MATCHES_ALL so a block form spanning lines still matches. Conservative: declines on anything
    // that does not fit the exact `if (...) return <bool> [;] else return <bool> [;]` shape.
    private val JS_RE = Regex(
        """if\s*\(\s*(.+?)\s*\)\s*\{?\s*return\s+(true|false)\s*;?\s*\}?\s*else\s*\{?\s*return\s+(true|false)\s*;?\s*\}?""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
}
```

- [ ] **Step 4: Append the `CollapseBooleanReturn` op to `FixOperation.kt`**

Add at the end of the sealed hierarchy (after `InsertStatementAfter`, before the file's closing). It is a thin delegate — all logic lives in the helper:

```kotlin
/**
 * Collapse the boolean-return if/else beginning on [line]: `if (C) return true else return false`
 * -> `return C` (negated branch order -> `return !C`). Kotlin uses PSI; JS/TS a best-effort regex.
 * Returns null when no such pattern starts on [line].
 */
@Serializable
@SerialName("collapseBooleanReturn")
data class CollapseBooleanReturn(val line: Int) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val c = BooleanReturnCollapse.collapseOnLine(ctx, line) ?: return null
        return TextEdit(c.startOffset, c.endOffset, c.replacement)
    }
}
```

(No new imports needed in `FixOperation.kt`: `BooleanReturnCollapse` is the same package; `@Serializable`/`@SerialName`/`TextEdit`/`FixContext` are already imported.)

- [ ] **Step 5: Run → PASS** (8 tests).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.CollapseBooleanReturnTest"`

Expected: all green. If `testKtBlockBodies` fails on indentation, confirm the replacement is exactly `return a` (the op replaces from the `if` token, so the leading `    ` before `if` is preserved by the surrounding content, not the replacement).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/BooleanReturnCollapse.kt \
        src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/CollapseBooleanReturnTest.kt
git commit -m "feat(fix-engine): CollapseBooleanReturn op + BooleanReturnCollapse detection helper"
```

---

### Task 2: Catalog entry + coverage bump + codec round-trip

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalog.kt`
- Modify: `src/test/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalogTest.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanCodecCollapseTest.kt`

- [ ] **Step 1: Update the coverage test count + add the codec round-trip test**

In `FixOperationCatalogTest.kt`, change the count assertion from 14 to 15:

```kotlin
        assertEquals(15, FixOperationCatalog.entries.size)
```

Create `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanCodecCollapseTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixPlanCodecCollapseTest {
    @Test fun decodesCollapseBooleanReturnFromPlannerJson() {
        val raw = """{"issueId":"i1","operations":[{"type":"collapseBooleanReturn","line":7}]}"""
        val plan = FixPlanCodec.decode(raw)!!
        assertEquals("i1", plan.issueId)
        assertEquals(1, plan.operations.size)
        val op = plan.operations[0]
        assertTrue(op.toString(), op is CollapseBooleanReturn)
        assertEquals(7, (op as CollapseBooleanReturn).line)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`entriesAreNonEmptyAndTypePrefixed` fails 14≠15; `everySealedOperationHasExactlyOneCatalogEntry` fails — `collapseBooleanReturn` is a registered subclass with no catalog entry; codec test passes already since decoding is auto-polymorphic).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixOperationCatalogTest" --tests "com.ghostdebugger.fix.engine.FixPlanCodecCollapseTest"`

- [ ] **Step 3: Add the catalog entry**

In `FixOperationCatalog.kt`, append to the `entries` list (after the `insertStatementAfter` line, keeping the trailing comma style):

```kotlin
        """{"type":"collapseBooleanReturn","line":<int>} // if (C) return true else return false -> return C (negated -> return !C); simplification""",
```

- [ ] **Step 4: Run → PASS**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixOperationCatalogTest" --tests "com.ghostdebugger.fix.engine.FixPlanCodecCollapseTest" --tests "com.ghostdebugger.ai.PromptTemplatesPlanFixTest"`

Expected: all green. `PromptTemplatesPlanFixTest.everyCatalogOpAppearsInThePrompt` now also asserts the new op renders into the planner prompt (it iterates `serialNames()`), so the AI can compose it — no edit needed there.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalog.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/FixOperationCatalogTest.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanCodecCollapseTest.kt
git commit -m "feat(fix-engine): expose collapseBooleanReturn in the AI op catalog"
```

---

### Task 3: `FunctionCounter`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/FunctionCounter.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FunctionCounterTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/fix/engine/FunctionCounterTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionCounterTest {
    @Test fun countsKotlinFunKeywords() {
        assertEquals(2, FunctionCounter.count("fun a() {}\nfun b() {}\n"))
    }

    @Test fun countsJsFunctionsAndArrows() {
        assertEquals(2, FunctionCounter.count("function f() {}\nconst g = () => 1\n"))
    }

    @Test fun ignoresKeywordsInStringsAndComments() {
        // `fun`/`=>` inside a string and a comment must not be counted; only the real `fun a` counts.
        assertEquals(1, FunctionCounter.count("// fun in a comment =>\nval s = \"fun x => y\"\nfun a() {}\n"))
    }

    @Test fun neverReturnsBelowOne() {
        assertEquals(1, FunctionCounter.count("val x = 1\n"))
    }
}
```

- [ ] **Step 2: Run → FAIL** (`FunctionCounter` unresolved).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FunctionCounterTest"`

- [ ] **Step 3: Implement `FunctionCounter.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.parser.TsJsRegexSymbolExtractor

/**
 * Content-based function count for the complexity divisor used by [ComplexityVerifier]
 * (`estimateComplexity = 1 + decisionPoints / functionCount`). Counts Kotlin `fun`, JS/TS
 * `function`, and arrow `=>` declarations over comment/string-masked content (reusing the same
 * masker `estimateComplexity` uses), so keywords inside literals or doc comments are not counted.
 *
 * This is a *stability* parameter, not a fidelity one: the gate holds it constant across the
 * original and the candidate (branch-elimination never adds or removes functions) and checks a
 * strict decrease, so any reasonable, stable count yields a correct verdict. Floored at 1 to keep
 * the metric finite for a file with no detected function.
 */
object FunctionCounter {
    private val PATTERNS = listOf(
        Regex("""\bfun\b"""),
        Regex("""\bfunction\b"""),
        Regex("""=>""")
    )

    fun count(content: String): Int {
        val masked = TsJsRegexSymbolExtractor().maskStringsAndComments(content)
        val n = PATTERNS.sumOf { pattern -> pattern.findAll(masked).count() }
        return n.coerceAtLeast(1)
    }
}
```

- [ ] **Step 4: Run → PASS** (4 tests).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FunctionCounterTest"`

Expected: all green. If `ignoresKeywordsInStringsAndComments` fails, verify `maskStringsAndComments` replaces literal/comment bodies (it preserves line structure but blanks content) — the masked text must not contain the `fun`/`=>` inside the string/comment.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FunctionCounter.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/FunctionCounterTest.kt
git commit -m "feat(fix-engine): FunctionCounter (content-based complexity divisor)"
```

---

### Task 4: `ComplexitySimplifierFixer` + registry

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/ComplexitySimplifierFixer.kt`
- Modify: `src/main/kotlin/com/ghostdebugger/fix/FixerRegistry.kt`
- Modify: `src/test/kotlin/com/ghostdebugger/fix/FixerRegistryTest.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/ComplexitySimplifierFixerTest.kt`

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/ghostdebugger/fix/ComplexitySimplifierFixerTest.kt`:

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.CollapseBooleanReturn
import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ComplexitySimplifierFixerTest : BasePlatformTestCase() {
    private fun cpxIssue() = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CPX-001"
    )
    private fun ctxFor(content: String): FixContext {
        val psi = myFixture.configureByText("A.kt", content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testEmitsOneCollapsePerSite() {
        val content = "fun f(a: Boolean, b: Boolean) {\n" +
            "    if (a) return true else return false\n" +
            "    if (b) return false else return true\n" +
            "}\n"
        val plan = runReadAction { ComplexitySimplifierFixer().generatePlan(cpxIssue(), ctxFor(content)) }!!
        assertEquals(2, plan.operations.size)
        assertEquals(listOf(2, 3), plan.operations.map { (it as CollapseBooleanReturn).line })
    }

    fun testDeclinesWhenNoCollapsibleSite() {
        val content = "fun f(a: Boolean): Int {\n    if (a) return 1 else return 2\n}\n"
        assertNull(runReadAction { ComplexitySimplifierFixer().generatePlan(cpxIssue(), ctxFor(content)) })
    }

    fun testGenerateFixReturnsNull() {
        // op-only fixer (mirrors KotlinNullSafetyFixer): the legacy CodeFix path is unused.
        assertNull(ComplexitySimplifierFixer().generateFix(cpxIssue(), "whatever"))
    }
}
```

In `FixerRegistryTest.kt`, bump the count assertion 7 → 8:

```kotlin
    @Test fun `all() returns exactly 8 entries`() { assertEquals(8, FixerRegistry.all().size) }
```

- [ ] **Step 2: Run → FAIL** (`ComplexitySimplifierFixer` unresolved; registry test 7≠8).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.ComplexitySimplifierFixerTest" --tests "com.ghostdebugger.fix.FixerRegistryTest"`

- [ ] **Step 3: Implement `ComplexitySimplifierFixer.kt`**

```kotlin
package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.BooleanReturnCollapse
import com.ghostdebugger.fix.engine.CollapseBooleanReturn
import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.FixPlan
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue

/**
 * Deterministic simplifier for high-complexity files (`AEG-CPX-001`). The issue is file-level
 * (line = 1), so this scans the whole file for collapsible boolean-return if/else sites
 * ([BooleanReturnCollapse.sites]) and emits one [CollapseBooleanReturn] per site. Each op resolves
 * its edit against the original content (absolute offsets), so the multi-op plan applies cleanly
 * (the applicator sorts edits descending by offset — no line-shift interference).
 *
 * Declines (null) when no collapsible site exists, leaving the file to the AI extract-method path
 * (a deliberate follow-on). The strict-complexity-decrease verdict is enforced downstream by
 * [com.ghostdebugger.fix.engine.ComplexityVerifier]; this fixer only proposes.
 */
class ComplexitySimplifierFixer : Fixer {
    override val ruleId = "AEG-CPX-001"
    override val description = "Simplifies high-complexity code by collapsing boolean-return if/else into a single return."

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? = null

    override fun generatePlan(issue: Issue, ctx: FixContext): FixPlan? {
        val lines = BooleanReturnCollapse.sites(ctx)
        if (lines.isEmpty()) return null
        return FixPlan(issue.id, lines.map { CollapseBooleanReturn(it) })
    }
}
```

- [ ] **Step 4: Register it in `FixerRegistry.kt`**

Add `ComplexitySimplifierFixer()` to the `fixers` list:

```kotlin
    private val fixers: Map<String, Fixer> = listOf(
        NullSafetyFixer(),
        StateInitFixer(),
        AsyncFlowFixer(),
        KotlinUnsafeCastFixer(),
        KotlinRedundantLetFixer(),
        KotlinNullSafetyFixer(),
        KotlinTypeMismatchFixer(),
        ComplexitySimplifierFixer()
    ).associateBy { it.ruleId }
```

- [ ] **Step 5: Run → PASS**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.ComplexitySimplifierFixerTest" --tests "com.ghostdebugger.fix.FixerRegistryTest" --tests "com.ghostdebugger.fix.FixerContractTest"`

Expected: all green (`FixerContractTest` iterates `all()` generically, so the new fixer is exercised by the contract without edits).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/ComplexitySimplifierFixer.kt \
        src/main/kotlin/com/ghostdebugger/fix/FixerRegistry.kt \
        src/test/kotlin/com/ghostdebugger/fix/FixerRegistryTest.kt \
        src/test/kotlin/com/ghostdebugger/fix/ComplexitySimplifierFixerTest.kt
git commit -m "feat(fix-engine): ComplexitySimplifierFixer (AEG-CPX-001 -> CollapseBooleanReturn plan)"
```

---

### Task 5: Route `AEG-CPX-001` through the complexity-aware acceptance in `FixEngine`

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineComplexityRoutingTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineComplexityRoutingTest.kt`:

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
 * Proves AEG-CPX-001 is judged by ComplexityVerifier, not FixVerifier. The injected plan only renames
 * `g()`->`k()` (complexity unchanged). FixVerifier would ACCEPT (the target's rule count drops 1->0 in
 * the empty re-analysis = "target resolved"); ComplexityVerifier REJECTS because complexity did not
 * decrease. A rejection with that reason can only come from the complexity gate.
 */
class FixEngineComplexityRoutingTest : BasePlatformTestCase() {
    fun testComplexityIssueRoutesThroughComplexityVerifier() {
        val content = "fun f(a: Boolean) {\n    if (a) g()\n}\n"
        val vf = myFixture.configureByText("A.kt", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val start = text.indexOf("g()")
        val plan = FixPlan("c1", listOf(ReplaceRange(start, start + 3, "k()")))
        val target = Issue(
            id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
            title = "High complexity", description = "", filePath = vf.path, line = 1, ruleId = "AEG-CPX-001"
        )
        val engine = FixEngine(project, derivePlan = { _, _, _ -> plan })

        val result = runBlocking {
            engine.fixVerified(
                target, vf, text, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertTrue(
            (result as FixApplyResult.Rejected).reason,
            result.reason.contains("Complexity did not decrease")
        )
        assertEquals(content, runReadAction { myFixture.getDocument(myFixture.file).text })
    }

    fun testNonComplexityIssueStillUsesDefaultVerifier() {
        // A null-safety issue with the same rename plan: FixVerifier accepts (target resolved, empty re-analysis).
        val content = "fun f(a: Boolean) {\n    if (a) g()\n}\n"
        val vf = myFixture.configureByText("B.kt", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val start = text.indexOf("g()")
        val plan = FixPlan("n1", listOf(ReplaceRange(start, start + 3, "k()")))
        val target = Issue(
            id = "n1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Nullable", description = "", filePath = vf.path, line = 2, ruleId = "AEG-NULL-KT-001"
        )
        val engine = FixEngine(project, derivePlan = { _, _, _ -> plan })

        val result = runBlocking {
            engine.fixVerified(
                target, vf, text, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`testComplexityIssueRoutesThroughComplexityVerifier` fails: the CPX issue currently flows through the default `FixVerifier`, which accepts the rename — so the result is `Success`, not the expected complexity `Rejected`).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineComplexityRoutingTest"`

- [ ] **Step 3: Add the CPX acceptance selection to `FixEngine.kt`**

Add a companion constant and a private selector, and branch both verified entry points on it.

3a. Add the companion + selector (place after `fixSupervised`, inside the class):

```kotlin
    /**
     * For `AEG-CPX-001`, build the complexity-aware acceptance: judge candidates by [ComplexityVerifier]
     * (strict `estimateComplexity` decrease + no other-rule regression) instead of the default
     * [FixVerifier]. [functionCount] comes from the file content via [FunctionCounter] and is held
     * constant across original/candidate. Returns null for every other rule (use the default gate).
     */
    private fun complexityAcceptanceOrNull(
        issue: Issue,
        baselineForFile: List<Issue>,
        content: String,
    ): ((String, String, List<Issue>) -> VerifyDecision)? {
        if (issue.ruleId != COMPLEXITY_RULE_ID) return null
        val verifier = ComplexityVerifier(FunctionCounter.count(content))
        return { original, candidate, candidateIssues ->
            verifier.decide(issue, baselineForFile, original, candidate, candidateIssues)
        }
    }

    private companion object {
        const val COMPLEXITY_RULE_ID = "AEG-CPX-001"
    }
```

3b. In `fixVerified`, replace the single `return applicator.applyVerified(...)` with the branched form:

```kotlin
        val plan = planFor(issue, virtualFile, content)
            ?: return FixApplyResult.Rejected("No deterministic fix available for ${issue.ruleId}.")
        val acceptance = complexityAcceptanceOrNull(issue, baselineForFile, content)
        return if (acceptance != null) {
            applicator.applyVerified(
                plan, virtualFile, project, issue, baselineForFile, reanalyze,
                acceptance = acceptance, edtContext = edtContext,
            )
        } else {
            applicator.applyVerified(
                plan, virtualFile, project, issue, baselineForFile, reanalyze, edtContext = edtContext,
            )
        }
```

3c. In `fixSupervised`, replace the default `applyVerified` lambda parameter body so AI-supervised CPX plans are judged the same way:

```kotlin
        applyVerified: suspend (FixPlan) -> FixApplyResult = { plan ->
            val acceptance = complexityAcceptanceOrNull(issue, baselineForFile, content)
            if (acceptance != null) {
                applicator.applyVerified(
                    plan, virtualFile, project, issue, baselineForFile, reanalyze,
                    acceptance = acceptance, edtContext = edtContext,
                )
            } else {
                applicator.applyVerified(
                    plan, virtualFile, project, issue, baselineForFile, reanalyze, edtContext = edtContext,
                )
            }
        },
```

(`VerifyDecision`, `ComplexityVerifier`, `FunctionCounter` are all in package `com.ghostdebugger.fix.engine` — no new imports.)

- [ ] **Step 4: Run → PASS**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineComplexityRoutingTest" --tests "com.ghostdebugger.fix.engine.FixEngineTest" --tests "com.ghostdebugger.fix.engine.FixEngineSupervisedTest"`

Expected: all green. `FixEngineTest`/`FixEngineSupervisedTest` (non-CPX rules) are unchanged because for them `complexityAcceptanceOrNull` returns null and the call is identical to before.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineComplexityRoutingTest.kt
git commit -m "feat(fix-engine): route AEG-CPX-001 through ComplexityVerifier acceptance"
```

---

### Task 6: End-to-end deterministic simplification

**Files:**
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/ComplexitySimplifierIntegrationTest.kt`

- [ ] **Step 1: Write the e2e test**

The full wiring: registered `ComplexitySimplifierFixer.generatePlan` → `FixDeriver.derivePlan` → `FixEngine.planFor` → `fixVerified` with CPX-routed `ComplexityVerifier` acceptance → Accept + edit applied + recomputed complexity strictly lower. Single function (`functionCount = 1`) so collapsing the one boolean-return if strictly drops `estimateComplexity` (5 → 4). `reanalyze` is stubbed empty (AEG-CPX-001 is graph-shadowed in single-file re-analysis; the gate's complexity check runs on real content). PSI-only fixer → `BasePlatformTestCase` + `Dispatchers.Unconfined`.

`src/test/kotlin/com/ghostdebugger/fix/engine/ComplexitySimplifierIntegrationTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.graph.GraphBuilder
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ComplexitySimplifierIntegrationTest : BasePlatformTestCase() {
    fun testRegisteredFixerSimplifiesThroughTheEngineAndLowersComplexity() {
        val code = "fun process(a: Boolean, b: Boolean, items: List<Int>): Boolean {\n" +
            "    for (i in items) {\n" +
            "        if (i < 0) {\n" +
            "            println(i)\n" +
            "        }\n" +
            "    }\n" +
            "    if (a && b) {\n" +
            "        return true\n" +
            "    } else {\n" +
            "        return false\n" +
            "    }\n" +
            "}\n"
        val vf = myFixture.configureByText("A.kt", code).virtualFile
        val content = runReadAction { myFixture.getDocument(myFixture.file).text }
        val target = Issue(
            id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
            title = "High complexity: A.kt", description = "", filePath = vf.path, line = 1,
            ruleId = "AEG-CPX-001"
        )

        val before = GraphBuilder().estimateComplexity(content, 1)

        val result = runBlocking {
            FixEngine(project).fixVerified(
                target, vf, content, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
        val after = runReadAction { myFixture.getDocument(myFixture.file).text }
        assertTrue(after, after.contains("return a && b"))
        assertFalse(after, after.contains("if (a && b)"))
        // the recomputed metric strictly dropped (the acceptance gate's guarantee, re-asserted here)
        assertTrue(GraphBuilder().estimateComplexity(after, 1) < before)
    }
}
```

- [ ] **Step 2: Run → PASS**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.ComplexitySimplifierIntegrationTest"`

Expected: green. If it fails as `Rejected("Complexity did not decrease …")`, print `before`/`after` metrics — the file must keep `functionCount = 1` (exactly one `fun`) and the collapse must remove one counted decision point (`if`). If `Rejected` for a regression, ensure `reanalyze` returns empty.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/ghostdebugger/fix/engine/ComplexitySimplifierIntegrationTest.kt
git commit -m "test(fix-engine): e2e deterministic complexity simplification (collapse -> metric drop)"
```

---

## Final verification

- [ ] Targeted suites green:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test \
  --tests "com.ghostdebugger.fix.*" --tests "com.ghostdebugger.ai.PromptTemplatesPlanFixTest"
```

- [ ] Full suite green with real exit status:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test 2>&1 | tail -8; echo EXIT=${PIPESTATUS[0]}
```

Expected: `BUILD SUCCESSFUL`, `EXIT=0`. (If the pipe hides a failure, inspect `build/test-results/test/*.xml` for `<testcase>` entries with `<failure>`.)

## Self-Review (completed during planning)

**1. Spec coverage**
- §3.3 `CollapseBooleanReturn` op (KT PSI both branch orders + block bodies + negation; JS/TS regex; null on absent/ambiguous) → Task 1.
- §3.4 `ComplexitySimplifierFixer` (whole-file scan, one op per site, declines when none, PSI-only) → Task 4.
- §3.5 wiring (`AEG-CPX-001` → `ComplexityVerifier` path, supplying `functionCount`) → Tasks 3 (`FunctionCounter`) + 5 (`FixEngine` selection, both `fixVerified` and `fixSupervised`).
- §4 verification semantics (Tier-1 PSI-valid + strict complexity decrease + no regression) → reused from B1's `applyVerified`/`ComplexityVerifier`; exercised in Tasks 5 (reject-on-non-decrease routing) + 6 (accept-on-decrease e2e).
- §5 testing strategy: `estimateComplexity` collapse lowers score (Task 6); `ComplexityVerifier.decide` pure (already B1); `CollapseBooleanReturn` cases (Task 1); fixer emit/decline (Task 4); e2e (Task 6). Catalog/codec exposure of the new op (Task 2) makes it AI-composable, matching the sub-project A breadth contract.

**2. Placeholder scan** — none. Every code step shows complete file content or an exact insertion; every run step has the exact `--tests` command and expected outcome.

**3. Type consistency**
- `BooleanReturnCollapse.sites(ctx: FixContext): List<Int>` and `collapseOnLine(ctx, line): Collapse?` — used identically by the op (Task 1) and the fixer (Task 4).
- `Collapse(startOffset, endOffset, replacement)` → wrapped as `TextEdit(startOffset, endOffset, replacement)` (field names align with `TextEdit`).
- `CollapseBooleanReturn(val line: Int)` `@SerialName("collapseBooleanReturn")` — same discriminator in the op (Task 1), the catalog entry and codec test (Task 2), and the fixer's emitted ops (Task 4).
- `FunctionCounter.count(content): Int` (Task 3) — called by `FixEngine.complexityAcceptanceOrNull` (Task 5).
- `ComplexityVerifier(functionCount).decide(target, baselineForFile, originalContent, candidateContent, candidateForFile)` — invoked with exactly that arg order/shape by the acceptance lambda (Task 5), matching B1's signature `(original, candidate, candidateIssues)` seam in `applyVerified`.
- `FixerRegistry` count 7 → 8 (Task 4) matches `FixerRegistryTest` bump; catalog count 14 → 15 (Task 2) matches `FixOperationCatalogTest` bump.

**Design note (integer-division sensitivity):** `estimateComplexity = 1 + decisionPoints / functionCount`. A single collapse removes exactly one decision point (`else`/`true`/`false` aren't counted), so with `functionCount > 1` integer division can mask it — the gate then *correctly* declines a fix that didn't move the reported metric (consistent with the conservative-miss bias; multiple sites compose, per spec §7). The e2e (Task 6) uses a one-function file (`functionCount = 1`) so the single collapse strictly drops the score, proving the mechanism. `FunctionCounter` only needs stability (same count both sides), which it guarantees, not parity with the parser's `file.functions.size`.
