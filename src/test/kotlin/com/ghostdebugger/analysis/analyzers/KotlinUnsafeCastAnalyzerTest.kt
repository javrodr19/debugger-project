package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.AegisKotlinAnalysisTestCase

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

    fun testSmartCastedReceiverFlagsAsKnownLimitation() {
        val src = """
            fun run(a: Any) {
                if (a is String) {
                    val s = a as String
                }
            }
        """.trimIndent()
        // K2 known limitation: smart-cast info from `is`-checks isn't propagated to the
        // `cast.left` use site through `smartCastInfo`/`expressionType`. The Kotlin compiler
        // recognises `a as String` as an unnecessary cast (and IDEA's "Unnecessary cast"
        // inspection flags it) — but the public Analysis API doesn't expose that here, so
        // our analyzer flags it as a genuine downcast (`Any -> String`). Conservative-miss
        // bias is preferred (we'd rather over-flag than miss a real cast bug); but documenting
        // the surface so a future fix can target this case explicitly.
        assertEquals(1, analyzeKt(src).size)
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
