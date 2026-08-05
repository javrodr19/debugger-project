package com.ghostdebugger.rules

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.analysis.analyzers.CustomRuleAnalyzer
import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.ParsedFile
import org.jetbrains.kotlin.psi.KtFile

class CustomRuleE2ETest : AegisKotlinAnalysisTestCase() {
    private val ruleYaml = """
        version: 1
        rules:
          - id: pce-rethrow-missing
            language: kotlin
            severity: WARNING
            message: "catch (e: Exception) must rethrow ProcessCanceledException first"
            match: { element: catch-clause, parameter-type: Exception }
    """.trimIndent()

    fun testEndToEndCustomRuleDetection() {
        myFixture.tempDirFixture.createFile(".aegis/rules/pce.yml", ruleYaml)
        CustomRuleService.getInstance(project).rules()

        val psiFile = myFixture.configureByText(
            "Dogfood.kt",
            """
            fun runTask() {
                try {
                    doWork()
                } catch (e: Exception) {
                    println("failed")
                }
            }
            """.trimIndent()
        ) as KtFile

        val parsedFile = ParsedFile(
            virtualFile = psiFile.virtualFile,
            path = psiFile.virtualFile.path,
            extension = "kt",
            content = psiFile.text
        )

        val ctx = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(parsedFile)
        )

        val issues = CustomRuleAnalyzer().analyze(ctx)
        assertEquals(1, issues.size)
        val issue = issues[0]
        assertEquals(IssueType.CUSTOM_RULE, issue.type)
        assertEquals(IssueSource.CUSTOM, issue.sources.single())
        assertEquals("pce-rethrow-missing", issue.ruleId)
    }
}
