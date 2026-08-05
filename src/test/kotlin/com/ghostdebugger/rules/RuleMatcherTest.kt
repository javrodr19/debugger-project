package com.ghostdebugger.rules

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtFile

class RuleMatcherTest : AegisKotlinAnalysisTestCase() {
    private val pceRule = RuleMatch(
        element = "catch-clause",
        `parameter-type` = "Exception",
        unless = RuleMatch(element = "catch-clause", `contains-text` = "ProcessCanceledException")
    )

    fun testMatchesNonCompliantCatchClause() {
        val file = myFixture.configureByText(
            "Test.kt",
            """
            fun foo() {
                try {
                    doWork()
                } catch (e: Exception) {
                    println("error")
                }
            }
            """.trimIndent()
        ) as KtFile

        ApplicationManager.getApplication().runReadAction {
            val catchClause = PsiTreeUtil.findChildOfType(file, KtCatchClause::class.java)!!
            assertTrue(RuleMatcher.matches(catchClause, pceRule))
        }
    }

    fun testDoesNotMatchWhenUnlessGuardPresent() {
        val file = myFixture.configureByText(
            "Test2.kt",
            """
            fun foo() {
                try {
                    doWork()
                } catch (e: Exception) {
                    if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                    println("error")
                }
            }
            """.trimIndent()
        ) as KtFile

        ApplicationManager.getApplication().runReadAction {
            val catchClause = PsiTreeUtil.findChildOfType(file, KtCatchClause::class.java)!!
            assertFalse(RuleMatcher.matches(catchClause, pceRule))
        }
    }
}
