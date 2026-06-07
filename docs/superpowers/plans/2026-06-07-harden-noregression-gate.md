# Harden the No-Regression Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Tier-2 no-regression gate real — single-file re-analysis runs the late analyzers even on files with compile errors, and both production fix entry points compute the baseline the same way — proven by tests that exercise the gate with real (non-stubbed) analysis.

**Architecture:** Add `excludeBrokenFromLate` (default `true`) to `AnalysisEngine.analyzeStaticOnly`; `SingleFileStaticReanalysis` calls it with `false` so the gate sees late-rule issues on broken-but-parse-clean files. `AnalysisOrchestrator.applyVerifiedFix` (and `UIEventRouter`'s apply-fix handler) compute the baseline via that same single-file pass on the original, replacing the shadowed `currentIssues` baseline. The verifiers and `applyVerified` are untouched.

**Tech Stack:** Kotlin 2.0.21, IntelliJ Platform Analysis API (K2), kotlinx-coroutines, JUnit4 + `AegisKotlinAnalysisTestCase`/`BasePlatformTestCase`.

**Spec:** `docs/superpowers/specs/2026-06-07-harden-noregression-gate-design.md`.

---

## Prerequisites (test prelude)

Tests need a JetBrains Runtime; the shell env does **not** persist between Bash calls. Prefix **every** gradlew invocation inline:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "..."
```

Append `; echo EXIT=${PIPESTATUS[0]}`; treat any `BUILD FAILED`/non-zero `EXIT` as failure regardless of tail.

**Analysis-API tests run off the EDT.** Tasks 1, 2, 4 use real Kotlin analyzers, so their test classes extend `AegisKotlinAnalysisTestCase` (Kotlin stdlib + `runInDispatchThread() = false`), not `BasePlatformTestCase`.

## Existing-code anchors (read before starting)

- `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt:79-103` — `doStaticPasses`; lines 91-95 build `brokenFilePaths`/`filteredFiles` and exclude broken files before the late pass. `analyzeStaticOnly` at 67-77 (calls `doStaticPasses`); `analyze` at 54-64 (also calls it; must keep default behavior). `StaticPassResult`'s 4th field `filteredContext` is consumed by `analyze`'s AI pass (line 59).
- `src/main/kotlin/com/ghostdebugger/fix/engine/SingleFileStaticReanalysis.kt:37` — `engineFactory().analyzeStaticOnly(ctx).issues`. Setup (lines 27-35): `FileScanner(project).parsedFiles(...)` in a read action, then `SymbolExtractor(project).extract(parsed)`, then `AnalysisContext(graph = InMemoryGraph(), project, parsedFiles = [extracted])`.
- `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt:56` — `internal fun baselineFor(issues, filePath)`; `:499-520` — `applyVerifiedFix` (line 507 computes the baseline). The `scope.launch` body is off-EDT (its `fixVerified` already calls `reanalyze`/`issuesFor`).
- `src/main/kotlin/com/ghostdebugger/UIEventRouter.kt:288-291` — the other `fixVerified` caller; line 290 computes `baselineFor(svc.currentIssues, vf.path)`, line 291 calls `FixEngine(project).fixVerified(issue, vf, content, baseline)`. `vf` is a `VirtualFile`. Also off-EDT (same reason).
- `src/test/kotlin/com/ghostdebugger/BaselineForTest.kt` — the only remaining `baselineFor` caller after this change; removed with the function.
- `src/test/kotlin/com/ghostdebugger/AnalysisOrchestratorApplyVerifiedFixTest.kt` — asserts the baseline passed to `fixVerified`; updated in Task 3.
- `src/test/kotlin/com/ghostdebugger/fix/engine/FixVerifyKotlinIntegrationTest.kt` — the **model** for Task 4: `AegisKotlinAnalysisTestCase` + real `SingleFileStaticReanalysis` baseline + `applyVerified` with real `reanalyze` (the *accept* case; Task 4 is the *reject* counterpart).
- Proven fixtures: `val x: Int = "string"` → `AEG-COMPILE-001` (`CompilationErrorAnalyzerTest`); `val x: String? = null; println(x.length)` → `AEG-NULL-KT-001` (`KotlinNullSafetyAnalyzerTest`).

## File structure

- **Modify** `AnalysisEngine.kt` (flag), `SingleFileStaticReanalysis.kt` (call with `false`), `AnalysisOrchestrator.kt` (`baselineProvider` seam, remove `baselineFor`), `UIEventRouter.kt` (baseline source).
- **Delete** `src/test/kotlin/com/ghostdebugger/BaselineForTest.kt`.
- **Create** `AnalysisEngineShadowingTest`, `SingleFileStaticReanalysisTest`, `FixVerifyRegressionRejectTest`; **modify** `AnalysisOrchestratorApplyVerifiedFixTest`.

---

### Task 1: `excludeBrokenFromLate` flag on `AnalysisEngine`

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt`
- Test: `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineShadowingTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineShadowingTest.kt`:

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.parser.FileScanner
import com.ghostdebugger.parser.SymbolExtractor
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.runBlocking

class AnalysisEngineShadowingTest : AegisKotlinAnalysisTestCase() {
    // A fresh context per run: finalize() calls dropContent() on parsedFiles, so a context can't be reused.
    private fun freshContext(vf: VirtualFile): AnalysisContext {
        val parsed = runReadAction { FileScanner(project).parsedFiles(listOf(vf)).firstOrNull() }!!
        val extracted = SymbolExtractor(project).extract(parsed)
        return AnalysisContext(graph = InMemoryGraph(), project = project, parsedFiles = listOf(extracted))
    }

    fun testLateAnalyzersRunOnBrokenFileOnlyWhenFlagDisabled() {
        // `val y: Int = "string"` -> AEG-COMPILE-001 (early -> file "broken").
        // `x.length` on String?      -> AEG-NULL-KT-001 (late) — visible only without shadowing.
        val code = "fun run() {\n" +
            "    val y: Int = \"string\"\n" +
            "    val x: String? = null\n" +
            "    println(x.length)\n" +
            "}\n"
        val vf = myFixture.configureByText("A.kt", code).virtualFile

        val shadowed = runBlocking {
            AnalysisEngine().analyzeStaticOnly(freshContext(vf), excludeBrokenFromLate = true)
        }.issues
        val unshadowed = runBlocking {
            AnalysisEngine().analyzeStaticOnly(freshContext(vf), excludeBrokenFromLate = false)
        }.issues

        assertTrue("unshadowed must include the late null-safety issue", unshadowed.any { it.ruleId == "AEG-NULL-KT-001" })
        assertTrue("shadowed must NOT include the late null-safety issue", shadowed.none { it.ruleId == "AEG-NULL-KT-001" })
    }
}
```

- [ ] **Step 2: Run → FAIL** (`analyzeStaticOnly` has no `excludeBrokenFromLate` parameter → compile error).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.analysis.AnalysisEngineShadowingTest"`

- [ ] **Step 3: Add the flag in `AnalysisEngine.kt`**

3a. Change `analyzeStaticOnly`'s signature (line 67) to add the parameter and pass it through:

```kotlin
    suspend fun analyzeStaticOnly(
        context: AnalysisContext,
        indicator: ProgressIndicator? = null,
        excludeBrokenFromLate: Boolean = true,
    ): AnalysisResult {
        val settings = settingsProvider()
        val staticResult = doStaticPasses(context, settings, indicator, excludeBrokenFromLate)
        val merged = mergeIssues(staticResult.earlyIssues + staticResult.lateIssues)
        val engineStatus = EngineStatusPayload(
            provider = "STATIC",
            status = EngineStatus.DISABLED,
            message = "Static-only re-analysis (dependent cascade)."
        )
        return finalize(merged, staticResult.limitedContext, engineStatus)
    }
```

3b. Change `doStaticPasses` (lines 79-103) to honor the flag:

```kotlin
    private suspend fun doStaticPasses(
        context: AnalysisContext,
        settings: GhostDebuggerSettings.State,
        indicator: ProgressIndicator?,
        excludeBrokenFromLate: Boolean = true
    ): StaticPassResult {
        val limitedContext = context.limitTo(settings.maxFilesToAnalyze)

        indicator?.text = "Checking for syntax and compilation errors..."
        val earlyAnalyzers = analyzers.filterIsInstance<EarlyAnalyzer>()
        val earlyIssues = runStaticPass(earlyAnalyzers, limitedContext, indicator)
        indicator?.checkCanceled()

        val lateContext = if (excludeBrokenFromLate) {
            val brokenFilePaths = earlyIssues.map { it.filePath.replace("\\", "/") }.toSet()
            limitedContext.copy(parsedFiles = limitedContext.parsedFiles.filterNot {
                it.path.replace("\\", "/") in brokenFilePaths
            })
        } else {
            limitedContext
        }

        indicator?.text = "Running static analysis..."
        val lateAnalyzers = analyzers.filterNot { it is EarlyAnalyzer }
        val lateIssues = runStaticPass(lateAnalyzers, lateContext, indicator)
        indicator?.checkCanceled()

        return StaticPassResult(earlyIssues, lateIssues, limitedContext, lateContext)
    }
```

(`analyze` at line 54 calls `doStaticPasses(context, settings, indicator)` with no flag → default `true` → its AI pass still runs on the non-broken `lateContext`, exactly as before. The 4th `StaticPassResult` field is now named `lateContext`; it equals the old `filteredContext` when the flag is `true`.)

- [ ] **Step 4: Run → PASS**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.analysis.AnalysisEngineShadowingTest"`

Expected: green. If `unshadowed` lacks `AEG-NULL-KT-001` (harness JDK finickiness — the null-safety analyzer didn't fire with the unrelated type error present), fall back to a late rule that needs no type resolution: replace the body with a redundant-let — `val x: String? = null\n    x?.let { println(it) }` is **not** flagged, so use `x?.let { it.length }` and assert on `AEG-LET-KT-001` instead (a structural late rule). Keep the `val y: Int = "string"` line as the guaranteed early issue. Report the substitution.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt \
        src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineShadowingTest.kt
git commit -m "feat(analysis): excludeBrokenFromLate flag so single-file re-analysis can see late rules"
```

---

### Task 2: `SingleFileStaticReanalysis` runs the non-shadowing pass

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/fix/engine/SingleFileStaticReanalysis.kt`
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/SingleFileStaticReanalysisTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/ghostdebugger/fix/engine/SingleFileStaticReanalysisTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import kotlinx.coroutines.runBlocking

class SingleFileStaticReanalysisTest : AegisKotlinAnalysisTestCase() {
    fun testReturnsLateRuleIssuesForAFileThatAlsoHasACompileError() {
        // The file has an early compile error AND a late null-safety issue. The hardened single-file
        // pass must surface the late one (the old shadowing dropped it).
        val code = "fun run() {\n" +
            "    val y: Int = \"string\"\n" +
            "    val x: String? = null\n" +
            "    println(x.length)\n" +
            "}\n"
        val vf = myFixture.configureByText("A.kt", code).virtualFile
        val issues = runBlocking { SingleFileStaticReanalysis(project).issuesFor(vf) }
        assertTrue(
            "single-file re-analysis must surface the late null-safety issue on a compile-error file",
            issues.any { it.ruleId == "AEG-NULL-KT-001" }
        )
    }
}
```

- [ ] **Step 2: Run → FAIL** (today `issuesFor` shadows the late pass → no `AEG-NULL-KT-001`).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.SingleFileStaticReanalysisTest"`

- [ ] **Step 3: Pass the flag in `SingleFileStaticReanalysis.kt`**

Change line 37 from:
```kotlin
        return engineFactory().analyzeStaticOnly(ctx).issues
```
to:
```kotlin
        return engineFactory().analyzeStaticOnly(ctx, excludeBrokenFromLate = false).issues
```

(Update the class KDoc's "run the static-only analysis pass" sentence to note it is the non-shadowing pass, so the gate sees late rules even on files with compile errors.)

- [ ] **Step 4: Run → PASS**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.SingleFileStaticReanalysisTest" --tests "com.ghostdebugger.fix.engine.FixVerifyKotlinIntegrationTest"`

Expected: green. `FixVerifyKotlinIntegrationTest` (its sample file has no compile error, so shadowing never applied) is unaffected. If Task 1 used the redundant-let fallback, assert `AEG-LET-KT-001` here with the same fixture.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/fix/engine/SingleFileStaticReanalysis.kt \
        src/test/kotlin/com/ghostdebugger/fix/engine/SingleFileStaticReanalysisTest.kt
git commit -m "feat(fix-engine): single-file re-analysis runs the non-shadowing late pass for the gate"
```

---

### Task 3: Consistent baseline at both fix entry points

**Files:**
- Modify: `src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt`
- Modify: `src/main/kotlin/com/ghostdebugger/UIEventRouter.kt`
- Delete: `src/test/kotlin/com/ghostdebugger/BaselineForTest.kt`
- Modify: `src/test/kotlin/com/ghostdebugger/AnalysisOrchestratorApplyVerifiedFixTest.kt`

- [ ] **Step 1: Update the orchestrator test to the new seam**

Replace the body of `AnalysisOrchestratorApplyVerifiedFixTest` with (it now verifies the orchestrator threads `baselineProvider`'s result into `fixVerified`):

```kotlin
package com.ghostdebugger

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

class AnalysisOrchestratorApplyVerifiedFixTest : BasePlatformTestCase() {

    private fun issue(id: String, path: String) = Issue(
        id = id, type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = path, line = 1, ruleId = "AEG-CAST-KT-001"
    )

    fun testThreadsBaselineProviderResultIntoFixVerified() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val here = issue("t", vf.path)

        var receivedBaseline: List<Issue>? = null
        val content = runReadAction { myFixture.getDocument(psi).text }
        val orch = AnalysisOrchestrator.getInstance(project)

        runBlocking {
            orch.applyVerifiedFix(
                here, vf, content,
                baselineProvider = { listOf(here) },   // stubbed single-file baseline
                fixVerified = { _, _, _, baseline ->
                    receivedBaseline = baseline
                    FixApplyResult.Rejected("verification declined (test)")
                },
            ).join()
        }

        assertEquals(listOf(here), receivedBaseline)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`applyVerifiedFix` has no `baselineProvider` parameter → compile error).

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.AnalysisOrchestratorApplyVerifiedFixTest"`

- [ ] **Step 3: Add the `baselineProvider` seam in `AnalysisOrchestrator.kt`**

Change `applyVerifiedFix` (lines 499-507): add the parameter and use it. The new signature + baseline line:

```kotlin
    internal fun applyVerifiedFix(
        issue: Issue,
        virtualFile: VirtualFile,
        content: String,
        baselineProvider: suspend (VirtualFile) -> List<Issue> =
            { com.ghostdebugger.fix.engine.SingleFileStaticReanalysis(project).issuesFor(it) },
        fixVerified: suspend (Issue, VirtualFile, String, List<Issue>) -> FixApplyResult =
            { i, v, c, b -> FixEngine(project).fixSupervised(i, v, c, b, resolveAiService()) },
    ): Job = scope.launch {
        try {
            val baseline = baselineProvider(virtualFile)
```

(The rest of the method body — the `when (val result = fixVerified(...))` block — is unchanged.)

- [ ] **Step 4: Update `UIEventRouter.kt` (the other fix entry point)**

Change line 290 from:
```kotlin
                val baseline = baselineFor(svc.currentIssues, vf.path)
```
to:
```kotlin
                val baseline = com.ghostdebugger.fix.engine.SingleFileStaticReanalysis(project).issuesFor(vf)
```

(`vf` is a `VirtualFile`; this `scope.launch` body is off-EDT — its `fixVerified` already calls `issuesFor`. `issuesFor` is `suspend`, callable here.)

- [ ] **Step 5: Remove the now-unused `baselineFor`**

Delete the `internal fun baselineFor(...)` function in `AnalysisOrchestrator.kt` (around line 56 — the whole function), and delete its test file:

```bash
git rm src/test/kotlin/com/ghostdebugger/BaselineForTest.kt
```

- [ ] **Step 6: Run → PASS** (orchestrator test + a broad compile check that nothing else referenced `baselineFor`):

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.AnalysisOrchestratorApplyVerifiedFixTest" --tests "com.ghostdebugger.UIEventRouter*"`

Expected: green, and compilation succeeds (proving no dangling `baselineFor` reference). If compilation fails with "unresolved reference: baselineFor", grep for the straggler and convert it to `SingleFileStaticReanalysis(project).issuesFor(vf)` (off-EDT call sites only) or restore the function if a non-gate caller exists.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/ghostdebugger/AnalysisOrchestrator.kt \
        src/main/kotlin/com/ghostdebugger/UIEventRouter.kt \
        src/test/kotlin/com/ghostdebugger/AnalysisOrchestratorApplyVerifiedFixTest.kt
git rm src/test/kotlin/com/ghostdebugger/BaselineForTest.kt
git commit -m "feat(fix-engine): both fix entry points use the single-file baseline (drop shadowed baselineFor)"
```

---

### Task 4: Gate rejects a real regression (end-to-end, real re-analysis)

**Files:**
- Test: `src/test/kotlin/com/ghostdebugger/fix/engine/FixVerifyRegressionRejectTest.kt`

- [ ] **Step 1: Write the test** (the *reject* counterpart to `FixVerifyKotlinIntegrationTest`'s *accept*)

`src/test/kotlin/com/ghostdebugger/fix/engine/FixVerifyRegressionRejectTest.kt`:

```kotlin
package com.ghostdebugger.fix.engine

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile

/**
 * End-to-end Tier-2 with REAL single-file re-analysis (no stub): a fix that introduces a regression is
 * rejected and the document reverted. Off the EDT (AegisKotlinAnalysisTestCase) so the Analysis API is legal.
 */
class FixVerifyRegressionRejectTest : AegisKotlinAnalysisTestCase() {
    fun testGateRejectsAndRevertsAFixThatIntroducesANewIssue() {
        val code = "fun f(x: String?): Int { return x?.length ?: 0 }\n"  // clean: safe call, no issues
        val psi = myFixture.configureByText("A.kt", code) as KtFile
        val vf = psi.virtualFile

        val baseline = runBlocking { SingleFileStaticReanalysis(project).issuesFor(vf) }  // real, clean

        // "Fix" that turns the safe access into an unsafe one -> introduces a new issue.
        val plan = FixPlan("t", listOf(ReplaceExpression(1, "x?.length ?: 0", "x.length")))
        val target = Issue(
            id = "t", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "t", description = "", filePath = vf.path, line = 1, ruleId = "AEG-NULL-KT-001"
        )

        val result = runBlocking {
            FixPlanApplicator().applyVerified(
                plan, vf, project, target,
                baselineForFile = baseline,
                reanalyze = { SingleFileStaticReanalysis(project).issuesFor(vf) },  // REAL
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertEquals("document must be reverted on reject", code, runReadAction { myFixture.getDocument(psi).text })
    }
}
```

- [ ] **Step 2: Run → PASS**

Run: `JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test --tests "com.ghostdebugger.fix.engine.FixVerifyRegressionRejectTest"`

Expected: green. The candidate `return x.length` is parse-clean (passes Tier-1), and real re-analysis surfaces a new issue (`AEG-NULL-KT-001` and/or `AEG-COMPILE-001`) absent from the clean baseline → `FixVerifier` rejects → `applyVerified` reverts. If it returns `Success`, the candidate produced no new issue — verify `ReplaceExpression` actually rewrote line 1 to `return x.length` (log the post-apply text before revert by temporarily asserting it), and that re-analysis detects the nullable access.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/ghostdebugger/fix/engine/FixVerifyRegressionRejectTest.kt
git commit -m "test(fix-engine): gate rejects + reverts a real regression via real re-analysis"
```

---

## Final verification

- [ ] Targeted suites green:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test \
  --tests "com.ghostdebugger.analysis.*" --tests "com.ghostdebugger.fix.engine.*" --tests "com.ghostdebugger.AnalysisOrchestrator*"
```

- [ ] Full suite green with real exit status:

```bash
JAVA_HOME="$(find ~/.gradle/caches -path '*ideaIC-2024.3.2*/jbr' -type d | head -1)" ./gradlew test 2>&1 | tail -8; echo EXIT=${PIPESTATUS[0]}
```

Expected: `BUILD SUCCESSFUL`, `EXIT=0`. (If the pipe hides a failure, inspect `build/test-results/test/*.xml` for `<failure>`.)

## Self-Review (completed during planning)

**1. Spec coverage**
- §3.1 non-shadowing pass (`excludeBrokenFromLate`, default `true` → pipeline unchanged; `SingleFileStaticReanalysis` uses `false`) → Tasks 1-2.
- §3.2 consistent baseline at **both** entry points (`applyVerifiedFix` `baselineProvider` + `UIEventRouter`) and `baselineFor`/`BaselineForTest` removal → Task 3.
- §3.3 verifiers/`applyVerified` untouched → confirmed (no task modifies them; Task 4 calls the existing `applyVerified`).
- §4/§5 verification + tests (un-shadowing at engine and wrapper layers; gate-rejects-real-regression e2e; orchestrator seam) → Tasks 1-4.
- §7 graph rules out of scope (empty graph) — unchanged; not addressed (correct).

**2. Placeholder scan** — none. Every step has complete code or an exact edit + the exact `--tests` command. The two harness-dependent tests (1, 2) carry a concrete fixture **and** a concrete fallback (redundant-let / `AEG-LET-KT-001`) — a bounded contingency for analyzer finickiness, not a TODO. The assertions are rule-presence comparisons, robust to spurious extra issues.

**3. Type consistency**
- `analyzeStaticOnly(context, indicator, excludeBrokenFromLate = true)` and `doStaticPasses(…, excludeBrokenFromLate = true)` — same name/default; `analyze` keeps the no-flag call (default `true`).
- `SingleFileStaticReanalysis` calls `analyzeStaticOnly(ctx, excludeBrokenFromLate = false)`.
- `applyVerifiedFix(issue, vf, content, baselineProvider = …, fixVerified = …)` — `baselineProvider: suspend (VirtualFile) -> List<Issue>`; the existing `fixVerified` named-arg call sites still bind correctly (both params have defaults).
- `baselineFor` removed; only callers were `AnalysisOrchestrator:507`, `UIEventRouter:290` (both changed) and `BaselineForTest` (deleted).
- Task 4 uses `FixPlanApplicator().applyVerified(plan, vf, project, target, baselineForFile, reanalyze)` — the existing signature (default `verifier`/`acceptance`/`edtContext`), matching `FixVerifyKotlinIntegrationTest`.
