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
