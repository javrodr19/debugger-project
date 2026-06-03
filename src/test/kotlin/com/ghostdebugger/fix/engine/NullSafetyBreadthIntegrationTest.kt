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
 * Integration of the Batch-1 null-safety wiring: the registered `KotlinNullSafetyFixer.generatePlan`
 * flows through `FixDeriver.derivePlan` → `FixEngine.planFor` → `applyVerified`, producing and
 * applying a real `WrapInSafeCall` edit (`user.length` → `user?.length`).
 *
 * Scope: this verifies the fixer→op→apply wiring. The Tier-2 re-analysis *verdict* is covered by the
 * 2b/2c-ii-a integration tests, so `reanalyze` returns empty here (target resolved, no regression →
 * accept) to keep this test deterministic. `KotlinNullSafetyFixer` is PSI-only (no Analysis API), so a
 * `BasePlatformTestCase` with `Dispatchers.Unconfined` is sufficient.
 */
class NullSafetyBreadthIntegrationTest : BasePlatformTestCase() {
    fun testRegisteredFixerAppliesSafeCallThroughTheEngine() {
        val code = "fun f(user: String?) { println(user.length) }\n"
        val vf = myFixture.configureByText("A.kt", code).virtualFile
        val content = runReadAction { myFixture.getDocument(myFixture.file).text }
        val target = Issue(
            id = "n1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Nullable 'user' accessed without a null check", description = "",
            filePath = vf.path, line = 1, ruleId = "AEG-NULL-KT-001"
        )

        val result = runBlocking {
            FixEngine(project).fixVerified(
                target, vf, content, baselineForFile = listOf(target),
                reanalyze = { emptyList() },
                edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(myFixture.file).text }.contains("user?.length"))
    }
}
