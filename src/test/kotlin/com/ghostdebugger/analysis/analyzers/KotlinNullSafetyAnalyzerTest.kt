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

    // ── Canary tests — the V1.2 name-matcher would have missed these.
    //    If these fail, suspect the Analysis API isn't resolving types
    //    (project descriptor regression — R3 in design spec).

    fun testCanary_typeInferredNullableMissedByOldAnalyzer() {
        val src = """
            fun fetch(): String? = null
            fun run() {
                val x = fetch()
                println(x.length)
            }
        """.trimIndent()
        val issues = analyzeKt(src)
        assertEquals(
            "Expected exactly one finding on the type-inferred nullable access; got ${issues.size}: $issues",
            1, issues.size
        )
        assertEquals("AEG-NULL-KT-001", issues.single().ruleId)
    }

    fun testCanary_smartCastWindowSuppressesFinding() {
        val src = """
            fun fetch(): String? = null
            fun run() {
                val x = fetch()
                if (x != null) {
                    println(x.length)
                }
            }
        """.trimIndent()
        assertEquals(0, analyzeKt(src).size)
    }

    fun testCanary_genericNullableBoundIsFlagged() {
        val src = """
            class Box<T>(val value: T?)
            fun run(b: Box<String>) {
                println(b.value.length)
            }
        """.trimIndent()
        val issues = analyzeKt(src)
        assertEquals(1, issues.size)
        assertEquals("AEG-NULL-KT-001", issues.single().ruleId)
    }

    fun testLateinitWithExplicitAssignmentBefore() {
        val src = """
            class Holder {
                lateinit var name: String
                fun setup() {
                    name = "hi"
                    println(name.length)
                }
            }
        """.trimIndent()
        // lateinit declares a non-null type; access never flagged.
        assertEquals(0, analyzeKt(src).size)
    }

    fun testTypeUnknownDoesNotCrash() {
        val src = """
            fun run() {
                val x: SomeUnresolvedType? = null
                println(x.length)
            }
        """.trimIndent()
        // F3 in design spec — KaErrorType must collapse to "don't flag",
        // and the analyzer must not throw.
        analyzeKt(src)
    }
}
