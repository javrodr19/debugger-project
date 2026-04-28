package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.model.IssueType

class KotlinNullSafetyAnalyzerTest : AegisKotlinAnalysisTestCase() {

    private fun analyzeKt(source: String) = analyze(source) { KotlinNullSafetyAnalyzer() }

    fun testNullableAccessWithoutGuardIsFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                println(x.length)
            }
        """.trimIndent()
        val issues = analyzeKt(src)
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
        assertEquals(0, analyzeKt(src).size)
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
        assertEquals(0, analyzeKt(src).size)
    }

    fun testLetGuardIsNotFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                x?.let { println(it.length) }
            }
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }

    fun testBangBangIsNotFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                println(x!!.length)
            }
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }

    fun testElvisReturnIsNotFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                val s = x ?: return
                println(x.length)
            }
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }

    fun testElvisThrowIsNotFlagged() {
        val src = """
            fun run() {
                val x: String? = null
                val s = x ?: throw IllegalStateException("nope")
                println(x.length)
            }
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }

    fun testReassignedToNullStillFlagged() {
        val src = """
            fun run() {
                var x: String? = "hi"
                x = null
                println(x.length)
            }
        """.trimIndent()
        assertEquals(1, analyzeKt(src).size)
    }

    fun testReassignedBeforeAccessIsNotFlagged() {
        val src = """
            fun run() {
                var x: String? = null
                x = "hello"
                println(x.length)
            }
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
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
        assertEquals(0, analyzeKt(src).size)
    }
}
