package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.AegisKotlinAnalysisTestCase

class KotlinTypeMismatchAnalyzerTest : AegisKotlinAnalysisTestCase() {

    private fun analyzeKt(source: String) = analyze(source) { KotlinTypeMismatchAnalyzer() }

    fun testIntDeclaredAsStringIsFlagged() {
        val src = """
            fun run() {
                val x: Int = ""
            }
        """.trimIndent()
        assertEquals(1, analyzeKt(src).size)
    }

    fun testAssignableTypeIsNotFlagged() {
        val src = """
            fun run() {
                val x: Number = 1
            }
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }

    fun testNullableMismatchIsFlagged() {
        val src = """
            fun fetch(): String? = null
            fun run() {
                val x: String = fetch()
            }
        """.trimIndent()
        assertEquals(1, analyzeKt(src).size)
    }

    fun testGenericInvarianceFlagged() {
        val src = """
            fun run() {
                val xs: MutableList<Number> = mutableListOf<Int>(1)
            }
        """.trimIndent()
        assertEquals(1, analyzeKt(src).size)
    }

    fun testUnresolvedFunctionInIsolationFlagsAsKnownLimitation() {
        val src = """
            fun run() {
                val x: Int = unresolvedFunction()
            }
        """.trimIndent()
        // K2 known limitation: when an unresolved call appears as the initializer, K2
        // wraps the result in a class-error shell that doesn't satisfy `is KaErrorType`
        // and whose `toString()` doesn't include the canonical "<ERROR>" marker. In
        // production this path is gated by the V1.1 two-phase early pass —
        // `CompilationErrorAnalyzer` flags the file as broken and downstream Kotlin
        // analyzers skip it entirely. The unit test here exercises the analyzer in
        // isolation, so the gate isn't applied.
        assertEquals(1, analyzeKt(src).size)
    }

    fun testNoExplicitTypeIsNotFlagged() {
        val src = """
            fun run() {
                val x = 1
            }
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }
}
