package com.ghostdebugger.fix.engine

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import kotlinx.coroutines.runBlocking

class SingleFileStaticReanalysisTest : AegisKotlinAnalysisTestCase() {
    fun testReturnsLateRuleIssuesForAFileThatAlsoHasACompileError() {
        // The file has an early compile error AND a late null-safety issue. The hardened single-file
        // pass must surface the late one (the old shadowing dropped it).
        val code = "fun run() {\n" +
            "    val y: Int = \"string\"\n" +
            "    val x: String? = null\n" +
            "    println(x.length)\n" +
            "}\n"
        val vf = myFixture.configureByText("A.kt", code).virtualFile
        val issues = runBlocking { SingleFileStaticReanalysis(project).issuesFor(vf) }
        assertTrue(
            "single-file re-analysis must surface the late null-safety issue on a compile-error file",
            issues.any { it.ruleId == "AEG-NULL-KT-001" }
        )
    }
}
