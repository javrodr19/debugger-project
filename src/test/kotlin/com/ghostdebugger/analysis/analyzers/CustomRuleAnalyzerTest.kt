package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.ParsedFile
import com.ghostdebugger.rules.CustomRuleService
import org.jetbrains.kotlin.psi.KtFile

class CustomRuleAnalyzerTest : AegisKotlinAnalysisTestCase() {
    private val ruleYaml = """
        version: 1
        rules:
          - id: pce-rethrow-missing
            language: kotlin
            severity: WARNING
            message: "catch (e: Exception) must rethrow ProcessCanceledException first"
            match: { element: catch-clause, parameter-type: Exception }
    """.trimIndent()

    fun testEmitsCustomIssueCarryingRuleId() {
        myFixture.tempDirFixture.createFile(".aegis/rules/pce.yml", ruleYaml)
        CustomRuleService.getInstance(project).rules()

        val psiFile = myFixture.configureByText(
            "Foo.kt",
            """
            fun test() {
                try {
                    doStuff()
                } catch (e: Exception) {
                    println("fail")
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
        assertEquals(IssueSource.CUSTOM, issues[0].sources.single())
        assertEquals(IssueType.CUSTOM_RULE, issues[0].type)
        assertEquals("pce-rethrow-missing", issues[0].ruleId)
    }
}
