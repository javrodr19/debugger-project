# JS/TS Extract-Method Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend AI extract-method to `.ts`/`.js`: a regex+brace-matching per-function complexity measurer, a delimiter-balance substitute Tier-1, a measurer-injected `ExtractMethodVerifier`, and a `FixEngine` language dispatch — so a JS/TS `AEG-CPX-001` extraction is verified by the same per-function decomposition gate as Kotlin.

**Architecture:** `JsTsPerFunctionComplexity.measure(content)` finds `function`/const-arrow declarations over comment/string-masked content, brace-matches each body, and maps `name → estimateComplexity(body, 1)` (same `Result` type as the Kotlin measurer). `JsTsStructuralCheck.isBalanced(content)` is the substitute for the PSI parse-validity gate `.ts`/`.js` lack. `ExtractMethodVerifier` takes an injected `measure` (default = Kotlin), so the identical five-condition gate serves both languages. `FixEngine`'s `AEG-CPX-001` extraction branch dispatches on `issue.filePath`.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform, kotlinx-coroutines, JUnit4 + `BasePlatformTestCase`.

**Spec:** `docs/superpowers/specs/2026-06-06-jsts-extract-method-design.md`.

---

## Prerequisites (test prelude)

Tests need a JetBrains Runtime; the shell env does **not** persist between Bash calls. Prefix **every** gradlew invocation inline:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

A piped gradle run masks the exit code; append `; echo EXIT=${PIPESTATUS[0]}` and treat any `BUILD FAILED`/non-zero `EXIT` as failure regardless of tail output.

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/PerFunctionComplexity.kt` — Kotlin measurer; `data class Result(val byKey: Map<String,Int>, val collision: Boolean)`. JS/TS reuses this `Result` type.
- `src/main/kotlin/com/ghostdebugger/parser/TsJsRegexSymbolExtractor.kt:103` — `internal fun maskStringsAndComments(src): String` (blanks string/comment bodies, length+line preserving).
- `src/main/kotlin/com/ghostdebugger/graph/GraphBuilder.kt:121` — `internal fun estimateComplexity(content, functionCount): Int` (member; call `GraphBuilder().estimateComplexity(body, 1)` = `1 + decisionPointsInBody`).
- `src/main/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifier.kt:21` — `class ExtractMethodVerifier(private val project: Project, private val threshold: Int)`; `decide(...)` calls `PerFunctionComplexity.measure(project, originalContent)` / `(project, candidateContent)` at lines 36-37. Task 3 injects the measurer here.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt:138-153` — `complexityAcceptanceOrNull`; the extraction branch (`FunctionCounter.count(candidate) > count(original)` → `ExtractMethodVerifier(project, threshold)`) is what Task 4 extends with the language branch. `FixEngine` has `private val project: Project`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixOperation.kt` — `ReplaceLines(startLine, endLine, text)`, `InsertLinesAfter(afterLine, text)` (C1, content-based; used by the e2e plan).
- `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt:31` — `Issue.ruleKey()`. `Issue` has `filePath`.
- C2 reference tests to mirror: `src/test/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifierTest.kt`, `FixEngineExtractMethodDispatchTest.kt`, `ExtractMethodIntegrationTest.kt`.

## File structure

- **Create** `src/main/kotlin/com/ghostdebugger/fix/engine/JsTsPerFunctionComplexity.kt` — JS/TS per-function measurement.
- **Create** `src/main/kotlin/com/ghostdebugger/fix/engine/JsTsStructuralCheck.kt` — delimiter-balance substitute Tier-1.
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifier.kt` — inject the measurer (default-preserving).
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt` — language dispatch.
- Tests created/modified alongside each (paths per task).

---

### Task 1: `JsTsPerFunctionComplexity`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/JsTsPerFunctionComplexity.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/JsTsPerFunctionComplexityTest.kt`

- [ ] **Step 1: Write the failing test** (plain JUnit — no PSI/project needed)

`src/test/kotlin/com/ghostdebugger/fix/engine/JsTsPerFunctionComplexityTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsTsPerFunctionComplexityTest {
    @Test fun measuresFunctionAndConstArrowBodies() {
        // f: 2 if + 1 && = 3 -> 4 ; g (const arrow): 1 if -> 2
        val content = "function f(a, b) {\n    if (a) {}\n    if (b && a) {}\n}\n" +
            "const g = (c) => {\n    if (c) {}\n}\n"
        val r = JsTsPerFunctionComplexity.measure(content)
        assertFalse(r.collision)
        assertEquals(4, r.byKey["f"])
        assertEquals(2, r.byKey["g"])
    }

    @Test fun skipsExpressionBodyArrow() {
        val content = "const h = (x) => x + 1\n"
        val r = JsTsPerFunctionComplexity.measure(content)
        assertNull(r.byKey["h"])
    }

    @Test fun bracematchesNestedBracesInBody() {
        // object literal inside the body must not end the body early; only the `if` counts -> 2
        val content = "function obj(a) {\n    const x = { k: 1 }\n    if (a) {}\n}\n"
        val r = JsTsPerFunctionComplexity.measure(content)
        assertEquals(2, r.byKey["obj"])
    }

    @Test fun ignoresKeywordsAndBracesInStrings() {
        // `if` and `{` live inside a string -> masked -> body has no decision points -> 1
        val content = "function s() {\n    const t = \"if (x) {\"\n}\n"
        val r = JsTsPerFunctionComplexity.measure(content)
        assertEquals(1, r.byKey["s"])
    }

    @Test fun flagsDuplicateNameCollision() {
        val content = "function dup(a) { if (a) {} }\nfunction dup(b) { if (b) {} }\n"
        val r = JsTsPerFunctionComplexity.measure(content)
        assertTrue(r.collision)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`JsTsPerFunctionComplexity` unresolved).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.JsTsPerFunctionComplexityTest"`

- [ ] **Step 3: Implement `JsTsPerFunctionComplexity.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.graph.GraphBuilder
import com.ghostdebugger.parser.TsJsRegexSymbolExtractor

/**
 * Per-function complexity for `.ts`/`.js` content without a parser: find `function`/const-arrow
 * declarations over comment/string-masked content, balanced-brace-match each body, and map
 * `name -> GraphBuilder.estimateComplexity(body, 1)` (the same metric as Kotlin, single-sourced).
 * Returns the Kotlin measurer's [PerFunctionComplexity.Result] so [ExtractMethodVerifier] is reused.
 *
 * Best-effort by design (no JS grammar): name-only keys (JS has no overloading; a duplicate name sets
 * [PerFunctionComplexity.Result.collision] and the gate declines); expression-body arrows and any
 * function whose body fails to brace-balance are skipped (so an un-measurable source/target makes the
 * gate reject — conservative). Object-literal return types may mis-delimit and are also skipped/rejected.
 */
object JsTsPerFunctionComplexity {
    private val FUNCTION_DECL = Regex("""\bfunction\s+(\w+)\s*\(""")
    private val CONST_ARROW = Regex("""\b(?:const|let|var)\s+(\w+)[^=\n{(]*=\s*(?:async\s+)?\(""")

    fun measure(content: String): PerFunctionComplexity.Result {
        val masked = TsJsRegexSymbolExtractor().maskStringsAndComments(content)
        val graphBuilder = GraphBuilder()
        val map = HashMap<String, Int>()
        var collision = false

        // (name, index-of-parameter-'(', isArrow) for every declaration, over masked content.
        val decls = ArrayList<Triple<String, Int, Boolean>>()
        FUNCTION_DECL.findAll(masked).forEach { decls.add(Triple(it.groupValues[1], it.range.last, false)) }
        CONST_ARROW.findAll(masked).forEach { decls.add(Triple(it.groupValues[1], it.range.last, true)) }

        for ((name, parenOpen, isArrow) in decls) {
            val body = bodyRange(masked, parenOpen, isArrow) ?: continue
            val complexity = graphBuilder.estimateComplexity(content.substring(body.first, body.last + 1), 1)
            if (map.containsKey(name)) collision = true
            map[name] = complexity
        }
        return PerFunctionComplexity.Result(map, collision)
    }

    /** Body brace range [openBrace, closeBrace] for a decl whose parameter '(' is at [parenOpen], or null. */
    private fun bodyRange(masked: String, parenOpen: Int, isArrow: Boolean): IntRange? {
        val parenClose = matchDelimiter(masked, parenOpen, '(', ')') ?: return null
        var i = parenClose + 1
        if (isArrow) {
            val arrow = masked.indexOf("=>", i)
            if (arrow < 0) return null
            i = arrow + 2
            while (i < masked.length && masked[i].isWhitespace()) i++
            if (i >= masked.length || masked[i] != '{') return null   // expression-body arrow
            return matchDelimiter(masked, i, '{', '}')?.let { i..it }
        }
        // function declaration: skip whitespace, then an optional `: ReturnType` up to the body '{'
        while (i < masked.length && masked[i].isWhitespace()) i++
        if (i < masked.length && masked[i] == ':') {
            val brace = masked.indexOf('{', i)
            if (brace < 0) return null
            i = brace
        }
        if (i >= masked.length || masked[i] != '{') return null
        return matchDelimiter(masked, i, '{', '}')?.let { i..it }
    }

    /** Index of the closer matching the opener at [open] (balanced), or null if unbalanced before end. */
    private fun matchDelimiter(s: String, open: Int, openCh: Char, closeCh: Char): Int? {
        var depth = 0
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (c == openCh) depth++ else if (c == closeCh) { depth--; if (depth == 0) return i }
            i++
        }
        return null
    }
}
```

- [ ] **Step 4: Run → PASS** (5 tests).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.JsTsPerFunctionComplexityTest"`

Expected: all green. If `measuresFunctionAndConstArrowBodies` differs, log `r.byKey`: `f`'s body is `{ if(a){} if(b&&a){} }` (if×2 + &&×1 → 4); `g`'s body `{ if(c){} }` (→ 2). If `skipsExpressionBodyArrow` fails, the arrow `=>` is found but the next non-ws char is `x` (not `{`) so `bodyRange` must return null.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/JsTsPerFunctionComplexity.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/JsTsPerFunctionComplexityTest.kt
git commit -m "feat(fix-engine): JsTsPerFunctionComplexity (regex+brace-match per-function metric)"
```

---

### Task 2: `JsTsStructuralCheck`

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/JsTsStructuralCheck.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/JsTsStructuralCheckTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/fix/engine/JsTsStructuralCheckTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsTsStructuralCheckTest {
    @Test fun balancedContentIsBalanced() {
        assertTrue(JsTsStructuralCheck.isBalanced("function f() {\n    if (a) { g([1, 2]) }\n}\n"))
    }

    @Test fun droppedClosingBraceIsUnbalanced() {
        assertFalse(JsTsStructuralCheck.isBalanced("function f() {\n    if (a) { g()\n}\n"))
    }

    @Test fun extraCloserIsUnbalanced() {
        assertFalse(JsTsStructuralCheck.isBalanced("f())\n"))
    }

    @Test fun unbalancedBracketIsUnbalanced() {
        assertFalse(JsTsStructuralCheck.isBalanced("const x = [1, 2\n"))
    }

    @Test fun imbalanceInsideStringIsIgnored() {
        // the unmatched '(' and '{' live inside a string -> masked -> balanced
        assertTrue(JsTsStructuralCheck.isBalanced("const s = \"if (a) {\"\n"))
    }
}
```

- [ ] **Step 2: Run → FAIL** (`JsTsStructuralCheck` unresolved).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.JsTsStructuralCheckTest"`

- [ ] **Step 3: Implement `JsTsStructuralCheck.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.parser.TsJsRegexSymbolExtractor

/**
 * Delimiter-balance check for `.ts`/`.js` — the substitute Tier-1 for the PSI parse-validity gate that
 * IntelliJ Community lacks for JS/TS. Over comment/string-masked content, every `()`, `{}`, `[]` must be
 * balanced and never close below zero. Best-effort: it catches gross delimiter malformation (a dropped or
 * extra brace) an AI extraction could introduce; it does NOT validate JS grammar.
 */
object JsTsStructuralCheck {
    fun isBalanced(content: String): Boolean {
        val masked = TsJsRegexSymbolExtractor().maskStringsAndComments(content)
        var paren = 0
        var brace = 0
        var bracket = 0
        for (c in masked) {
            when (c) {
                '(' -> paren++
                ')' -> if (--paren < 0) return false
                '{' -> brace++
                '}' -> if (--brace < 0) return false
                '[' -> bracket++
                ']' -> if (--bracket < 0) return false
            }
        }
        return paren == 0 && brace == 0 && bracket == 0
    }
}
```

- [ ] **Step 4: Run → PASS** (5 tests).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.JsTsStructuralCheckTest"`

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/JsTsStructuralCheck.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/JsTsStructuralCheckTest.kt
git commit -m "feat(fix-engine): JsTsStructuralCheck (delimiter-balance substitute Tier-1)"
```

---

### Task 3: Inject the measurer into `ExtractMethodVerifier`

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifier.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifierJsTsTest.kt`

- [ ] **Step 1: Write the failing test** (verifier driven by the JS/TS measurer)

`src/test/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifierJsTsTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ExtractMethodVerifierJsTsTest : BasePlatformTestCase() {
    private fun cpx() = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = "A.ts", line = 1, ruleId = "AEG-CPX-001"
    )
    // f: 4 if -> complexity 5
    private val original = "function f(a, b, c, d) {\n    if (a) {}\n    if (b) {}\n    if (c) {}\n    if (d) {}\n}\n"
    // f: 2 if + call -> 3 ; g: 2 if -> 3
    private val candidateGood = "function f(a, b, c, d) {\n    if (a) {}\n    if (b) {}\n    g(c, d)\n}\n" +
        "function g(c, d) {\n    if (c) {}\n    if (d) {}\n}\n"

    private fun verifier(threshold: Int) =
        ExtractMethodVerifier(project, threshold, JsTsPerFunctionComplexity::measure)

    fun testAcceptsGenuineJsTsDecomposition() {
        val d = verifier(threshold = 2).decide(cpx(), listOf(cpx()), original, candidateGood, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Accept)
    }

    fun testRejectsWhenSourceNotOverThreshold() {
        val d = verifier(threshold = 10).decide(cpx(), listOf(cpx()), original, candidateGood, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Reject)
        assertTrue((d as VerifyDecision.Reject).reason, d.reason.contains("threshold"))
    }
}
```

- [ ] **Step 2: Run → FAIL** (the 3-arg `ExtractMethodVerifier(project, threshold, measure)` constructor does not exist yet).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.ExtractMethodVerifierJsTsTest"`

- [ ] **Step 3: Add the injected `measure` parameter**

In `ExtractMethodVerifier.kt`, change the class header (line 21) to add a third, default-valued parameter:

```kotlin
class ExtractMethodVerifier(
    private val project: Project,
    private val threshold: Int,
    private val measure: (String) -> PerFunctionComplexity.Result = { PerFunctionComplexity.measure(project, it) },
) {
```

Then replace the two measurement calls (currently lines ~36-37):

```kotlin
        val orig = PerFunctionComplexity.measure(project, originalContent)
        val candR = PerFunctionComplexity.measure(project, candidateContent)
```

with:

```kotlin
        val orig = measure(originalContent)
        val candR = measure(candidateContent)
```

Leave the rest of `decide(...)` unchanged.

- [ ] **Step 4: Run → PASS**, and confirm the Kotlin verifier tests still pass via the default:

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.ExtractMethodVerifierJsTsTest" --tests "com.ghostdebugger.fix.engine.ExtractMethodVerifierTest"`

Expected: all green. `ExtractMethodVerifierTest` (Kotlin, C2) constructs `ExtractMethodVerifier(project, threshold)` and now uses the default Kotlin measurer — behavior identical.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifier.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifierJsTsTest.kt
git commit -m "feat(fix-engine): ExtractMethodVerifier accepts an injected per-function measurer"
```

---

### Task 4: `FixEngine` language dispatch

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineJsTsDispatchTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineJsTsDispatchTest.kt`:

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

class FixEngineJsTsDispatchTest : BasePlatformTestCase() {
    private fun cpxTarget(path: String) = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = path, line = 1, ruleId = "AEG-CPX-001"
    )

    fun testTsExtractionRoutesToJsTsGate() {
        // A small .ts function (complexity 2) is under the default threshold (10). The extraction adds a
        // function, so the JS/TS gate runs and rejects with "threshold" — proving the JS/TS measurer ran
        // (the Kotlin measurer would parse .ts as Kotlin -> empty map -> a different "add exactly one"/"no
        // source" reason).
        val content = "function f(a) {\n    if (a) g0()\n}\n"
        val vf = myFixture.configureByText("A.ts", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val plan = FixPlan("c1", listOf(
            ReplaceLines(2, 2, "    h(a)"),
            InsertLinesAfter(3, "function h(a) {\n    if (a) g0()\n}")
        ))
        val target = cpxTarget(vf.path)
        val engine = FixEngine(project, derivePlan = { _, _, _ -> plan })

        val result = runBlocking {
            engine.fixVerified(
                target, vf, text, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertTrue((result as FixApplyResult.Rejected).reason, result.reason.contains("threshold"))
    }

    fun testTsCandidateWithUnbalancedDelimitersIsRejected() {
        // The plan inserts an unbalanced `function h() {` (no closing brace) -> balance check rejects.
        val content = "function f(a) {\n    if (a) g0()\n}\n"
        val vf = myFixture.configureByText("B.ts", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val plan = FixPlan("c1", listOf(InsertLinesAfter(3, "function h() {")))
        val target = cpxTarget(vf.path)
        val engine = FixEngine(project, derivePlan = { _, _, _ -> plan })

        val result = runBlocking {
            engine.fixVerified(
                target, vf, text, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertTrue((result as FixApplyResult.Rejected).reason, result.reason.contains("unbalanced"))
    }
}
```

- [ ] **Step 2: Run → FAIL** (`testTsExtractionRoutesToJsTsGate`: today the extraction branch always uses the Kotlin measurer, so a `.ts` extraction rejects with `"Extraction must add exactly one function (added 0)"` / `"No source function got simpler."`, not `"…threshold…"`; `testTsCandidateWithUnbalancedDelimitersIsRejected`: there is no balance check yet, so the candidate reaches the Kotlin gate and rejects for a different reason — not `"unbalanced"`).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineJsTsDispatchTest"`

- [ ] **Step 3: Add the language branch in `complexityAcceptanceOrNull`**

In `FixEngine.kt`, replace the extraction branch of the returned lambda. The current body (lines ~144-152) is:

```kotlin
        return { original, candidate, candidateIssues ->
            if (FunctionCounter.count(candidate) > FunctionCounter.count(original)) {
                ExtractMethodVerifier(project, threshold)
                    .decide(issue, baselineForFile, original, candidate, candidateIssues)
            } else {
                ComplexityVerifier(FunctionCounter.count(original))
                    .decide(issue, baselineForFile, original, candidate, candidateIssues)
            }
        }
```

Replace it with:

```kotlin
        return { original, candidate, candidateIssues ->
            if (FunctionCounter.count(candidate) > FunctionCounter.count(original)) {
                val path = issue.filePath
                if (path.endsWith(".ts") || path.endsWith(".js")) {
                    if (!JsTsStructuralCheck.isBalanced(candidate)) {
                        VerifyDecision.Reject("Extraction left unbalanced delimiters.")
                    } else {
                        ExtractMethodVerifier(project, threshold, JsTsPerFunctionComplexity::measure)
                            .decide(issue, baselineForFile, original, candidate, candidateIssues)
                    }
                } else {
                    ExtractMethodVerifier(project, threshold)
                        .decide(issue, baselineForFile, original, candidate, candidateIssues)
                }
            } else {
                ComplexityVerifier(FunctionCounter.count(original))
                    .decide(issue, baselineForFile, original, candidate, candidateIssues)
            }
        }
```

(`JsTsStructuralCheck` and `JsTsPerFunctionComplexity` are same-package — no imports needed.)

- [ ] **Step 4: Run → PASS**, plus the C2 Kotlin dispatch test still green:

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineJsTsDispatchTest" --tests "com.ghostdebugger.fix.engine.FixEngineExtractMethodDispatchTest"`

Expected: all green. `FixEngineExtractMethodDispatchTest` (C2, `.kt` files) still hits the `else` (Kotlin) branch unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineJsTsDispatchTest.kt
git commit -m "feat(fix-engine): route .ts/.js extractions through the JS/TS gate + balance check"
```

---

### Task 5: End-to-end JS/TS extract-method

**Files:**
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/JsTsExtractMethodIntegrationTest.kt`

- [ ] **Step 1: Write the e2e test**

The full path: a hand-authored `.ts` extraction plan (simulating the AI) → `FixEngine.fixVerified` → the language dispatch (balance check + JS/TS measurer) → Accept + new function present + the source's per-function complexity strictly lower. Threshold lowered to 2 (restored in `finally`); `reanalyze` stubbed empty.

`src/test/kotlin/com/ghostdebugger/fix/engine/JsTsExtractMethodIntegrationTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class JsTsExtractMethodIntegrationTest : BasePlatformTestCase() {
    fun testTsExtractionAppliesAndIsAcceptedAndDecomposesTheSource() {
        val settings = GhostDebuggerSettings.getInstance()
        val originalThreshold = settings.snapshot().maxComplexity
        settings.update { maxComplexity = 2 }
        try {
            // process has three `if`s -> complexity 4 (> threshold 2)
            val code = "function process(a, b, c) {\n" +
                "    if (a) {\n        log(\"a\")\n    }\n" +
                "    if (b) {\n        log(\"b\")\n    }\n" +
                "    if (c) {\n        log(\"c\")\n    }\n" +
                "}\n"
            val vf = myFixture.configureByText("A.ts", code).virtualFile
            val content = runReadAction { myFixture.getDocument(myFixture.file).text }
            val target = Issue(
                id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
                title = "High complexity: A.ts", description = "", filePath = vf.path, line = 1,
                ruleId = "AEG-CPX-001"
            )
            // extract the `if (c) { … }` block (lines 8-10) into handleC, after process's `}` (line 11)
            val plan = FixPlan("c1", listOf(
                ReplaceLines(8, 10, "    handleC(c)"),
                InsertLinesAfter(11, "function handleC(c) {\n    if (c) {\n        log(\"c\")\n    }\n}")
            ))

            val before = JsTsPerFunctionComplexity.measure(content).byKey.getValue("process")

            val result = runBlocking {
                FixEngine(project, derivePlan = { _, _, _ -> plan }).fixVerified(
                    target, vf, content, baselineForFile = listOf(target),
                    reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
                )
            }

            assertTrue(result.toString(), result is FixApplyResult.Success)
            val after = runReadAction { myFixture.getDocument(myFixture.file).text }
            assertTrue(after, after.contains("handleC(c)"))
            assertTrue(after, after.contains("function handleC(c)"))
            val afterSource = JsTsPerFunctionComplexity.measure(after).byKey.getValue("process")
            assertTrue("source complexity should drop ($before -> $afterSource)", afterSource < before)
        } finally {
            settings.update { maxComplexity = originalThreshold }
        }
    }
}
```

- [ ] **Step 2: Run → PASS**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.JsTsExtractMethodIntegrationTest"`

Expected: green. If `Rejected("…threshold…")`, confirm the threshold was lowered and `process` measured at 4 (three `if`s). If `Rejected("…unbalanced…")`, the candidate text isn't delimiter-balanced — re-check the `InsertLinesAfter` text. If `Rejected("…add exactly one function…")`, confirm `function handleC` is detected (begins with `function ` + name).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/ghostdebugger/fix/engine/JsTsExtractMethodIntegrationTest.kt
git commit -m "test(fix-engine): e2e JS/TS extract-method (apply -> decomposition accepted)"
```

---

## Final verification

- [ ] Targeted suites green:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.*"
```

- [ ] Full suite green with real exit status:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test 2>&1 | tail -8; echo EXIT=${PIPESTATUS[0]}
```

Expected: `BUILD SUCCESSFUL`, `EXIT=0`. (If the pipe hides a failure, inspect `build/test-results/test/*.xml` for `<failure>`.)

## Self-Review (completed during planning)

**1. Spec coverage**
- §3.1 `JsTsPerFunctionComplexity` (regex find + brace-match body + `estimateComplexity(body,1)`; name keys; skip expression-arrow/brace-fail; collision flag) → Task 1.
- §3.2 `JsTsStructuralCheck.isBalanced` (masked `()`/`{}`/`[]` balance) → Task 2.
- §3.3 `ExtractMethodVerifier` measurer injection (default = Kotlin, gate unchanged) → Task 3.
- §3.4 `FixEngine` dispatch on `issue.filePath` (`.ts`/`.js` → balance-check-then-JS/TS-gate; `.kt`/other → Kotlin; in-place → `ComplexityVerifier`) → Task 4.
- §3.5 ops/prompt unchanged — confirmed (no task needed; the e2e uses the existing `ReplaceLines`/`InsertLinesAfter`).
- §4 verification + §5 testing → Tasks 1-5 (unit, dispatch both branches, e2e). §7 risks are accepted (no task).

**2. Placeholder scan** — none. Every code step shows complete file content or an exact edit; every run step has the exact `--tests` command + expected outcome.

**3. Type consistency**
- `JsTsPerFunctionComplexity.measure(content): PerFunctionComplexity.Result` (name keys) — matches the injected `measure: (String) -> PerFunctionComplexity.Result` in `ExtractMethodVerifier` (Task 3) and the `JsTsPerFunctionComplexity::measure` reference in the dispatch (Task 4) and tests.
- `ExtractMethodVerifier(project, threshold, measure = …)` — the 2-arg call sites (C2 Kotlin) keep working via the default; the 3-arg call is used for JS/TS.
- `JsTsStructuralCheck.isBalanced(content): Boolean` — used in the dispatch (Task 4) and tested (Task 2).
- Dispatch branch keys off `issue.filePath` suffix; in-place branch unchanged (`ComplexityVerifier(FunctionCounter.count(original))`).

**Traced fixtures:**
- Task 1 `f` body `{ if(a){} if(b&&a){} }` → if×2+&&×1 → 4; `g` arrow body `{ if(c){} }` → 2; `obj` body with `{ k: 1 }` → only the `if` counts → 2; expression-arrow `h` → no `{` after `=>` → skipped. ✓
- Task 3/5 `.ts` `function f(a,b,c,d)` 4 `if` → 5; after extracting two `if`s into `g`: `f`→3, `g`→3, threshold 2 → accept. e2e `process` 3 `if` → 4 → after extracting one `if` into `handleC`: `process`→3, `handleC`→2 → accept; 4→3 strict drop. ✓
- Task 4 `testTsExtractionRoutesToJsTsGate`: `f` (1 `if` → 2) under threshold 10 → JS/TS gate rejects "…threshold…" (Kotlin measurer would have said "added 0"/"no source"); unbalanced-insert candidate → balance check rejects "unbalanced". ✓
