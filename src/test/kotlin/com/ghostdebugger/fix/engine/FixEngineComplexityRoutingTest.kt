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
