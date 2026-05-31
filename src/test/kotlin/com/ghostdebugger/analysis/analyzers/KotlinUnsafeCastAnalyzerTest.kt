package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.AegisKotlinAnalysisTestCase
import com.ghostdebugger.model.IssueType

class KotlinUnsafeCastAnalyzerTest : AegisKotlinAnalysisTestCase() {

    private fun analyzeKt(source: String) = analyze(source) { KotlinUnsafeCastAnalyzer() }

    fun testDowncastWithoutSafeCastIsFlagged() {
        val src = """
            open class A
            class B : A()
            fun run(a: A): B = a as B
        """.trimIndent()
        val issues = analyzeKt(src)
        assertEquals(1, issues.size)
        assertEquals("AEG-CAST-KT-001", issues.single().ruleId)
        // Unsafe casts are compilation errors, not null-safety issues. Regression for BUG-21.
        assertEquals(IssueType.COMPILATION_ERROR, issues.single().type)
    }

    fun testSafeCastIsNotFlagged() {
        val src = """
            open class A
            class B : A()
            fun run(a: A): B? = a as? B
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }

    fun testUpcastIsNotFlagged() {
        val src = """
            open class A
            class B : A()
            fun run(b: B): A = b as A
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }

    fun testIdentityCastIsNotFlagged() {
        val src = """
            class A
            fun run(a: A): A = a as A
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }

    fun testSmartCastedReceiverIsNotFlagged() {
        val src = """
            fun run(a: Any) {
                if (a is String) {
                    val s = a as String
                }
            }
        """.trimIndent()
        // V1.4: the structural smart-cast walker detects `a is String` and narrows `a`
        // to String at the `as String` use site; the cast is now identity, not flagged.
        assertEquals(0, analyzeKt(src).size)
    }

    fun testCanary_unrelatedTypesDowncastFlagged() {
        val src = """
            class A
            class B
            fun run(a: A): B = a as B
        """.trimIndent()
        assertEquals(1, analyzeKt(src).size)
    }

    fun testCanary_unresolvedTargetTypeDoesNotFlag() {
        val src = """
            fun run(a: Any): Foo = a as Foo
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }
}
