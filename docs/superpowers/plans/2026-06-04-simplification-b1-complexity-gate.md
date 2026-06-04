# Code Simplification — B1: Complexity-Aware Verify Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `ComplexityVerifier` (accept iff complexity strictly decreases + no new other-rule issues) and thread an optional acceptance seam through `FixPlanApplicator.applyVerified`, so a simplification reuses the existing apply→reanalyze→accept/revert lifecycle but is judged by a recomputed complexity drop instead of the count-gate's "target resolved."

**Architecture:** `applyVerified` gains an optional `acceptance(originalContent, candidateContent, candidateIssues): VerifyDecision` parameter whose default reproduces today's `FixVerifier.decide(...)` (fully backward-compatible). `ComplexityVerifier` (using the module-internal `estimateComplexity`) is what callers pass for `AEG-CPX-001`. No ops/fixer yet — those are B2. Spec: `docs/superpowers/specs/2026-06-04-code-simplification-design.md`.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform (IPGP 2.14.0, 2024.3.2), kotlinx-coroutines, JUnit4 `BasePlatformTestCase` + plain JUnit4.

---

## Prerequisites (test prelude)

Tests need a JBR; shell env does NOT persist between Bash commands. Prefix EVERY gradlew call inline:
```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/graph/GraphBuilder.kt:121` — `internal fun estimateComplexity(content: String, functionCount: Int): Int = 1 + decisionPoints / functionCount.coerceAtLeast(1)` (top-level in package `com.ghostdebugger.graph`; module-internal → callable from `fix.engine`).
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixVerifier.kt` — `VerifyDecision { Accept; Reject(reason) }`; `FixVerifier.decide(target, baselineForFile, candidateForFile)` — per-`ruleKey` count gate (model for the no-regression logic).
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt` — `suspend fun applyVerified(plan, virtualFile, project, target, baselineForFile, reanalyze, verifier=FixVerifier(), edtContext=AegisWriteSafeEdt): FixApplyResult`. It applies + Tier-1, then `val candidateIssues = reanalyze()`, then `val decision = verifier.decide(target, baselineForFile, candidateIssues)`, then saves on `Accept` / reverts on `Reject`. `Tier1Outcome(ok, original)` carries the pre-fix text. `AegisWriteSafeEdt` is the write-safe dispatcher.
- `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` — `Issue.ruleKey()` (Phase 2b).
- Existing applyVerified/engine tests (`FixPlanApplicatorVerifyTest`, `FixEngineTest`, `FixEngineSupervisedTest`) — must stay green (the new param is defaulted).

## File structure

- Create `fix/engine/ComplexityVerifier.kt` + `src/test/kotlin/com/ghostdebugger/fix/engine/ComplexityVerifierTest.kt`.
- Modify `fix/engine/FixPlanApplicator.kt` (add the `acceptance` seam) + create `src/test/kotlin/com/ghostdebugger/fix/engine/ApplyVerifiedComplexityTest.kt`.

---

### Task 1: `ComplexityVerifier` (pure decision)

**Files:** Create `src/main/kotlin/com/ghostdebugger/fix/engine/ComplexityVerifier.kt`; Test `src/test/kotlin/com/ghostdebugger/fix/engine/ComplexityVerifierTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplexityVerifierTest {
    private fun issue(rule: String, type: IssueType = IssueType.HIGH_COMPLEXITY) = Issue(
        id = "x", type = type, severity = IssueSeverity.WARNING, title = "t", description = "",
        filePath = "A.kt", line = 1, ruleId = rule
    )
    private val target = issue("AEG-CPX-001")

    @Test fun acceptsWhenComplexityStrictlyDecreasesAndNoRegression() {
        // original has two `if`s, candidate one → estimateComplexity drops (functionCount=1)
        val d = ComplexityVerifier(functionCount = 1).decide(
            target, baselineForFile = listOf(target),
            originalContent = "if (a) {}\nif (b) {}", candidateContent = "if (a) {}",
            candidateForFile = emptyList()
        )
        assertTrue(d.toString(), d is VerifyDecision.Accept)
    }

    @Test fun rejectsWhenComplexityDidNotDecrease() {
        val d = ComplexityVerifier(functionCount = 1).decide(
            target, listOf(target),
            originalContent = "if (a) {}", candidateContent = "if (a) {}",
            candidateForFile = emptyList()
        )
        assertTrue(d.toString(), d is VerifyDecision.Reject)
    }

    @Test fun rejectsWhenANewOtherRuleIssueAppears() {
        // complexity dropped, but a new AEG-NULL-001 issue appeared → regression
        val d = ComplexityVerifier(functionCount = 1).decide(
            target, listOf(target),
            originalContent = "if (a) {}\nif (b) {}", candidateContent = "if (a) {}",
            candidateForFile = listOf(issue("AEG-NULL-001", IssueType.NULL_SAFETY))
        )
        assertTrue(d.toString(), d is VerifyDecision.Reject)
    }

    @Test fun ignoresTheComplexityRuleItselfInTheRegressionCheck() {
        // a re-detected AEG-CPX-001 in candidate must not count as a regression
        val d = ComplexityVerifier(functionCount = 1).decide(
            target, listOf(target),
            originalContent = "if (a) {}\nif (b) {}", candidateContent = "if (a) {}",
            candidateForFile = listOf(issue("AEG-CPX-001"))
        )
        assertTrue(d.toString(), d is VerifyDecision.Accept)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`ComplexityVerifier` unresolved). `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.ComplexityVerifierTest"`

- [ ] **Step 3: Implement `ComplexityVerifier.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.graph.estimateComplexity
import com.ghostdebugger.model.Issue

/**
 * Complexity-aware acceptance for simplification fixes (`AEG-CPX-001`). Accepts iff:
 *  - no *other* rule's per-ruleKey count increased vs the baseline (the target's own complexity rule
 *    is ignored — it is graph-level and absent from single-file re-analysis), AND
 *  - the recomputed [estimateComplexity] strictly decreases from original to candidate.
 *
 * The complexity-decrease replaces the count gate's "target resolved", which cannot apply to a
 * graph-level metric. [functionCount] is the flagged file's function count (stable under deterministic
 * branch-elimination; the AI extract-method follow-on will recompute it per candidate).
 */
class ComplexityVerifier(private val functionCount: Int) {
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
            if (count > (base[key] ?: 0)) {
                return VerifyDecision.Reject("Simplification introduces new \"$key\" issue(s).")
            }
        }
        val before = estimateComplexity(originalContent, functionCount)
        val after = estimateComplexity(candidateContent, functionCount)
        if (after >= before) {
            return VerifyDecision.Reject("Complexity did not decrease ($before -> $after).")
        }
        return VerifyDecision.Accept
    }
}
```

- [ ] **Step 4: Run → PASS** (4 tests). **Step 5: Commit** `git commit -m "feat(fix-engine): ComplexityVerifier (complexity-drop + no-regression)"`

---

### Task 2: `applyVerified` acceptance seam + integration test

**Files:** Modify `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt`; Test `src/test/kotlin/com/ghostdebugger/fix/engine/ApplyVerifiedComplexityTest.kt`

- [ ] **Step 1: Establish the green baseline**

`JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanApplicatorVerifyTest" --tests "com.ghostdebugger.fix.engine.FixEngineTest" --tests "com.ghostdebugger.fix.engine.FixEngineSupervisedTest"` → note pass counts (these must stay green; the new param is defaulted).

- [ ] **Step 2: Write the failing integration test**

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

/** The acceptance seam routes the save/revert decision through ComplexityVerifier. */
class ApplyVerifiedComplexityTest : BasePlatformTestCase() {
    private fun cpxTarget(path: String) = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = path, line = 1, ruleId = "AEG-CPX-001"
    )
    private fun complexityAcceptance(target: Issue) =
        { original: String, candidate: String, cand: List<Issue> ->
            ComplexityVerifier(functionCount = 1).decide(target, listOf(target), original, candidate, cand)
        }

    fun testAcceptsAComplexityReducingEdit() {
        val content = "fun f(a: Boolean) {\n    if (a) g()\n    h()\n}\n"
        val vf = myFixture.configureByText("A.kt", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val start = text.indexOf("if (a) g()")
        val plan = FixPlan("c1", listOf(ReplaceRange(start, start + "if (a) g()".length, "g()")))  // removes the `if`
        val target = cpxTarget(vf.path)

        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
                acceptance = complexityAcceptance(target),
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertFalse(runReadAction { myFixture.getDocument(myFixture.file).text }.contains("if (a)"))
    }

    fun testRejectsAndRevertsANonReducingEdit() {
        val content = "fun f(a: Boolean) {\n    if (a) g()\n}\n"
        val vf = myFixture.configureByText("A.kt", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val start = text.indexOf("g()")
        val plan = FixPlan("c1", listOf(ReplaceRange(start, start + 3, "k()")))  // rename only — complexity unchanged
        val target = cpxTarget(vf.path)

        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
                acceptance = complexityAcceptance(target),
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertEquals(content, runReadAction { myFixture.getDocument(myFixture.file).text })
    }
}
```

- [ ] **Step 3: Run → FAIL** (the `acceptance` parameter doesn't exist yet). `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.ApplyVerifiedComplexityTest"`

- [ ] **Step 4: Add the `acceptance` seam to `applyVerified`**

Read the current `applyVerified` in `FixPlanApplicator.kt`, then make exactly these three surgical changes:

1. Add a parameter (after `verifier`, before/with `edtContext`) whose default reproduces today's behavior:

```kotlin
        acceptance: (originalContent: String, candidateContent: String, candidateIssues: List<Issue>) -> VerifyDecision =
            { _, _, candidateIssues -> verifier.decide(target, baselineForFile, candidateIssues) },
```

2. After `val candidateIssues = reanalyze()`, read the committed candidate text in a read action:

```kotlin
            val candidateContent = ApplicationManager.getApplication().runReadAction<String> { document.text }
```

3. Replace the decision line `val decision = verifier.decide(target, baselineForFile, candidateIssues)` with:

```kotlin
            val decision = acceptance(outcome.original, candidateContent, candidateIssues)
```

Leave everything else (Tier-1, save/revert, PCE rethrow) unchanged. The default `acceptance` keeps every existing caller (`fix`/`fixVerified`/`fixSupervised`/tests) behaving identically. (`outcome.original` is the pre-fix text from `Tier1Outcome`; `document` is in scope.)

- [ ] **Step 5: Run the integration test → PASS** (2 tests). `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.ApplyVerifiedComplexityTest"`

- [ ] **Step 6: Run the Step-1 baseline suites → still green** (default seam = unchanged behavior). `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanApplicatorVerifyTest" --tests "com.ghostdebugger.fix.engine.FixEngineTest" --tests "com.ghostdebugger.fix.engine.FixEngineSupervisedTest"`

- [ ] **Step 7: Commit** `git commit -m "feat(fix-engine): applyVerified acceptance seam for complexity-aware gating"`

---

## Final verification

- [ ] `JAVA_HOME=... ./gradlew test --tests "com.ghostdebugger.fix.engine.*"` → green.
- [ ] `JAVA_HOME=... ./gradlew test 2>&1 | tail -6; echo EXIT=${PIPESTATUS[0]}` → full suite green, `EXIT=0`.

## Self-Review (completed during planning)

- **Spec coverage:** complexity-aware gate (spec §3.2, §4) → Task 1 (`ComplexityVerifier`) + Task 2 (the `applyVerified` seam). No ops/fixer (those are B2).
- **Backward-compat:** the new `acceptance` parameter defaults to `verifier.decide(target, baselineForFile, candidateIssues)` — byte-for-byte today's behavior; all existing callers/tests unaffected (Task 2 Steps 1 & 6 verify).
- **Type consistency:** `ComplexityVerifier(functionCount).decide(target, baselineForFile, originalContent, candidateContent, candidateForFile): VerifyDecision`; `acceptance(originalContent, candidateContent, candidateIssues): VerifyDecision`; `estimateComplexity(content, functionCount)`; `Issue.ruleKey()`; `outcome.original` is the pre-fix text — all consistent.
- **No-false-positive / determinism:** acceptance requires a strict complexity decrease AND no other-rule regression AND (Tier-1) PSI-valid; a non-reducing or breaking edit is reverted. No AI judgment.
- **Placeholders:** none — `estimateComplexity` is the real module-internal metric; Task 2 names the exact three edits against the real `applyVerified`.
