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

class JsTsExtractMethodIntegrationTest : BasePlatformTestCase() {
    fun testTsExtractionAppliesAndIsAcceptedAndDecomposesTheSource() {
        val settings = GhostDebuggerSettings.getInstance()
        val originalThreshold = settings.snapshot().maxComplexity
        settings.update { maxComplexity = 2 }
        try {
            // process has three `if`s -> complexity 4 (> threshold 2)
            val code = "function process(a, b, c) {\n" +
                "    if (a) {\n        log(\"a\")\n    }\n" +
                "    if (b) {\n        log(\"b\")\n    }\n" +
                "    if (c) {\n        log(\"c\")\n    }\n" +
                "}\n"
            val vf = myFixture.configureByText("A.ts", code).virtualFile
            val content = runReadAction { myFixture.getDocument(myFixture.file).text }
            val target = Issue(
                id = "c1", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
                title = "High complexity: A.ts", description = "", filePath = vf.path, line = 1,
                ruleId = "AEG-CPX-001"
            )
            // extract the `if (c) { … }` block (lines 8-10) into handleC, after process's `}` (line 11)
            val plan = FixPlan("c1", listOf(
                ReplaceLines(8, 10, "    handleC(c)"),
                InsertLinesAfter(11, "function handleC(c) {\n    if (c) {\n        log(\"c\")\n    }\n}")
            ))

            val before = JsTsPerFunctionComplexity.measure(content).byKey.getValue("process")

            val result = runBlocking {
                FixEngine(project, derivePlan = { _, _, _ -> plan }).fixVerified(
                    target, vf, content, baselineForFile = listOf(target),
                    reanalyze = { emptyList() }, edtContext = Dispatchers.Unconfined,
                )
            }

            assertTrue(result.toString(), result is FixApplyResult.Success)
            val after = runReadAction { myFixture.getDocument(myFixture.file).text }
            assertTrue(after, after.contains("handleC(c)"))
            assertTrue(after, after.contains("function handleC(c)"))
            val afterSource = JsTsPerFunctionComplexity.measure(after).byKey.getValue("process")
            assertTrue("source complexity should drop ($before -> $afterSource)", afterSource < before)
        } finally {
            settings.update { maxComplexity = originalThreshold }
        }
    }
}
