package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.AegisKotlinAnalysisTestCase

class KotlinRedundantLetAnalyzerTest : AegisKotlinAnalysisTestCase() {

    private fun analyzeKt(source: String) = analyze(source) { KotlinRedundantLetAnalyzer() }

    fun testLetOnNonNullableTypeIsFlagged() {
        val src = """
            fun run() {
                val x: String = "hi"
                x?.let { println(it.length) }
            }
        """.trimIndent()
        assertEquals(1, analyzeKt(src).size)
    }

    fun testLetOnGenuinelyNullableIsNotFlagged() {
        val src = """
            fun fetch(): String? = null
            fun run() {
                val x = fetch()
                x?.let { println(it.length) }
            }
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }

    fun testLetWithMultipleStatementsIsNotFlagged() {
        val src = """
            fun run() {
                val x: String = "hi"
                x?.let {
                    println(it.length)
                    println(it)
                }
            }
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }

    fun testCanary_smartCastWindowMakesLetRedundant() {
        val src = """
            fun fetch(): String? = null
            fun run() {
                val x = fetch()
                if (x != null) {
                    x?.let { println(it.length) }
                }
            }
        """.trimIndent()
        // V1.4: the structural smart-cast walker sees `if (x != null)` and treats `x`
        // as non-null at the safe-call's receiver site; the `?.let` is therefore
        // redundant and flagged.
        assertEquals(1, analyzeKt(src).size)
    }

    fun testUnresolvedTypeDoesNotFlag() {
        val src = """
            fun run(x: SomeUnresolvedType) {
                x?.let { println(it) }
            }
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }
}
