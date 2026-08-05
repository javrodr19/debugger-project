package com.ghostdebugger.fix.engine

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtFile

class CustomFixApplyTest : AegisKotlinAnalysisTestCase() {

    fun testResolveAnchorOffsetForCatchClause() {
        val file = myFixture.configureByText(
            "Catch.kt",
            """
            fun test() {
                try { work() } catch (e: Exception) { println("err") }
            }
            """.trimIndent()
        ) as KtFile

        ApplicationManager.getApplication().runReadAction {
            val catchClause = PsiTreeUtil.findChildOfType(file, KtCatchClause::class.java)!!
            val offset = RuleAnchorResolver.resolveAnchorOffset(catchClause, "catch-block-open-brace")
            assertNotNull(offset)
            assertTrue(offset!! > catchClause.textOffset)
        }
    }

    fun testFixVerifierDecidesAcceptOnResolvedTarget() {
        val target = Issue(
            id = "test-1",
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.ERROR,
            title = "Null Safety",
            description = "Null warning",
            filePath = "Test.kt",
            ruleId = "AEG-NULL-001"
        )

        val baseline = listOf(target)
        val candidate = emptyList<Issue>()

        val verifier = FixVerifier()
        val decision = verifier.decide(target, baseline, candidate)
        assertTrue(decision is VerifyDecision.Accept)
    }

    fun testFixVerifierDecidesRejectOnRegression() {
        val target = Issue(
            id = "test-1",
            type = IssueType.NULL_SAFETY,
            severity = IssueSeverity.ERROR,
            title = "Null Safety",
            description = "Null warning",
            filePath = "Test.kt",
            ruleId = "AEG-NULL-001"
        )
        val newIssue = Issue(
            id = "test-2",
            type = IssueType.SYNTAX_ERROR,
            severity = IssueSeverity.ERROR,
            title = "Syntax error",
            description = "Syntax error",
            filePath = "Test.kt",
            ruleId = "AEG-SYNTAX-001"
        )

        val baseline = listOf(target)
        val candidate = listOf(newIssue)

        val verifier = FixVerifier()
        val decision = verifier.decide(target, baseline, candidate)
        assertTrue(decision is VerifyDecision.Reject)
    }
}
