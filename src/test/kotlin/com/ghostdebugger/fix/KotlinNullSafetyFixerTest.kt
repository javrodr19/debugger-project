package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.WrapInSafeCall
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KotlinNullSafetyFixerTest : BasePlatformTestCase() {
    private fun issue(line: Int, title: String) = Issue(
        id = "i", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = title, description = "", filePath = "A.kt", line = line, ruleId = "AEG-NULL-KT-001"
    )
    private fun ctxFor(content: String): FixContext {
        val psi = myFixture.configureByText("A.kt", content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testEmitsWrapInSafeCallForFlaggedAccess() {
        val content = "fun f(user: User?) {\n    val n = user.name\n}\n"
        val plan = runReadAction {
            KotlinNullSafetyFixer().generatePlan(issue(2, "Nullable 'user' accessed without a null check"), ctxFor(content))
        }!!
        assertEquals(1, plan.operations.size)
        val op = plan.operations[0]
        assertTrue(op.toString(), op is WrapInSafeCall)
        assertEquals("user", (op as WrapInSafeCall).receiver)
        assertEquals(2, op.line)
    }

    fun testDeclinesWhenNoDotAccessOnLine() {
        val content = "fun f(user: User?) {\n    val n = 1\n}\n"
        assertNull(runReadAction {
            KotlinNullSafetyFixer().generatePlan(issue(2, "Nullable 'user' accessed without a null check"), ctxFor(content))
        })
    }
}
