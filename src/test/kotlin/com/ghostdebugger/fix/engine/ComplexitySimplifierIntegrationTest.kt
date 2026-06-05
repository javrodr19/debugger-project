package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.graph.GraphBuilder
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ComplexitySimplifierIntegrationTest : BasePlatformTestCase() {
    fun testRegisteredFixerSimplifiesThroughTheEngineAndLowersComplexity() {
        val code = "fun process(a: Boolean, b: Boolean, items: List<Int>): Boolean {\n" +
            "    for (i in items) {\n" +
            "        if (i < 0) {\n" +
            "            println(i)\n" +
            "        }\n" +
            "    }\n" +
            "    if (a && b) {\n" +
            "        return true\n" +
            "    } else {\n" +
            "        return false\n" +
            "    }\n" +
            "}\n"
        val vf = myFixture.configureByText("A.kt", code).virtualFile
        val content = runReadAction { myFixture.getDocument(myFixture.file).text }
        val target = Issue(
            id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
            title = "High complexity: A.kt", description = "", filePath = vf.path, line = 1,
            ruleId = "AEG-CPX-001"
        )

        val before = GraphBuilder().estimateComplexity(content, 1)

        val result = runBlocking {
            FixEngine(project).fixVerified(
                target, vf, content, baselineForFile = listOf(target),
                reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
            )
        }

        assertTrue(result.toString(), result is FixApplyResult.Success)
        val after = runReadAction { myFixture.getDocument(myFixture.file).text }
        assertTrue(after, after.contains("return a && b"))
        assertFalse(after, after.contains("if (a && b)"))
        // the recomputed metric strictly dropped (the acceptance gate's guarantee, re-asserted here)
        assertTrue(GraphBuilder().estimateComplexity(after, 1) < before)
    }
}
