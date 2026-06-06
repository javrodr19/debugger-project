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
 * The AEG-CPX-001 acceptance dispatches on whether the candidate added a function: a candidate that
 * adds one is judged by ExtractMethodVerifier; an in-place candidate by B2's ComplexityVerifier.
 */
class FixEngineExtractMethodDispatchTest : BasePlatformTestCase() {
    private fun cpxTarget(path: String) = Issue(
        id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
        title = "High complexity", description = "", filePath = path, line = 1, ruleId = "AEG-CPX-001"
    )

    fun testCandidateThatAddsAFunctionRoutesToExtractMethodGate() {
        // A small function (complexity 2) is under the default threshold (10). An extraction plan adds
        // a function, so the dispatch picks ExtractMethodVerifier, which rejects with its distinctive
        // "not over the threshold" reason — a verdict ComplexityVerifier (file-average) would never give.
        val content = "fun f(a: Boolean) {\n    if (a) g0()\n}\n"
        val vf = myFixture.configureByText("A.kt", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val plan = FixPlan("c1", listOf(
            ReplaceLines(2, 2, "    h()"),
            InsertLinesAfter(3, "fun h() {\n    if (a) g0()\n}")
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

    fun testInPlaceCandidateStillUsesComplexityVerifier() {
        // No function added + complexity unchanged -> ComplexityVerifier rejects "did not decrease".
        val content = "fun f(a: Boolean) {\n    if (a) g0()\n}\n"
        val vf = myFixture.configureByText("B.kt", content).virtualFile
        val text = runReadAction { myFixture.getDocument(myFixture.file).text }
        val start = text.indexOf("g0()")
        val plan = FixPlan("c1", listOf(ReplaceRange(start, start + 4, "k0()")))  // rename, complexity unchanged
        val target = cpxTarget(vf.path)
        val engine = FixEngine(project, derivePlan = { _, _, _ -> plan })

        val result = runBlocking {
            engine.fixVerified(
                target, vf, text, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Rejected)
        assertTrue((result as FixApplyResult.Rejected).reason, result.reason.contains("Complexity did not decrease"))
    }
}
