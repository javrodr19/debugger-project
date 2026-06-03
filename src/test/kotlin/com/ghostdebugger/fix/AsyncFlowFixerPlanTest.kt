package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.AddPromiseCatch
import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.SurroundWithTryCatch
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AsyncFlowFixerPlanTest : BasePlatformTestCase() {
    private fun issue(type: IssueType, line: Int) = Issue(
        id = "i", type = type, severity = IssueSeverity.ERROR, title = "t", description = "",
        filePath = "a.ts", line = line, ruleId = "AEG-ASYNC-001"
    )
    private fun ctxFor(content: String): FixContext {
        val psi = myFixture.configureByText("a.ts", content)
        return FixContext(content) { runReadAction { PsiManager.getInstance(project).findFile(psi.virtualFile) } }
    }

    fun testUnhandledPromiseEmitsAddPromiseCatch() {
        val content = "function f() {\n  doThing().then(handle);\n}\n"
        val plan = runReadAction {
            AsyncFlowFixer().generatePlan(issue(IssueType.UNHANDLED_PROMISE, 2), ctxFor(content))
        }!!
        assertTrue(plan.operations.single() is AddPromiseCatch)
    }

    fun testMissingErrorHandlingEmitsSurroundWithTryCatch() {
        val content = "async function f() {\n  return res.json();\n}\n"
        val plan = runReadAction {
            AsyncFlowFixer().generatePlan(issue(IssueType.MISSING_ERROR_HANDLING, 2), ctxFor(content))
        }!!
        assertTrue(plan.operations.single() is SurroundWithTryCatch)
    }

    fun testMemoryLeakDeclined() {
        val content = "useEffect(() => {\n  setInterval(tick, 1000);\n}, []);\n"
        assertNull(runReadAction {
            AsyncFlowFixer().generatePlan(issue(IssueType.MEMORY_LEAK, 2), ctxFor(content))
        })
    }
}
