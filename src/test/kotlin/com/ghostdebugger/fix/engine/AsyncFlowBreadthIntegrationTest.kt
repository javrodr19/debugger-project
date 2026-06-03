package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Integration: registered AsyncFlowFixer.generatePlan → AddPromiseCatch → applyVerified applies the
 * `.catch(...)` edit. reanalyze is stubbed (gate verdict covered by 2b/2c-ii-a); content-based op, so
 * BasePlatformTestCase + Unconfined is sufficient.
 */
class AsyncFlowBreadthIntegrationTest : BasePlatformTestCase() {
    fun testRegisteredFixerAddsCatchThroughTheEngine() {
        val code = "function f() {\n  doThing().then(handle);\n}\n"
        val vf = myFixture.configureByText("a.ts", code).virtualFile
        val content = runReadAction { myFixture.getDocument(myFixture.file).text }
        val target = Issue(
            id = "p1", type = IssueType.UNHANDLED_PROMISE, severity = IssueSeverity.ERROR,
            title = "Unhandled promise rejection", description = "",
            filePath = vf.path, line = 2, ruleId = "AEG-ASYNC-001"
        )
        val result = runBlocking {
            FixEngine(project).fixVerified(
                target, vf, content, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }
        assertTrue(result.toString(), result is FixApplyResult.Success)
        assertTrue(runReadAction { myFixture.getDocument(myFixture.file).text }.contains(".catch(console.error);"))
    }
}
