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
