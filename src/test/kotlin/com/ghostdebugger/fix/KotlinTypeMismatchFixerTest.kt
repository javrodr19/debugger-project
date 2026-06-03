package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.ReplaceRange
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KotlinTypeMismatchFixerTest : BasePlatformTestCase() {
    private fun issue(line: Int, declared: String, actual: String) = Issue(
        id = "i", type = IssueType.COMPILATION_ERROR, severity = IssueSeverity.ERROR,
        title = "Type mismatch on 'y'",
        description = "Declared type is not assignable from the initializer's type. Declared: $declared. Initializer: $actual.",
        filePath = "A.kt", line = line, ruleId = "AEG-TYPE-KT-001"
    )
    private fun ctxFor(content: String): FixContext {
        val psi = myFixture.configureByText("A.kt", content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testEmitsWideningConversionForIntToLong() {
        val content = "fun f(n: Int) {\n    val y: Long = n\n}\n"
        val plan = runReadAction { KotlinTypeMismatchFixer().generatePlan(issue(2, "Long", "Int"), ctxFor(content)) }!!
        val op = plan.operations.single()
        // The fixer uses ReplaceRange with exact PSI offsets to avoid ambiguous token matching
        // (e.g. the 'n' in 'Long' appearing before the initializer 'n' on the same line).
        assertTrue(op.toString(), op is ReplaceRange)
        op as ReplaceRange
        assertEquals("n.toLong()", op.text)
    }

    fun testHandlesFullyQualifiedTypeNames() {
        val content = "fun f(n: Int) {\n    val y: Long = n\n}\n"
        val plan = runReadAction { KotlinTypeMismatchFixer().generatePlan(issue(2, "kotlin.Long", "kotlin.Int"), ctxFor(content)) }!!
        val op = plan.operations.single() as ReplaceRange
        assertEquals("n.toLong()", op.text)
    }

    fun testDeclinesNonWideningMismatch() {
        val content = "fun f(n: Int) {\n    val y: String = n\n}\n"
        assertNull(runReadAction { KotlinTypeMismatchFixer().generatePlan(issue(2, "String", "Int"), ctxFor(content)) })
    }
}
