package com.ghostdebugger.fix.engine

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ExtractMethodIntegrationTest : BasePlatformTestCase() {
    fun testExtractionAppliesAndIsAcceptedAndDecomposesTheSource() {
        val settings = GhostDebuggerSettings.getInstance()
        val originalThreshold = settings.snapshot().maxComplexity
        settings.update { maxComplexity = 2 }
        try {
            // process/3 has three `if`s -> complexity 4 (> threshold 2)
            val code = "fun process(a: Boolean, b: Boolean, c: Boolean) {\n" +
                "    if (a) {\n        println(\"a\")\n    }\n" +
                "    if (b) {\n        println(\"b\")\n    }\n" +
                "    if (c) {\n        println(\"c\")\n    }\n" +
                "}\n"
            val vf = myFixture.configureByText("A.kt", code).virtualFile
            val content = runReadAction { myFixture.getDocument(myFixture.file).text }
            val target = Issue(
                id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
                title = "High complexity: A.kt", description = "", filePath = vf.path, line = 1,
                ruleId = "AEG-CPX-001"
            )
            // extract the `if (c) { … }` block (lines 8-10) into handleC, after process's `}` (line 11)
            val plan = FixPlan("c1", listOf(
                ReplaceLines(8, 10, "    handleC(c)"),
                InsertLinesAfter(11, "fun handleC(c: Boolean) {\n    if (c) {\n        println(\"c\")\n    }\n}")
            ))

            val before = PerFunctionComplexity.measure(project, content).byKey.getValue("process/3")

            val result = runBlocking {
                FixEngine(project, derivePlan = { _, _, _ -> plan }).fixVerified(
                    target, vf, content, baselineForFile = listOf(target),
                    reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
                )
            }

            assertTrue(result.toString(), result is FixApplyResult.Success)
            val after = runReadAction { myFixture.getDocument(myFixture.file).text }
            assertTrue(after, after.contains("handleC(c)"))
            assertTrue(after, after.contains("fun handleC(c: Boolean)"))
            val afterSource = PerFunctionComplexity.measure(project, after).byKey.getValue("process/3")
            assertTrue("source complexity should drop ($before -> $afterSource)", afterSource < before)
        } finally {
            settings.update { maxComplexity = originalThreshold }
        }
    }
}
