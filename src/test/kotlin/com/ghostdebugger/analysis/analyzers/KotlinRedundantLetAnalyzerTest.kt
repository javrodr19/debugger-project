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

    fun testSmartCastViaIfNullCheckIsKnownLimitation() {
        val src = """
            fun fetch(): String? = null
            fun run() {
                val x = fetch()
                if (x != null) {
                    x?.let { println(it.length) }
                }
            }
        """.trimIndent()
        // K2 known limitation: smart-cast info from an `if (x != null)` block isn't
        // exposed at the safe-call's receiver site, so we don't flag this `?.let` as
        // redundant even though it is. IDEA's own "Redundant `?.let` call" inspection
        // catches this via a different code path. The plain non-nullable case
        // (`val x: String = "hi"; x?.let {...}`) is still flagged correctly above.
        assertEquals(0, analyzeKt(src).size)
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
