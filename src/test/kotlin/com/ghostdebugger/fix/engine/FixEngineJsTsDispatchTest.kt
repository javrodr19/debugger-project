package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class FixEngineJsTsDispatchTest : BasePlatformTestCase() {
    private fun cpxTarget(path: String) = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = path, line = 1, ruleId = "AEG-CPX-001"
    )

    fun testTsExtractionRoutesToJsTsGate() {
        // A small .ts function (complexity 2) is under the default threshold (10). The extraction adds a
        // function, so the JS/TS gate runs and rejects with "threshold" — proving the JS/TS measurer ran
        // (the Kotlin measurer would parse .ts as Kotlin -> empty map -> a different "add exactly one"/"no
        // source" reason).
        val content = "function f(a) {\n    if (a) g0()\n}\n"
        val vf = myFixture.configureByText("A.ts", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val plan = FixPlan("c1", listOf(
            ReplaceLines(2, 2, "    h(a)"),
            InsertLinesAfter(3, "function h(a) {\n    if (a) g0()\n}")
        ))
        val target = cpxTarget(vf.path)
        val engine = FixEngine(project, derivePlan = { _, _, _ -> plan })

        val result = runBlocking {
            engine.fixVerified(
                target, vf, text, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertTrue((result as FixApplyResult.Rejected).reason, result.reason.contains("threshold"))
    }

    fun testTsCandidateWithUnbalancedDelimitersIsRejected() {
        // The plan inserts an unbalanced `function h() {` (no closing brace) -> balance check rejects.
        val content = "function f(a) {\n    if (a) g0()\n}\n"
        val vf = myFixture.configureByText("B.ts", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val plan = FixPlan("c1", listOf(InsertLinesAfter(3, "function h() {")))
        val target = cpxTarget(vf.path)
        val engine = FixEngine(project, derivePlan = { _, _, _ -> plan })

        val result = runBlocking {
            engine.fixVerified(
                target, vf, text, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertTrue((result as FixApplyResult.Rejected).reason, result.reason.contains("unbalanced"))
    }
}
