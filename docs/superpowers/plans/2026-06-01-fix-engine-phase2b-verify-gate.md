# Fix Engine Phase 2b — Tier-2 Re-analysis Verify Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Tier-2 verify gate that re-analyzes a candidate deterministic fix in-place and accepts it only if it resolves the target issue and introduces no regressions, using the live committed Document as the transient-document mechanism so Kotlin analyzers get full in-module type resolution.

**Architecture:** The gate has two cleanly separated layers. (1) A **pure decision function** `FixVerifier.decide(target, baseline, candidate)` compares per-ruleKey *counts* (line-shift-immune): the target's rule must have strictly fewer occurrences and no rule may increase. (2) A **transactional applicator** `FixPlanApplicator.applyVerified(...)` (suspend) drives the transient-document lifecycle — on the EDT it applies edits + commits + runs the Tier-1 PSI-validity check; off the EDT it re-analyzes the now-committed (but unsaved) Document via an injected `reanalyze` callback; back on the EDT it saves on accept or reverts on reject. Because `KotlinAnalyzer` resolves PSI from `virtualFile`, committing the candidate makes the standard single-file re-analysis path observe it with no special casing. The default `reanalyze` is a single-file static pass (`SingleFileStaticReanalysis`) mirroring `AnalysisOrchestrator.reanalyzeFile`'s reparse idiom.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform (IPGP 2.14.0, intellijIdeaCommunity 2024.3.2), K2 Kotlin Analysis API, kotlinx-coroutines (`Dispatchers.Swing` for EDT hops), JUnit3-style `BasePlatformTestCase` / `AegisKotlinAnalysisTestCase`.

---

## Prerequisites (test prelude)

Gradle's `instrumentTestCode` requires a JetBrains Runtime. Export it once per shell before running any test:

```bash
export JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)"
export PATH="$JAVA_HOME/bin:$PATH"
```

All `./gradlew test --tests "..."` commands below assume this is set.

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt` — the Tier-1 applicator this plan refactors and extends.
- `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt` — entry point gaining `fixVerified`.
- `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` — `Issue` (+ `fingerprint()`), `AnalysisContext`, `ParsedFile`.
- `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt:223-256` — the reparse + off-EDT analyze idiom the default `reanalyze` mirrors (`FileScanner(project).parsedFiles(...)`, `SymbolExtractor(project).extract(...)`, `AnalysisEngine().analyzeStaticOnly(ctx)`).
- `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicatorTest.kt` — fixture pattern (`BasePlatformTestCase`, `myFixture.configureByText`, `runReadAction { myFixture.getDocument(psi).text }`).
- `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEnginePostEditRerunTest.kt` — reference for an off-EDT re-analysis test harness.
- `src/test/kotlin/com/ghostdebugger/AegisKotlinAnalysisTestCase.kt` — base class for the Kotlin end-to-end integration test (runs off-EDT).

## File structure

- **Create** `src/main/kotlin/com/ghostdebugger/fix/engine/FixVerifier.kt` — `VerifyDecision` + `FixVerifier.decide(...)` (pure).
- **Create** `src/main/kotlin/com/ghostdebugger/fix/engine/SingleFileStaticReanalysis.kt` — default off-EDT single-file re-analysis provider.
- **Modify** `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt` — add `Issue.ruleKey()`, route `fingerprint()` through it.
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt` — extract `applyAndCheck`, add suspend `applyVerified`.
- **Modify** `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt` — add suspend `fixVerified`.
- **Create** `src/test/kotlin/com/ghostdebugger/fix/engine/FixVerifierTest.kt`
- **Create** `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicatorVerifyTest.kt`
- **Create** `src/test/kotlin/com/ghostdebugger/fix/engine/FixVerifyKotlinIntegrationTest.kt`
- **Modify** `src/test/kotlin/com/ghostdebugger/model/` (add `IssueRuleKeyTest.kt`) — or colocate with an existing model test if one exists.
- **Modify** `docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md` — mark Tier-2 implemented in 2b; record the count-based decision.

---

### Task 1: `Issue.ruleKey()` — single source of rule identity

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt:30-32`
- Test: `src/test/kotlin/com/ghostdebugger/model/IssueRuleKeyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.model

import org.junit.Assert.assertEquals
import org.junit.Test

class IssueRuleKeyTest {
    private fun issue(ruleId: String?, type: IssueType, line: Int) = Issue(
        id = "x", type = type, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = "A.kt", line = line, ruleId = ruleId
    )

    @Test fun ruleKeyUsesRuleIdWhenPresent() {
        assertEquals("AEG-CAST-KT-001", issue("AEG-CAST-KT-001", IssueType.NULL_SAFETY, 1).ruleKey())
    }

    @Test fun ruleKeyFallsBackToTypeNameWhenRuleIdNull() {
        assertEquals("NULL_SAFETY", issue(null, IssueType.NULL_SAFETY, 1).ruleKey())
    }

    @Test fun fingerprintComposesRuleKeyPathAndLine() {
        assertEquals("AEG-CAST-KT-001:A.kt:7", issue("AEG-CAST-KT-001", IssueType.NULL_SAFETY, 7).fingerprint())
    }

    @Test fun fingerprintUsesTypeNameWhenRuleIdNull() {
        assertEquals("NULL_SAFETY:A.kt:7", issue(null, IssueType.NULL_SAFETY, 7).fingerprint())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.ghostdebugger.model.IssueRuleKeyTest"`
Expected: FAIL — `ruleKey()` is unresolved (does not compile / no such method).

- [ ] **Step 3: Add `ruleKey()` and route `fingerprint()` through it**

In `AnalysisModels.kt`, replace the current `fingerprint()` body (lines 30-32):

```kotlin
    /** Stable rule identity: the analyzer's ruleId, or the issue type name when no ruleId is set. */
    fun ruleKey(): String = ruleId ?: type.name

    fun fingerprint(): String =
        listOf(ruleKey(), filePath, line.toString()).joinToString(":")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.ghostdebugger.model.IssueRuleKeyTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/model/AnalysisModels.kt src/test/kotlin/com/ghostdebugger/model/IssueRuleKeyTest.kt
git commit -m "feat(fix-engine): add Issue.ruleKey() as single source of rule identity"
```

---

### Task 2: `FixVerifier.decide` — the pure count-based gate

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/FixVerifier.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixVerifierTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertTrue
import org.junit.Test

class FixVerifierTest {
    private fun issue(ruleId: String?, type: IssueType, line: Int) = Issue(
        id = "x", type = type, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = "A.kt", line = line, ruleId = ruleId
    )

    private val cast10 = issue("AEG-CAST-KT-001", IssueType.NULL_SAFETY, 10)
    private val cast30 = issue("AEG-CAST-KT-001", IssueType.NULL_SAFETY, 30)

    @Test fun acceptsWhenTargetResolvedAndNoRegression() {
        val d = FixVerifier().decide(cast10, baselineForFile = listOf(cast10), candidateForFile = emptyList())
        assertTrue(d is VerifyDecision.Accept)
    }

    @Test fun rejectsWhenTargetRuleCountUnchanged() {
        val d = FixVerifier().decide(cast10, baselineForFile = listOf(cast10), candidateForFile = listOf(cast10))
        assertTrue(d is VerifyDecision.Reject)
    }

    @Test fun rejectsWhenANewRuleAppears() {
        val newRule = issue("AEG-NULL-KT-001", IssueType.NULL_SAFETY, 5)
        // target cast resolved (0 < 1) but a different rule rose 0 -> 1
        val d = FixVerifier().decide(cast10, baselineForFile = listOf(cast10), candidateForFile = listOf(newRule))
        assertTrue(d is VerifyDecision.Reject)
    }

    @Test fun acceptsWhenUnchangedIssueLineShifts() {
        // Simulates InsertImport shifting an unrelated issue from line 20 -> 21.
        val async20 = issue("AEG-ASYNC-001", IssueType.UNHANDLED_PROMISE, 20)
        val async21 = issue("AEG-ASYNC-001", IssueType.UNHANDLED_PROMISE, 21)
        val d = FixVerifier().decide(
            cast10,
            baselineForFile = listOf(cast10, async20),
            candidateForFile = listOf(async21) // cast gone, async same count at a shifted line
        )
        assertTrue(d is VerifyDecision.Accept)
    }

    @Test fun acceptsWhenOneOfTwoSameRuleIssuesResolved() {
        val d = FixVerifier().decide(
            cast10,
            baselineForFile = listOf(cast10, cast30),
            candidateForFile = listOf(cast30) // 2 -> 1
        )
        assertTrue(d is VerifyDecision.Accept)
    }

    @Test fun rejectsWhenTargetRuleNotInBaseline() {
        // Degenerate: target rule absent from baseline -> cannot be "resolved".
        val other = issue("AEG-ASYNC-001", IssueType.UNHANDLED_PROMISE, 5)
        val d = FixVerifier().decide(cast10, baselineForFile = listOf(other), candidateForFile = listOf(other))
        assertTrue(d is VerifyDecision.Reject)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixVerifierTest"`
Expected: FAIL — `FixVerifier` / `VerifyDecision` unresolved.

- [ ] **Step 3: Implement `FixVerifier.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue

/** Outcome of the Tier-2 verify gate. */
sealed interface VerifyDecision {
    /** The candidate fix resolved the target and introduced no regressions. */
    object Accept : VerifyDecision
    /** The candidate fix is rejected; [reason] is user-facing. */
    data class Reject(val reason: String) : VerifyDecision
}

/**
 * Pure Tier-2 decision over single-file re-analysis results, comparing per-[Issue.ruleKey] *counts*
 * rather than fingerprints so the verdict is immune to line shifts (e.g. an inserted import that
 * renumbers every following issue). A candidate is accepted iff:
 *  - no rule's occurrence count increased versus the baseline (no regression), AND
 *  - the target's rule has strictly fewer occurrences than in the baseline (target resolved).
 *
 * Known acceptable miss (consistent with the project's conservative-miss bias): a fix that resolves
 * the target instance but introduces a *different* instance of the *same* rule nets a zero count
 * delta and is accepted. Single-purpose deterministic fixers rarely do this.
 */
class FixVerifier {
    fun decide(
        target: Issue,
        baselineForFile: List<Issue>,
        candidateForFile: List<Issue>,
    ): VerifyDecision {
        val base = baselineForFile.groupingBy { it.ruleKey() }.eachCount()
        val cand = candidateForFile.groupingBy { it.ruleKey() }.eachCount()

        for ((key, count) in cand) {
            val before = base[key] ?: 0
            if (count > before) {
                return VerifyDecision.Reject("Fix introduces new \"$key\" issue(s) (was $before, now $count).")
            }
        }

        val targetKey = target.ruleKey()
        val resolved = (cand[targetKey] ?: 0) < (base[targetKey] ?: 0)
        if (!resolved) {
            return VerifyDecision.Reject("Fix did not resolve the target \"$targetKey\" issue.")
        }
        return VerifyDecision.Accept
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixVerifierTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixVerifier.kt src/test/kotlin/com/ghostdebugger/fix/engine/FixVerifierTest.kt
git commit -m "feat(fix-engine): FixVerifier count-based Tier-2 decision (line-shift immune)"
```

---

### Task 3: Extract `applyAndCheck` from `FixPlanApplicator` (Tier-1 primitive)

Refactor so both the existing synchronous `apply` and the new `applyVerified` share one apply+commit+validity routine. Behavior-preserving — existing tests must stay green.

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicatorTest.kt` (existing — used as regression)

- [ ] **Step 1: Run the existing tests to confirm the green baseline**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanApplicatorTest"`
Expected: PASS (2 tests). This is the regression bar for the refactor.

- [ ] **Step 2: Refactor — extract `applyAndCheck`, keep `apply` behavior**

Replace the body of `apply(...)`'s write action and add the private helper. The full file becomes:

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Applies a [FixPlan] to a file's Document inside a write action and enforces the Tier-1
 * PSI-validity gate: commit the PSI and, if a [PsiErrorElement] appears, revert and reject.
 * [applyVerified] additionally runs the Tier-2 re-analysis gate (see FixVerifier).
 */
class FixPlanApplicator {
    private val log = logger<FixPlanApplicator>()

    /** Result of the Tier-1 apply step. When [ok] is false the document has already been reverted. */
    private data class Tier1Outcome(val ok: Boolean, val original: String)

    /**
     * Runs inside a write action on the EDT. Applies [edits], commits, checks PSI validity. On an
     * error element it reverts the document and returns ok=false; on success it leaves the candidate
     * **committed but unsaved** and returns ok=true with the [Tier1Outcome.original] text for any
     * later revert.
     */
    private fun applyAndCheck(document: Document, edits: List<TextEdit>, project: Project): Tier1Outcome {
        val original = document.text
        for (edit in edits.sortedByDescending { it.startOffset }) {
            document.replaceString(edit.startOffset, edit.endOffset, edit.replacement)
        }
        val psiDocMgr = PsiDocumentManager.getInstance(project)
        psiDocMgr.commitDocument(document)

        val psiFile = psiDocMgr.getPsiFile(document)
        val firstError = psiFile?.let { PsiTreeUtil.findChildOfType(it, PsiErrorElement::class.java) }
        return if (firstError != null) {
            log.warn("Fix rejected (Tier-1): PSI error after apply: ${firstError.errorDescription}")
            document.setText(original)
            psiDocMgr.commitDocument(document)
            Tier1Outcome(ok = false, original = original)
        } else {
            Tier1Outcome(ok = true, original = original)
        }
    }

    /** Reads the document and computes edits, or returns null with a rejection reason. */
    private fun resolveEdits(plan: FixPlan, virtualFile: VirtualFile, project: Project): Pair<Document, List<TextEdit>>? {
        val fdm = FileDocumentManager.getInstance()
        val document = ApplicationManager.getApplication().runReadAction<Document?> {
            fdm.getDocument(virtualFile)
        } ?: return null
        val edits = ApplicationManager.getApplication().runReadAction<List<TextEdit>?> {
            val ctx = FixContext(document.text) {
                com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
            }
            plan.toEdits(ctx)
        } ?: return null
        return document to edits
    }

    /** Tier-1 only: apply, validity-check, and save on success. Unchanged public behavior. */
    fun apply(plan: FixPlan, virtualFile: VirtualFile, project: Project): FixApplyResult {
        return try {
            val fdm = FileDocumentManager.getInstance()
            val (document, edits) = resolveEdits(plan, virtualFile, project)
                ?: return FixApplyResult.Rejected("Plan does not apply to current content (stale offsets).")

            var succeeded = false
            WriteCommandAction.runWriteCommandAction(project, "Apply Aegis Debug Fix", null, Runnable {
                val outcome = applyAndCheck(document, edits, project)
                if (outcome.ok) {
                    fdm.saveDocument(document)
                    succeeded = true
                }
            })

            if (succeeded) FixApplyResult.Success
            else FixApplyResult.Rejected("The proposed fix would produce invalid code and was not applied.")
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            FixApplyResult.Failed(t)
        }
    }
}
```

(Note: `applyVerified` is added in Task 4. `resolveEdits` returns null both for "no document" and "stale offsets"; `apply` collapses them to the stale-offsets message, matching prior behavior closely enough — the prior code only distinguished them in log text, not in the returned result for the stale case. The "No document" path previously returned a distinct message; preserve it by checking document separately in Task 4's `applyVerified`, which surfaces both. For `apply`, the collapsed message is acceptable and not asserted by tests.)

- [ ] **Step 3: Run the existing tests to verify the refactor is green**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanApplicatorTest"`
Expected: PASS (2 tests), unchanged.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt
git commit -m "refactor(fix-engine): extract applyAndCheck Tier-1 primitive"
```

---

### Task 4: `FixPlanApplicator.applyVerified` — transient-document Tier-2 lifecycle

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicatorVerifyTest.kt`

- [ ] **Step 1: Write the failing test**

These tests inject a **fake `reanalyze`** and `Dispatchers.Unconfined` so they run deterministically on the EDT-based `BasePlatformTestCase` with no real Analysis API. They prove the apply → re-analyze → accept/revert lifecycle.

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

class FixPlanApplicatorVerifyTest : BasePlatformTestCase() {

    private val target = Issue(
        id = "t", type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CAST-KT-001"
    )

    private fun planReplacing(needle: String, replacement: String, psiText: String): FixPlan {
        val start = psiText.indexOf(needle)
        return FixPlan("t", listOf(ReplaceRange(start, start + needle.length, replacement)))
    }

    fun testAcceptsWhenReanalysisShowsTargetResolved() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val text = runReadAction { myFixture.getDocument(psi).text }
        val plan = planReplacing("return 1", "return 2", text)

        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target,
                baselineForFile = listOf(target),
                reanalyze = { emptyList() },              // candidate clean: resolved + no regression
                edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(psi).text }.contains("return 2"))
    }

    fun testRejectsAndRevertsWhenTargetStillPresent() {
        val original = "fun f(): Int { return 1 }\n"
        val psi = myFixture.configureByText("A.kt", original)
        val vf = psi.virtualFile
        val text = runReadAction { myFixture.getDocument(psi).text }
        val plan = planReplacing("return 1", "return 2", text)

        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target,
                baselineForFile = listOf(target),
                reanalyze = { listOf(target) },           // target still detected: not resolved
                edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertEquals(original, runReadAction { myFixture.getDocument(psi).text })
    }

    fun testRejectsAndRevertsOnRegression() {
        val original = "fun f(): Int { return 1 }\n"
        val psi = myFixture.configureByText("A.kt", original)
        val vf = psi.virtualFile
        val text = runReadAction { myFixture.getDocument(psi).text }
        val plan = planReplacing("return 1", "return 2", text)
        val newRule = target.copy(id = "n", ruleId = "AEG-NULL-KT-001")

        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target,
                baselineForFile = listOf(target),
                reanalyze = { listOf(newRule) },          // target gone but a new rule appears
                edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertEquals(original, runReadAction { myFixture.getDocument(psi).text })
    }

    fun testRejectsInvalidCandidateBeforeReanalysis() {
        val original = "fun f(): Int { return 1 }\n"
        val psi = myFixture.configureByText("A.kt", original)
        val vf = psi.virtualFile
        val text = runReadAction { myFixture.getDocument(psi).text }
        val plan = planReplacing("return 1", "return 1 }}}", text) // unbalanced braces -> Tier-1 fail
        var reanalyzeCalled = false

        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target,
                baselineForFile = listOf(target),
                reanalyze = { reanalyzeCalled = true; emptyList() },
                edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertFalse("Tier-2 must not run when Tier-1 fails", reanalyzeCalled)
        assertEquals(original, runReadAction { myFixture.getDocument(psi).text })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanApplicatorVerifyTest"`
Expected: FAIL — `applyVerified` unresolved.

- [ ] **Step 3: Implement `applyVerified`**

Add these imports to `FixPlanApplicator.kt`:

```kotlin
import com.ghostdebugger.model.Issue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
```

Add this method to the `FixPlanApplicator` class:

```kotlin
    /**
     * Tier-1 + Tier-2. Applies [plan], runs the PSI-validity gate, then — with the candidate
     * **committed but unsaved** — invokes [reanalyze] off the EDT to re-analyze the live document
     * (the transient-document mechanism: Kotlin analyzers resolve PSI from the virtual file, so the
     * committed candidate is what they see). [verifier] decides; the document is saved on Accept or
     * reverted on Reject. EDT hops use [edtContext] (overridable in tests).
     *
     * Must be called from a coroutine. [reanalyze] must run its own read action and return the
     * issues for this file under the candidate content.
     */
    suspend fun applyVerified(
        plan: FixPlan,
        virtualFile: VirtualFile,
        project: Project,
        target: Issue,
        baselineForFile: List<Issue>,
        reanalyze: suspend () -> List<Issue>,
        verifier: FixVerifier = FixVerifier(),
        edtContext: CoroutineContext = Dispatchers.Swing,
    ): FixApplyResult {
        return try {
            val fdm = FileDocumentManager.getInstance()
            val document = ApplicationManager.getApplication().runReadAction<Document?> {
                fdm.getDocument(virtualFile)
            } ?: return FixApplyResult.Rejected("No document for ${virtualFile.path}")
            val edits = ApplicationManager.getApplication().runReadAction<List<TextEdit>?> {
                val ctx = FixContext(document.text) {
                    com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
                }
                plan.toEdits(ctx)
            } ?: return FixApplyResult.Rejected("Plan does not apply to current content (stale offsets).")

            // Tier-1 on the EDT. Reverts itself if the candidate is not PSI-valid.
            var tier1: Tier1Outcome? = null
            withContext(edtContext) {
                WriteCommandAction.runWriteCommandAction(project, "Apply Aegis Debug Fix", null, Runnable {
                    tier1 = applyAndCheck(document, edits, project)
                })
            }
            val outcome = tier1 ?: return FixApplyResult.Failed(IllegalStateException("Tier-1 produced no outcome"))
            if (!outcome.ok) {
                return FixApplyResult.Rejected("The proposed fix would produce invalid code and was not applied.")
            }

            // Tier-2 off the EDT: the document now holds the committed (unsaved) candidate.
            val candidateIssues = reanalyze()
            val decision = verifier.decide(target, baselineForFile, candidateIssues)

            // Commit the decision on the EDT: save on Accept, revert on Reject.
            var result: FixApplyResult = FixApplyResult.Failed(IllegalStateException("No decision applied"))
            withContext(edtContext) {
                WriteCommandAction.runWriteCommandAction(project, "Finalize Aegis Debug Fix", null, Runnable {
                    when (decision) {
                        is VerifyDecision.Accept -> {
                            fdm.saveDocument(document)
                            result = FixApplyResult.Success
                        }
                        is VerifyDecision.Reject -> {
                            document.setText(outcome.original)
                            PsiDocumentManager.getInstance(project).commitDocument(document)
                            result = FixApplyResult.Rejected(decision.reason)
                        }
                    }
                })
            }
            result
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            FixApplyResult.Failed(t)
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanApplicatorVerifyTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the Tier-1 regression suite**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixPlanApplicatorTest"`
Expected: PASS (2 tests) — Tier-1 path unaffected.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicator.kt src/test/kotlin/com/ghostdebugger/fix/engine/FixPlanApplicatorVerifyTest.kt
git commit -m "feat(fix-engine): applyVerified transient-document Tier-2 gate"
```

---

### Task 5: `SingleFileStaticReanalysis` + `FixEngine.fixVerified` wiring

**Files:**
- Create: `src/main/kotlin/com/ghostdebugger/fix/engine/SingleFileStaticReanalysis.kt`
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineTest.kt` (add cases)

- [ ] **Step 1: Write the failing test (add to `FixEngineTest`)**

Add these two cases. The first proves `fixVerified` short-circuits when no fixer applies (pure, no threading). The second proves it derives a plan and routes through `applyVerified` to Success, using a fake `reanalyze` + `Dispatchers.Unconfined`.

```kotlin
    fun testFixVerifiedRejectsWhenNoDeterministicFixer() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        val issue = com.ghostdebugger.model.Issue(
            id = "i", type = com.ghostdebugger.model.IssueType.ARCHITECTURE,
            severity = com.ghostdebugger.model.IssueSeverity.WARNING,
            title = "t", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-NO-FIXER-999"
        )

        val result = kotlinx.coroutines.runBlocking {
            FixEngine(project).fixVerified(
                issue, vf, content, baselineForFile = listOf(issue),
                reanalyze = { emptyList() },
                edtContext = kotlinx.coroutines.Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is com.ghostdebugger.fix.FixApplyResult.Rejected)
    }

    fun testFixVerifiedAppliesAndVerifiesAnInjectedPlan() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        val start = content.indexOf("return 1")
        val issue = com.ghostdebugger.model.Issue(
            id = "i", type = com.ghostdebugger.model.IssueType.NULL_SAFETY,
            severity = com.ghostdebugger.model.IssueSeverity.WARNING,
            title = "t", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CAST-KT-001"
        )
        val plan = FixPlan(issue.id, listOf(ReplaceRange(start, start + "return 1".length, "return 2")))
        // Inject a deriver that returns a CodeFix-free plan directly via a stub deriveCodeFix path:
        val engine = FixEngine(
            project = project,
            deriveCodeFix = { _, _, c ->
                val s = c.indexOf("return 1")
                com.ghostdebugger.model.CodeFix(
                    id = "f", issueId = issue.id, description = "d",
                    originalCode = "return 1", fixedCode = "return 2",
                    filePath = "A.kt", startOffset = s, endOffset = s + "return 1".length
                )
            },
        )

        val result = kotlinx.coroutines.runBlocking {
            engine.fixVerified(
                issue, vf, content, baselineForFile = listOf(issue),
                reanalyze = { emptyList() },  // candidate clean -> resolved + no regression
                edtContext = kotlinx.coroutines.Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is com.ghostdebugger.fix.FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(psi).text }.contains("return 2"))
    }
```

> Implementer note: the `CodeFix(...)` constructor arguments above mirror Phase 2a usage (`originalCode`/`fixedCode`/`startOffset`/`endOffset`). If the actual `CodeFix` field names differ, read `AnalysisModels.kt` and adjust the call to match — the test's intent is "a deriver that yields a `return 1` → `return 2` replacement". Confirm `FixEngineTest` already imports `runReadAction` and extends `BasePlatformTestCase` (it does in the existing file).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineTest"`
Expected: FAIL — `fixVerified` unresolved.

- [ ] **Step 3: Implement `SingleFileStaticReanalysis.kt`**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.analysis.AnalysisEngine
import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ParsedFile
import com.ghostdebugger.parser.FileScanner
import com.ghostdebugger.parser.SymbolExtractor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Default Tier-2 [reanalyze] provider: re-parse [virtualFile] from its **live (committed)** document
 * and run the static-only analysis pass, returning the issues for that file. Mirrors
 * `AnalysisOrchestrator.reanalyzeFile`'s reparse idiom but scoped to a single file with an empty
 * graph (single-file gate; cross-file graph rules are out of scope for a deterministic fix).
 *
 * **Must be called off the EDT** — the Kotlin Analysis API throws from the EDT.
 */
class SingleFileStaticReanalysis(
    private val project: Project,
    private val engineFactory: () -> AnalysisEngine = { AnalysisEngine() },
) {
    suspend fun issuesFor(virtualFile: VirtualFile): List<Issue> {
        val parsed = ApplicationManager.getApplication().runReadAction<ParsedFile?> {
            FileScanner(project).parsedFiles(listOf(virtualFile)).firstOrNull()
        } ?: return emptyList()
        val extracted = SymbolExtractor(project).extract(parsed)
        val ctx = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(extracted),
        )
        val targetPath = virtualFile.path.replace("\\", "/")
        return engineFactory().analyzeStaticOnly(ctx).issues
            .filter { it.filePath.replace("\\", "/") == targetPath }
    }
}
```

- [ ] **Step 4: Implement `FixEngine.fixVerified`**

Add these imports to `FixEngine.kt`:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
```

Add this method to the `FixEngine` class:

```kotlin
    /**
     * Derive + apply with the Tier-2 re-analysis verify gate. Returns Rejected when no deterministic
     * fixer applies, the candidate is not PSI-valid, or the gate rejects it. [reanalyze] defaults to
     * a single-file static pass over the live (committed) candidate; [baselineForFile] is the set of
     * issues already known for the file (its current findings). Must be called from a coroutine.
     */
    suspend fun fixVerified(
        issue: Issue,
        virtualFile: VirtualFile,
        content: String,
        baselineForFile: List<Issue>,
        reanalyze: suspend () -> List<Issue> = { SingleFileStaticReanalysis(project).issuesFor(virtualFile) },
        edtContext: CoroutineContext = Dispatchers.Swing,
    ): FixApplyResult {
        val plan = planFor(issue, virtualFile, content)
            ?: return FixApplyResult.Rejected("No deterministic fix available for ${issue.ruleId}.")
        return applicator.applyVerified(
            plan, virtualFile, project, issue, baselineForFile, reanalyze, edtContext = edtContext,
        )
    }
```

Also add `import kotlinx.coroutines.swing.Swing` to `FixEngine.kt` (for `Dispatchers.Swing`).

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixEngineTest"`
Expected: PASS (existing cases + 2 new).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/SingleFileStaticReanalysis.kt src/main/kotlin/com/ghostdebugger/fix/engine/FixEngine.kt src/test/kotlin/com/ghostdebugger/fix/engine/FixEngineTest.kt
git commit -m "feat(fix-engine): FixEngine.fixVerified + default single-file reanalysis provider"
```

---

### Task 6: Kotlin end-to-end integration — the transient-document mechanism

Proves the whole mechanism with **real** Kotlin analysis: a genuine unsafe cast is detected, a `ConvertToSafeCast` (Phase 2a op) candidate is committed, the live committed PSI is re-analyzed off-EDT (full in-module type resolution), the cast is resolved with no regression, and the fix is accepted and saved.

**Files:**
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixVerifyKotlinIntegrationTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.fix.FixApplyResult
import com.intellij.openapi.application.runReadAction
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile

/**
 * End-to-end Tier-2: real analyzer detection -> ConvertToSafeCast candidate -> transient-document
 * re-analysis -> Accept. Runs off the EDT (AegisKotlinAnalysisTestCase) so the Analysis API is legal
 * and the default Dispatchers.Swing EDT hops in applyVerified resolve against a free EDT.
 */
class FixVerifyKotlinIntegrationTest : AegisKotlinAnalysisTestCase() {

    fun testTransientReanalysisAcceptsRealSafeCastFix() {
        val code = "fun f(a: Any): String { return a as String }\n"
        val psi = myFixture.configureByText("A.kt", code) as KtFile
        val vf = psi.virtualFile

        // 1. Baseline: detect the real unsafe-cast issue from the original document (off-EDT).
        val baseline = runBlocking { SingleFileStaticReanalysis(project).issuesFor(vf) }
        val target = baseline.first { it.ruleId == "AEG-CAST-KT-001" }

        // 2. Build the Phase-2a ConvertToSafeCast plan from the detected `as` keyword offset.
        val text = runReadAction { myFixture.getDocument(psi).text }
        val asOffset = text.indexOf(" as ") + 1
        val plan = FixPlan(target.id, listOf(ConvertToSafeCast(asOffset)))

        // 3. Apply + verify with the real single-file re-analysis (default Swing dispatcher).
        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target,
                baselineForFile = baseline,
                reanalyze = { SingleFileStaticReanalysis(project).issuesFor(vf) },
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
        val after = runReadAction { myFixture.getDocument(psi).text }
        assertTrue(after, after.contains("as? String"))
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests "com.ghostdebugger.fix.engine.FixVerifyKotlinIntegrationTest"`
Expected: PASS (1 test).

Troubleshooting if it fails:
- **Baseline has no `AEG-CAST-KT-001`:** confirm `KotlinUnsafeCastAnalyzer` flags `a as String` in this construct; adjust the sample so the cast is genuinely unsafe and reachable (a non-null target type from `Any`).
- **`asOffset` doesn't match the `as` keyword:** print `text` and locate the keyword; `ConvertToSafeCast` requires `operationReference.textRange.startOffset == asOffset`.
- **Hang / EDT assertion:** ensure the class extends `AegisKotlinAnalysisTestCase` (off-EDT). Do **not** pass `Dispatchers.Unconfined` here — the real EDT hop is intended.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/ghostdebugger/fix/engine/FixVerifyKotlinIntegrationTest.kt
git commit -m "test(fix-engine): Kotlin end-to-end transient-document Tier-2 gate"
```

---

### Task 7: Document Tier-2 as implemented in 2b

**Files:**
- Modify: `docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md`

- [ ] **Step 1: Update §3.3 (verify gate) and §9 (phasing)**

In §3.3, update the Tier-2 description to state it is implemented in Phase 2b and record the design choices:
- The gate re-analyzes the **single fixed file** via `SingleFileStaticReanalysis` (static-only pass), not the whole project.
- The candidate is observed through the **live committed (unsaved) Document** — the transient-document mechanism — so Kotlin analyzers get in-module type resolution; `applyVerified` saves on Accept and reverts on Reject.
- The decision (`FixVerifier.decide`) compares **per-ruleKey counts** (line-shift-immune), not fingerprint sets: the target rule must drop by ≥1 and no rule may rise. Document the known acceptable miss (resolve-one/introduce-one of the same rule nets zero) under the conservative-miss bias.

In §9, mark Phase 2b done and note that the **live wire-in** (routing the quick-fix / intention path through `fixVerified` in a coroutine, supplying `baselineForFile` from the current file findings) and the `FixPlanner` + AI review loop remain in **Phase 2c**.

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-05-31-ai-supervised-fix-engine-design.md
git commit -m "docs(spec): Tier-2 verify gate implemented in Phase 2b"
```

---

## Final verification

- [ ] **Run the full fix-engine package + model + a static-analysis smoke**

```bash
./gradlew test --tests "com.ghostdebugger.fix.engine.*" --tests "com.ghostdebugger.model.*"
```
Expected: all green.

- [ ] **Run the full suite once** (catches cross-package regressions, e.g. fingerprint consumers)

```bash
./gradlew test
```
Expected: all green. Pay attention to any test asserting on `Issue.fingerprint()` output — Task 1 preserves its format exactly, so these must remain green.

---

## Self-Review (completed during planning)

- **Spec coverage:** Tier-2 gate (issue-gone + no-new-issues) → Tasks 2,4; transient-document mechanism for Kotlin type resolution → Tasks 4 (commit-before-reanalyze) + 5 (`SingleFileStaticReanalysis` reads live committed doc) + 6 (real end-to-end). Wire-in + AI review explicitly deferred to 2c (Task 7).
- **Type consistency:** `FixVerifier.decide(target, baselineForFile, candidateForFile)`, `VerifyDecision.{Accept,Reject(reason)}`, `applyVerified(plan, virtualFile, project, target, baselineForFile, reanalyze, verifier, edtContext)`, `fixVerified(issue, virtualFile, content, baselineForFile, reanalyze, edtContext)`, `Issue.ruleKey()` — names used identically across tasks. `Tier1Outcome(ok, original)` is private to the applicator.
- **Line-shift correctness:** count-per-ruleKey was chosen specifically because `InsertImport` renumbers lines; the `acceptsWhenUnchangedIssueLineShifts` test guards it.
- **Threading:** EDT writes via `withContext(edtContext)` (default `Dispatchers.Swing`); off-EDT re-analysis between the two write actions; tests inject `Dispatchers.Unconfined` + fake `reanalyze` (deterministic), with one real off-EDT integration test on the default dispatcher.
- **PCE:** `applyVerified` rethrows `ProcessCanceledException` before the `Throwable` catch, per project convention.
- **Placeholders:** none — every code step is complete; the only flagged uncertainty is `CodeFix` field names in Task 5 (implementer verifies against `AnalysisModels.kt`).
