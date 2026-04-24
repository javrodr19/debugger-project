package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.*
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KotlinNullSafetyAnalyzerTest : BasePlatformTestCase() {

    private fun analyze(source: String): List<Issue> {
        val vf = myFixture.configureByText("Sample.kt", source).virtualFile
        val pf = ParsedFile(
            virtualFile = vf,
            path = vf.path,
            extension = "kt",
            content = source
        )
        val ctx = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(pf)
        )
        return KotlinNullSafetyAnalyzer().analyze(ctx)
    }

    fun testNullableAccessWithoutGuardIsFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                println(x.length)
            }
        """.trimIndent()
        val issues = analyze(src)
        assertEquals(1, issues.size)
        assertEquals(IssueType.NULL_SAFETY, issues.single().type)
    }

    fun testSafeCallIsNotFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                println(x?.length)
            }
        """.trimIndent()
        assertEquals(0, analyze(src).size)
    }

    fun testIfNotNullGuardIsNotFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                if (x != null) {
                    println(x.length)
                }
            }
        """.trimIndent()
        assertEquals(0, analyze(src).size)
    }

    fun testLetGuardIsNotFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                x?.let { println(it.length) }
            }
        """.trimIndent()
        assertEquals(0, analyze(src).size)
    }

    fun testBangBangIsNotFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                println(x!!.length)
            }
        """.trimIndent()
        assertEquals(0, analyze(src).size)
    }

    fun testElvisReturnIsNotFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                val s = x ?: return
                println(x.length)
            }
        """.trimIndent()
        assertEquals(0, analyze(src).size)
    }

    fun testReassignedBeforeAccessIsNotFlagged() {
        val src = """
            fun run() {
                var x: String? = null
                x = "hello"
                println(x.length)
            }
        """.trimIndent()
        assertEquals(0, analyze(src).size)
    }

    fun testShadowedVariableInInnerScopeIsNotFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                run {
                    val x = "shadow"
                    println(x.length)
                }
            }
        """.trimIndent()
        assertEquals(0, analyze(src).size)
    }
}
