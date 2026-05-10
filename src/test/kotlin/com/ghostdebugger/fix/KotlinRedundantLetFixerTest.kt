package com.ghostdebugger.fix

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.analysis.analyzers.KotlinRedundantLetAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile

class KotlinRedundantLetFixerTest : AegisKotlinAnalysisTestCase() {

    private fun applyFix(source: String): String? {
        val vf = myFixture.configureByText("Sample.kt", source).virtualFile
        val pf = com.ghostdebugger.model.ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "kt",
            content = source
        )
        val ctx = com.ghostdebugger.model.AnalysisContext(
            graph = com.ghostdebugger.graph.InMemoryGraph(),
            project = project,
            parsedFiles = listOf(pf)
        )
        val issue = KotlinRedundantLetAnalyzer().analyze(ctx).firstOrNull() ?: return null
        return ApplicationManager.getApplication().runReadAction<String?> {
            val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return@runReadAction null
            KotlinRedundantLetFixer().generateFixFromPsi(issue, ktFile)?.fixedCode
        }
    }

    fun testSimpleUnwrapToDirectCall() {
        val src = """
            fun run() {
                val x: String = "hi"
                x?.let { println(it.length) }
            }
        """.trimIndent()
        val fixed = applyFix(src)
        assertNotNull(fixed)
        assertTrue(
            "Expected unwrapped `println(x.length)` in: $fixed",
            fixed!!.contains("println(x.length)")
        )
        assertFalse(
            "Expected `?.let` removed in: $fixed",
            fixed.contains("?.let")
        )
    }

    fun testChainedAccessPreserved() {
        val src = """
            fun run() {
                val x: String = "hi"
                x?.let { println(it.length.toString()) }
            }
        """.trimIndent()
        val fixed = applyFix(src)
        assertNotNull(fixed)
        assertTrue(
            "Expected chained access preserved in: $fixed",
            fixed!!.contains("println(x.length.toString())")
        )
    }

    fun testMultiStatementLambdaSkipped() {
        val src = """
            fun run() {
                val x: String = "hi"
                x?.let {
                    println(it.length)
                    println(it)
                }
            }
        """.trimIndent()
        val fixed = applyFix(src)
        assertNull(
            "Expected no fix for multi-statement lambda; got: $fixed",
            fixed
        )
    }

    fun testGenuinelyNullableLetIsNotFlagged() {
        val src = """
            fun fetch(): String? = null
            fun run() {
                val x = fetch()
                x?.let { println(it.length) }
            }
        """.trimIndent()
        val fixed = applyFix(src)
        assertNull(
            "Expected no fix for genuinely-nullable `?.let`",
            fixed
        )
    }

    fun testItReplacedAtStartOfIdentifier() {
        val src = """
            fun run() {
                val theItem: String = "hi"
                theItem?.let { println(it.length) }
            }
        """.trimIndent()
        val fixed = applyFix(src)
        assertNotNull(fixed)
        assertTrue(
            "Expected `theItem` preserved (whole-word `\\bit\\b` regex shouldn't replace `it` inside `theItem`); got: $fixed",
            fixed!!.contains("theItem")
        )
    }
}
