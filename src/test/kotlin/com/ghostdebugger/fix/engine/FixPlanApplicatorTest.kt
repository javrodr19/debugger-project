package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FixPlanApplicatorTest : BasePlatformTestCase() {

    fun testAppliesAValidPlanAndSaves() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        val start = content.indexOf("return 1")
        val plan = FixPlan("i1", listOf(ReplaceRange(start, start + "return 1".length, "return 2")))

        val result = FixPlanApplicator().apply(plan, vf, project)

        assertTrue(result.toString(), result is FixApplyResult.Success)
        val after = runReadAction { myFixture.getDocument(psi).text }
        assertTrue(after.contains("return 2"))
    }

    fun testRejectsAPlanThatProducesInvalidKotlinAndRevertsTheDocument() {
        val original = "fun f(): Int { return 1 }\n"
        val psi = myFixture.configureByText("A.kt", original)
        val vf = psi.virtualFile
        val content = runReadAction { myFixture.getDocument(psi).text }
        val start = content.indexOf("return 1")
        val plan = FixPlan("i1", listOf(ReplaceRange(start, start + "return 1".length, "return 1 }}}")))

        val result = FixPlanApplicator().apply(plan, vf, project)

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        val after = runReadAction { myFixture.getDocument(psi).text }
        assertEquals(original, after)
    }
}
