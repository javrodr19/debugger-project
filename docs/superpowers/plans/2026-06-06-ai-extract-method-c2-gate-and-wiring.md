# AI Extract-Method C2 — Gate + Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the AI extract-method capability end-to-end: a per-function `ExtractMethodVerifier` gate, the `FixEngine` auto-dispatch that routes an `AEG-CPX-001` candidate which *added a function* to that gate (and an in-place candidate to B2's `ComplexityVerifier`), the planner-prompt section teaching extraction, and an e2e proving a real extraction applies + is accepted + measurably decomposes the source.

**Architecture:** `ExtractMethodVerifier(project, threshold)` compares `PerFunctionComplexity` (C1) maps of original vs candidate and accepts iff PSI-valid, no other-rule regression, exactly one new function, a source function got strictly simpler, that source was over threshold, and the new function is strictly simpler than the original source. `FixEngine.complexityAcceptanceOrNull` is generalized from a single verifier into a dispatch on `FunctionCounter.count(candidate) > count(original)`. `PromptTemplates.planFix` gains a conditional `AEG-CPX-001` section. The deterministic `CollapseBooleanReturn` path (B2) is untouched; both simplifiers flow through the existing `fixSupervised` lifecycle.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform PSI, kotlinx-coroutines, JUnit4 + `BasePlatformTestCase`.

**Spec:** `docs/superpowers/specs/2026-06-05-ai-extract-method-design.md` (§3.3 verifier, §3.4 dispatch, §3.5 prompt, §4 verification, §5 testing, phase C2).

---

## Prerequisites (test prelude)

Tests need a JetBrains Runtime; the shell env does **not** persist between Bash calls. Prefix **every** gradlew invocation inline:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

A piped gradle run masks the exit code; append `; echo EXIT=${PIPESTATUS[0]}` and treat any `BUILD FAILED`/non-zero `EXIT` as failure regardless of tail output.

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/PerFunctionComplexity.kt` (C1, merged) — `measure(project, content): Result` where `Result(byKey: Map<String,Int>, collision: Boolean)`; keys are `"name/arity"`, values `1 + decisionPointsInBody`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/ComplexityVerifier.kt` (B1) — `ComplexityVerifier(functionCount).decide(target, baselineForFile, originalContent, candidateContent, candidateForFile): VerifyDecision`; the no-regression count check to mirror.
- `src/main/kotlin/com/ghostdebugger/fix/engine/VerifyDecision.kt` — `sealed interface VerifyDecision { object Accept; data class Reject(val reason: String) }`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FunctionCounter.kt` (B2) — `count(content): Int`.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt:136-150` — the **current** `complexityAcceptanceOrNull` (single `ComplexityVerifier` path) + the `private companion object { const val COMPLEXITY_RULE_ID = "AEG-CPX-001" }`. `FixEngine` has `private val project: Project` (constructor). Task 2 generalizes this method.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt:132-146` — `applyVerified(..., acceptance: (originalContent, candidateContent, candidateIssues) -> VerifyDecision = <default>, edtContext)`. The dispatch is passed as `acceptance`.
- `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt:31` — `Issue.ruleKey(): String = ruleId ?: type.name`.
- `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt` — `getInstance().snapshot().maxComplexity` (read; default 10); `getInstance().update { maxComplexity = N }` (mutate, for the e2e). `ComplexityAnalyzer` reads the threshold the same way.
- `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt:166-190` — `planFix(issue, fileContent, feedback)`; the ops are rendered from `FixOperationCatalog.entries`. Task 3 inserts a conditional `AEG-CPX-001` section.
- `src/test/kotlin/com/ghostdebugger/ai/PromptTemplatesPlanFixTest.kt` — extend in Task 3.
- B2 reference tests to mirror: `src/test/kotlin/com/ghostdebugger/fix/engine/ComplexityVerifierTest.kt` (pure verifier), `FixEngineComplexityRoutingTest.kt` (routing via injected `derivePlan`), `ApplyVerifiedComplexityTest.kt` / `ComplexitySimplifierIntegrationTest.kt` (e2e through `fixVerified`).

## File structure

- **Create** `src/main/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifier.kt` — the per-function gate (one responsibility).
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt` — generalize `complexityAcceptanceOrNull` into the dispatch.
- **Modify** `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt` — the conditional extract-method prompt section.
- Tests created/extended alongside each (paths per task).

---

### Task 1: `ExtractMethodVerifier` (per-function gate)

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifier.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifierTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifierTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ExtractMethodVerifierTest : BasePlatformTestCase() {
    private fun cpx() = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CPX-001"
    )
    // original: one function f with 4 `if` -> complexity 5
    private val original = "fun f(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {\n" +
        "    if (a) {}\n    if (b) {}\n    if (c) {}\n    if (d) {}\n}\n"
    // candidate: f keeps 2 ifs + call (complexity 3); new g has 2 ifs (complexity 3)
    private val candidateGood = "fun f(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {\n" +
        "    if (a) {}\n    if (b) {}\n    g(c, d)\n}\n\n" +
        "fun g(c: Boolean, d: Boolean) {\n    if (c) {}\n    if (d) {}\n}\n"

    private fun verifier(threshold: Int) = ExtractMethodVerifier(project, threshold)

    fun testAcceptsGenuineDecomposition() {
        val d = verifier(threshold = 2).decide(cpx(), listOf(cpx()), original, candidateGood, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Accept)
    }

    fun testRejectsWhenNoFunctionAdded() {
        // candidate just renames a call — same function set, f unchanged
        val candidate = original.replace("if (d) {}", "if (e) {}")
        val d = verifier(threshold = 2).decide(cpx(), listOf(cpx()), original, candidate, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Reject)
    }

    fun testRejectsWhenSourceNotOverThreshold() {
        // genuine decomposition, but threshold 10 > source's original complexity 5
        val d = verifier(threshold = 10).decide(cpx(), listOf(cpx()), original, candidateGood, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Reject)
        assertTrue((d as VerifyDecision.Reject).reason, d.reason.contains("threshold"))
    }

    fun testRejectsWhenExtractedFunctionNotSimplerThanSource() {
        // f drops 5 -> 3, but g has 4 ifs (complexity 5) = original source complexity -> not simpler
        val candidate = "fun f(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {\n" +
            "    if (a) {}\n    if (b) {}\n    g(c, d)\n}\n\n" +
            "fun g(c: Boolean, d: Boolean) {\n    if (c) {}\n    if (d) {}\n    if (c) {}\n    if (d) {}\n}\n"
        val d = verifier(threshold = 2).decide(cpx(), listOf(cpx()), original, candidate, emptyList())
        assertTrue(d.toString(), d is VerifyDecision.Reject)
    }

    fun testRejectsOnRegression() {
        // genuine decomposition, but candidate analysis surfaces a new other-rule issue
        val newIssue = Issue(
            id = "n", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "x", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-NULL-001"
        )
        val d = verifier(threshold = 2).decide(cpx(), listOf(cpx()), original, candidateGood, listOf(newIssue))
        assertTrue(d.toString(), d is VerifyDecision.Reject)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`ExtractMethodVerifier` unresolved).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.ExtractMethodVerifierTest"`

- [ ] **Step 3: Implement `ExtractMethodVerifier.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.intellij.openapi.project.Project

/**
 * Per-function acceptance for AI extract-method simplifications (`AEG-CPX-001` when the candidate added
 * a function). Unlike [ComplexityVerifier] (file-average), this judges a genuine *decomposition*:
 * extracting a cohesive block from a genuinely-complex function into a new, simpler one — not gaming
 * the per-file average by adding any function. Accepts iff:
 *  - no *other* rule's per-ruleKey count increased vs baseline (the target's own rule ignored), AND
 *  - exactly one new function appeared (by `name/arity`), AND
 *  - some shared-name function got strictly simpler (the source), AND
 *  - that source's original complexity was over [threshold] (a genuine extraction target), AND
 *  - the new function is strictly simpler than the original source (genuine decomposition).
 *
 * Complexity is measured per-function by [PerFunctionComplexity] (parses both sides; no Analysis API).
 * Ambiguous overloads (same name/arity) are declined conservatively. Tier-1 PSI-validity runs first in
 * [FixPlanApplicator] and reverts a non-parsing candidate before this gate is consulted.
 */
class ExtractMethodVerifier(private val project: Project, private val threshold: Int) {
    fun decide(
        target: Issue,
        baselineForFile: List<Issue>,
        originalContent: String,
        candidateContent: String,
        candidateForFile: List<Issue>,
    ): VerifyDecision {
        val targetKey = target.ruleKey()
        val base = baselineForFile.filterNot { it.ruleKey() == targetKey }.groupingBy { it.ruleKey() }.eachCount()
        val cand = candidateForFile.filterNot { it.ruleKey() == targetKey }.groupingBy { it.ruleKey() }.eachCount()
        for ((key, count) in cand) {
            if (count > (base[key] ?: 0)) return VerifyDecision.Reject("Extraction introduces new \"$key\" issue(s).")
        }

        val orig = PerFunctionComplexity.measure(project, originalContent)
        val candR = PerFunctionComplexity.measure(project, candidateContent)
        if (orig.collision || candR.collision) {
            return VerifyDecision.Reject("Ambiguous function names (overloads); cannot verify extraction.")
        }

        val newKeys = candR.byKey.keys - orig.byKey.keys
        if (newKeys.size != 1) {
            return VerifyDecision.Reject("Extraction must add exactly one function (added ${newKeys.size}).")
        }
        val extractedComplexity = candR.byKey.getValue(newKeys.first())

        val source = (orig.byKey.keys intersect candR.byKey.keys)
            .filter { candR.byKey.getValue(it) < orig.byKey.getValue(it) }
            .maxByOrNull { orig.byKey.getValue(it) - candR.byKey.getValue(it) }
            ?: return VerifyDecision.Reject("No source function got simpler.")
        val sourceOriginal = orig.byKey.getValue(source)

        if (sourceOriginal <= threshold) {
            return VerifyDecision.Reject("Source function complexity ($sourceOriginal) is not over the threshold ($threshold).")
        }
        if (extractedComplexity >= sourceOriginal) {
            return VerifyDecision.Reject("Extracted function ($extractedComplexity) is not simpler than the original source ($sourceOriginal).")
        }
        return VerifyDecision.Accept
    }
}
```

- [ ] **Step 4: Run → PASS** (5 tests).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.ExtractMethodVerifierTest"`

Expected: all green. If `testAcceptsGenuineDecomposition` rejects, log both `PerFunctionComplexity.measure` maps — confirm `original` has `f/4 = 5` and `candidateGood` has `f/4 = 3`, `g/2 = 3` (the decision counts: `f` original 4 ifs, candidate 2 ifs; `g` 2 ifs).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifier.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/ExtractMethodVerifierTest.kt
git commit -m "feat(fix-engine): ExtractMethodVerifier (per-function decomposition gate)"
```

---

### Task 2: `FixEngine` auto-dispatch for `AEG-CPX-001`

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineExtractMethodDispatchTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineExtractMethodDispatchTest.kt`:

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
 * The AEG-CPX-001 acceptance dispatches on whether the candidate added a function: a candidate that
 * adds one is judged by ExtractMethodVerifier; an in-place candidate by B2's ComplexityVerifier.
 */
class FixEngineExtractMethodDispatchTest : BasePlatformTestCase() {
    private fun cpxTarget(path: String) = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = path, line = 1, ruleId = "AEG-CPX-001"
    )

    fun testCandidateThatAddsAFunctionRoutesToExtractMethodGate() {
        // A small function (complexity 2) is under the default threshold (10). An extraction plan adds
        // a function, so the dispatch picks ExtractMethodVerifier, which rejects with its distinctive
        // "not over the threshold" reason — a verdict ComplexityVerifier (file-average) would never give.
        val content = "fun f(a: Boolean) {\n    if (a) g0()\n}\n"
        val vf = myFixture.configureByText("A.kt", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val plan = FixPlan("c1", listOf(
            ReplaceLines(2, 2, "    h()"),
            InsertLinesAfter(3, "fun h() {\n    if (a) g0()\n}")
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

    fun testInPlaceCandidateStillUsesComplexityVerifier() {
        // No function added + complexity unchanged -> ComplexityVerifier rejects "did not decrease".
        val content = "fun f(a: Boolean) {\n    if (a) g0()\n}\n"
        val vf = myFixture.configureByText("B.kt", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val start = text.indexOf("g0()")
        val plan = FixPlan("c1", listOf(ReplaceRange(start, start + 4, "k0()")))  // rename, complexity unchanged
        val target = cpxTarget(vf.path)
        val engine = FixEngine(project, derivePlan = { _, _, _ -> plan })

        val result = runBlocking {
            engine.fixVerified(
                target, vf, text, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertTrue((result as FixApplyResult.Rejected).reason, result.reason.contains("Complexity did not decrease"))
    }
}
```

- [ ] **Step 2: Run → FAIL** (`testCandidateThatAddsAFunctionRoutesToExtractMethodGate` fails: today every `AEG-CPX-001` candidate goes to `ComplexityVerifier`, which would *accept* the extraction (file-average dropped) → `Success`, not the expected extract-gate `Rejected("…threshold…")`).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineExtractMethodDispatchTest"`

- [ ] **Step 3: Generalize `complexityAcceptanceOrNull` in `FixEngine.kt`**

Replace the current method body (lines ~136-146) with the dispatch. The new method:

```kotlin
    /**
     * Acceptance for `AEG-CPX-001`, dispatching on what the candidate did:
     *  - it **added a function** (extract-method) -> [ExtractMethodVerifier] (per-function decomposition), OR
     *  - **in-place** (e.g. B2's CollapseBooleanReturn) -> [ComplexityVerifier] (file-average decrease).
     * Returns null for every other rule (use the default [FixVerifier] gate).
     */
    private fun complexityAcceptanceOrNull(
        issue: Issue,
        baselineForFile: List<Issue>,
        content: String,
    ): ((String, String, List<Issue>) -> VerifyDecision)? {
        if (issue.ruleId != COMPLEXITY_RULE_ID) return null
        val threshold = GhostDebuggerSettings.getInstance().snapshot().maxComplexity
        return { original, candidate, candidateIssues ->
            if (FunctionCounter.count(candidate) > FunctionCounter.count(original)) {
                ExtractMethodVerifier(project, threshold)
                    .decide(issue, baselineForFile, original, candidate, candidateIssues)
            } else {
                ComplexityVerifier(FunctionCounter.count(original))
                    .decide(issue, baselineForFile, original, candidate, candidateIssues)
            }
        }
    }
```

Add the import at the top of `FixEngine.kt` (with the other imports):
```kotlin
import com.ghostdebugger.settings.GhostDebuggerSettings
```
Leave the `private companion object { const val COMPLEXITY_RULE_ID = "AEG-CPX-001" }` unchanged.

- [ ] **Step 4: Run → PASS** (2 tests), plus the B2 routing test still green:

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineExtractMethodDispatchTest" --tests "com.ghostdebugger.fix.engine.FixEngineComplexityRoutingTest"`

Expected: all green. `FixEngineComplexityRoutingTest` (B2) still passes because its in-place rename plan does not add a function → the dispatch's `else` branch is exactly the old `ComplexityVerifier(FunctionCounter.count(original))` call.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineExtractMethodDispatchTest.kt
git commit -m "feat(fix-engine): dispatch AEG-CPX-001 to ExtractMethodVerifier when a function is added"
```

---

### Task 3: `planFix` extract-method prompt section

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt`
- Test: `src/test/kotlin/com/ghostdebugger/ai/PromptTemplatesPlanFixTest.kt`

- [ ] **Step 1: Add the failing test cases**

Append to `PromptTemplatesPlanFixTest` (it already has an `issue()` helper that builds an issue; build a CPX issue inline):

```kotlin
    @Test fun complexityIssueGetsExtractMethodGuidance() {
        val cpx = com.ghostdebugger.model.Issue(
            id = "c1", type = com.ghostdebugger.model.IssueType.HIGH_COMPLEXITY,
            severity = com.ghostdebugger.model.IssueSeverity.WARNING,
            title = "High complexity", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CPX-001"
        )
        val p = PromptTemplates.planFix(cpx, "fun f() {}", feedback = null)
        assertTrue(p.contains("extract"))
        assertTrue(p.contains("replaceLines"))
        assertTrue(p.contains("insertLinesAfter"))
    }

    @Test fun nonComplexityIssueHasNoExtractMethodGuidance() {
        val p = PromptTemplates.planFix(issue(), "x", feedback = null)
        assertFalse(p.contains("most complex function"))
    }
```

(If `PromptTemplatesPlanFixTest` lacks `import org.junit.Assert.assertFalse`, add it.)

- [ ] **Step 2: Run → FAIL** (`complexityIssueGetsExtractMethodGuidance` fails — no extract guidance yet).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.ai.PromptTemplatesPlanFixTest"`

- [ ] **Step 3: Add the conditional section in `planFix`**

In `PromptTemplates.planFix`, immediately AFTER the feedback block and BEFORE the `sb.append("\nReturn ONLY a JSON object ...")` line, insert:

```kotlin
        if ((issue.ruleId ?: issue.type.name) == "AEG-CPX-001") {
            sb.append("\nThis is a high-complexity issue. Prefer EXTRACT-METHOD: find the most complex function, ")
            sb.append("move a cohesive block of its branching logic into a new, well-named function, and replace ")
            sb.append("the block with a call. Compose a `replaceLines` (swap the block for the call) plus an ")
            sb.append("`insertLinesAfter` (define the new function after the source's closing brace). Both the ")
            sb.append("shrunken function and the new function must end up simpler than the original.\n")
        }
```

(Use the exact `sb` builder already in `planFix`; this matches its `sb.append(...)` style and avoids the `trimIndent()` interpolation gotcha.)

- [ ] **Step 4: Run → PASS**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.ai.PromptTemplatesPlanFixTest"`

Expected: all green (the new cases pass; the existing op-catalog assertions are unaffected — the section is additive, and `replaceLines`/`insertLinesAfter` already render from the catalog).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt \
        src/test/kotlin/com/ghostdebugger/ai/PromptTemplatesPlanFixTest.kt
git commit -m "feat(fix-engine): planFix teaches extract-method for high-complexity issues"
```

---

### Task 4: End-to-end extract-method simplification

**Files:**
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/ExtractMethodIntegrationTest.kt`

- [ ] **Step 1: Write the e2e test**

The full path: a hand-authored extraction plan (simulating the AI) → `FixEngine.fixVerified` → the §3.4 dispatch sees a function was added → `ExtractMethodVerifier` (threshold lowered to 2 for the test, restored after) → Accept + both functions present + the source's per-function complexity strictly lower. `reanalyze` stubbed empty (the gate runs on real content). PSI-only → `BasePlatformTestCase` + `Dispatchers.Unconfined`.

`src/test/kotlin/com/ghostdebugger/fix/engine/ExtractMethodIntegrationTest.kt`:

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

class ExtractMethodIntegrationTest : BasePlatformTestCase() {
    fun testExtractionAppliesAndIsAcceptedAndDecomposesTheSource() {
        val settings = GhostDebuggerSettings.getInstance()
        val originalThreshold = settings.snapshot().maxComplexity
        settings.update { maxComplexity = 2 }
        try {
            // process/3 has three `if`s -> complexity 4 (> threshold 2)
            val code = "fun process(a: Boolean, b: Boolean, c: Boolean) {\n" +
                "    if (a) {\n        println(\"a\")\n    }\n" +
                "    if (b) {\n        println(\"b\")\n    }\n" +
                "    if (c) {\n        println(\"c\")\n    }\n" +
                "}\n"
            val vf = myFixture.configureByText("A.kt", code).virtualFile
            val content = runReadAction { myFixture.getDocument(myFixture.file).text }
            val target = Issue(
                id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
                title = "High complexity: A.kt", description = "", filePath = vf.path, line = 1,
                ruleId = "AEG-CPX-001"
            )
            // extract the `if (c) { … }` block (lines 8-10) into handleC, after process's `}` (line 11)
            val plan = FixPlan("c1", listOf(
                ReplaceLines(8, 10, "    handleC(c)"),
                InsertLinesAfter(11, "fun handleC(c: Boolean) {\n    if (c) {\n        println(\"c\")\n    }\n}")
            ))

            val before = PerFunctionComplexity.measure(project, content).byKey.getValue("process/3")

            val result = runBlocking {
                FixEngine(project, derivePlan = { _, _, _ -> plan }).fixVerified(
                    target, vf, content, baselineForFile = listOf(target),
                    reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
                )
            }

            assertTrue(result.toString(), result is FixApplyResult.Success)
            val after = runReadAction { myFixture.getDocument(myFixture.file).text }
            assertTrue(after, after.contains("handleC(c)"))
            assertTrue(after, after.contains("fun handleC(c: Boolean)"))
            val afterSource = PerFunctionComplexity.measure(project, after).byKey.getValue("process/3")
            assertTrue("source complexity should drop ($before -> $afterSource)", afterSource < before)
        } finally {
            settings.update { maxComplexity = originalThreshold }
        }
    }
}
```

- [ ] **Step 2: Run → PASS**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.ExtractMethodIntegrationTest"`

Expected: green. If `Rejected("…not over the threshold…")`, the test forgot to lower the threshold (or the source isn't `process/3`). If `Rejected("Extraction must add exactly one function…")`, confirm the `InsertLinesAfter` text begins `fun handleC` (a new `name/arity` key not in the original). If `Rejected("…new issue(s)…")`, ensure `reanalyze` returns empty.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/ghostdebugger/fix/engine/ExtractMethodIntegrationTest.kt
git commit -m "test(fix-engine): e2e AI extract-method (apply -> per-function decomposition accepted)"
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

Expected: `BUILD SUCCESSFUL`, `EXIT=0`. (If the pipe hides a failure, inspect `build/test-results/test/*.xml` for `<failure>`.)

## Self-Review (completed during planning)

**1. Spec coverage**
- §3.3 `ExtractMethodVerifier` (no-regression; exactly one new fn; source got simpler; source over threshold; extracted fn simpler than source) → Task 1. (The spec's "F′ < F" is the *source got strictly simpler* selection; "G < F" is the `extractedComplexity >= sourceOriginal` reject — no redundant double-check.)
- §3.4 dispatch (added-fn → ExtractMethod; in-place → ComplexityVerifier; threshold from settings; B2 untouched) → Task 2.
- §3.5 prompt (conditional `AEG-CPX-001` section; the two ops already render) → Task 3.
- §4 verification + §5 testing (verifier unit tests; routing both branches; e2e accept + decomposition) → Tasks 1-4.
- §6 phase C2 lands the capability. Out of scope (correctly): JS/TS, extract-expression, per-function analyzer.

**2. Placeholder scan** — none. Every step shows complete code or an exact insertion; every run step has the exact `--tests` command + expected outcome.

**3. Type consistency**
- `ExtractMethodVerifier(project, threshold).decide(target, baselineForFile, originalContent, candidateContent, candidateForFile): VerifyDecision` — same arg shape as `ComplexityVerifier.decide` (the `applyVerified` acceptance seam passes `(original, candidate, candidateIssues)`; `target`/`baselineForFile` are captured in the `FixEngine` lambda — matching Task 2's dispatch).
- `PerFunctionComplexity.measure(project, content).byKey: Map<String,Int>` keyed `"name/arity"` (e.g. `"process/3"`, `"f/4"`, `"g/2"`) — consumed identically in Task 1 and the Task 4 assertions.
- Dispatch uses `FunctionCounter.count(candidate) > count(original)` and, in the `else`, `ComplexityVerifier(FunctionCounter.count(original))` — byte-identical to the B2 call it replaces, so `FixEngineComplexityRoutingTest` stays green.
- `GhostDebuggerSettings.getInstance().snapshot().maxComplexity` (read, Task 2) and `.update { maxComplexity = … }` (mutate+restore, Task 4) match the service API.

**Verification of the accept fixtures (traced with `estimateComplexity(body,1)`):**
- Task 1 `original` `f` body: 4 `if` → 5; `candidateGood` `f`: 2 `if` + call → 3, `g`: 2 `if` → 3. newKeys `{g/2}`; source `f/4` (5→3); threshold 2 → 5>2 ✓; extracted 3 < 5 ✓ → **Accept**. The "not simpler" fixture gives `g` 4 `if` → 5 ≥ 5 → **Reject**. ✓
- Task 4 `process/3`: 3 `if` → 4; after extracting the `if (c)` block: `process` 2 `if` + call → 3, `handleC/1`: 1 `if` → 2; threshold 2 → 4>2 ✓; extracted 2 < 4 ✓ → **Accept**; source 4 → 3 (strictly lower). ✓
