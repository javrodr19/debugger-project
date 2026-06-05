package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.CollapseBooleanReturn
import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ComplexitySimplifierFixerTest : BasePlatformTestCase() {
    private fun cpxIssue() = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = "A.kt", line = 1, ruleId = "AEG-CPX-001"
    )
    private fun ctxFor(content: String): FixContext {
        val psi = myFixture.configureByText("A.kt", content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testEmitsOneCollapsePerSite() {
        val content = "fun f(a: Boolean, b: Boolean) {\n" +
            "    if (a) return true else return false\n" +
            "    if (b) return false else return true\n" +
            "}\n"
        val plan = runReadAction { ComplexitySimplifierFixer().generatePlan(cpxIssue(), ctxFor(content)) }!!
        assertEquals(2, plan.operations.size)
        assertEquals(listOf(2, 3), plan.operations.map { (it as CollapseBooleanReturn).line })
    }

    fun testDeclinesWhenNoCollapsibleSite() {
        val content = "fun f(a: Boolean): Int {\n    if (a) return 1 else return 2\n}\n"
        assertNull(runReadAction { ComplexitySimplifierFixer().generatePlan(cpxIssue(), ctxFor(content)) })
    }

    fun testGenerateFixReturnsNull() {
        // op-only fixer (mirrors KotlinNullSafetyFixer): the legacy CodeFix path is unused.
        assertNull(ComplexitySimplifierFixer().generateFix(cpxIssue(), "whatever"))
    }
}
