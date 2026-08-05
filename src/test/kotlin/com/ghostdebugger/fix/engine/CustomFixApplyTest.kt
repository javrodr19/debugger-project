package com.ghostdebugger.fix.engine

import com.ghostdebugger.AegisKotlinAnalysisTestCase
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

        val catchClause = PsiTreeUtil.findChildOfType(file, KtCatchClause::class.java)!!
        val offset = RuleAnchorResolver.resolveAnchorOffset(catchClause, "catch-block-open-brace")
        assertNotNull(offset)
        assertTrue(offset!! > catchClause.textOffset)
    }

    fun testValidFixPlanApplicationPassesVerifier() {
        val file = myFixture.configureByText(
            "Valid.kt",
            """
            fun foo() { val x = 1 }
            """.trimIndent()
        ) as KtFile

        val verifier = FixVerifier()
        val isValid = verifier.verify(file, file.text)
        assertTrue(isValid)
    }

    fun testInvalidFixPlanRefusedByVerifier() {
        val file = myFixture.configureByText(
            "Invalid.kt",
            """
            fun foo() { val x = 1 }
            """.trimIndent()
        ) as KtFile

        val verifier = FixVerifier()
        val isValid = verifier.verify(file, "fun foo() { val x = {{{")
        assertFalse(isValid)
    }
}
