package com.ghostdebugger

import com.ghostdebugger.fix.FixApplyResult
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

class AnalysisOrchestratorApplyVerifiedFixTest : BasePlatformTestCase() {

    private fun issue(id: String, path: String) = Issue(
        id = id, type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = path, line = 1, ruleId = "AEG-CAST-KT-001"
    )

    fun testThreadsBaselineProviderResultIntoFixVerified() {
        val psi = myFixture.configureByText("A.kt", "fun f(): Int { return 1 }\n")
        val vf = psi.virtualFile
        val here = issue("t", vf.path)

        var receivedBaseline: List<Issue>? = null
        val content = runReadAction { myFixture.getDocument(psi).text }
        val orch = AnalysisOrchestrator.getInstance(project)

        runBlocking {
            orch.applyVerifiedFix(
                here, vf, content,
                baselineProvider = { listOf(here) },   // stubbed single-file baseline
                fixVerified = { _, _, _, baseline ->
                    receivedBaseline = baseline
                    FixApplyResult.Rejected("verification declined (test)")
                },
            ).join()
        }

        assertEquals(listOf(here), receivedBaseline)
    }
}
