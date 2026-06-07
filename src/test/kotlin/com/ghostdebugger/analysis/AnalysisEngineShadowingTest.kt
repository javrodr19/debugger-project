package com.ghostdebugger.analysis

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.parser.FileScanner
import com.ghostdebugger.parser.SymbolExtractor
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.runBlocking

class AnalysisEngineShadowingTest : AegisKotlinAnalysisTestCase() {
    // A fresh context per run: finalize() calls dropContent() on parsedFiles, so a context can't be reused.
    private fun freshContext(vf: VirtualFile): AnalysisContext {
        val parsed = runReadAction { FileScanner(project).parsedFiles(listOf(vf)).firstOrNull() }!!
        val extracted = SymbolExtractor(project).extract(parsed)
        return AnalysisContext(graph = InMemoryGraph(), project = project, parsedFiles = listOf(extracted))
    }

    fun testLateAnalyzersRunOnBrokenFileOnlyWhenFlagDisabled() {
        // `val y: Int = "string"` -> AEG-COMPILE-001 (early -> file "broken").
        // `x.length` on String?      -> AEG-NULL-KT-001 (late) — visible only without shadowing.
        val code = "fun run() {\n" +
            "    val y: Int = \"string\"\n" +
            "    val x: String? = null\n" +
            "    println(x.length)\n" +
            "}\n"
        val vf = myFixture.configureByText("A.kt", code).virtualFile

        val shadowed = runBlocking {
            AnalysisEngine().analyzeStaticOnly(freshContext(vf), excludeBrokenFromLate = true)
        }.issues
        val unshadowed = runBlocking {
            AnalysisEngine().analyzeStaticOnly(freshContext(vf), excludeBrokenFromLate = false)
        }.issues

        assertTrue("unshadowed must include the late null-safety issue", unshadowed.any { it.ruleId == "AEG-NULL-KT-001" })
        assertTrue("shadowed must NOT include the late null-safety issue", shadowed.none { it.ruleId == "AEG-NULL-KT-001" })
    }
}
