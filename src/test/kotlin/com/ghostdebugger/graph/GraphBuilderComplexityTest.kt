package com.ghostdebugger.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for GraphBuilder.estimateComplexity — the per-node complexity metric that the
 * ComplexityAnalyzer thresholds against. The computation was previously untested, which let three
 * defects ship: a `.coerceAtMost(20)` cap (every non-trivial file saturated to 21), counting
 * keywords inside comments/strings, and a per-file count compared against a per-function threshold.
 */
class GraphBuilderComplexityTest {

    private val builder = GraphBuilder()

    @Test
    fun `trivial file has baseline complexity`() {
        assertEquals(1, builder.estimateComplexity("fun f() { return 1 }\n", functionCount = 1))
    }

    @Test
    fun `keywords inside comments and strings are not counted`() {
        val src = """
            fun f() {
                // if for while when catch && ||
                val s = "if for while when catch && ||"
                /* if for while */
                return s
            }
        """.trimIndent()
        assertEquals(
            1, builder.estimateComplexity(src, functionCount = 1),
            "control-flow keywords inside comments/strings must not inflate complexity"
        )
    }

    @Test
    fun `complexity is not capped at 21`() {
        val ifs = (1..30).joinToString("\n") { "    if (x == $it) doThing()" }
        val src = "fun f() {\n$ifs\n}\n"
        val c = builder.estimateComplexity(src, functionCount = 1)
        assertTrue(c > 21, "30 branches must score above the old saturation ceiling of 21; got $c")
    }

    @Test
    fun `large file of simple functions stays at or below threshold`() {
        // 12 one-branch functions: per-function average is ~1, not the file-level total of 12.
        val fns = (1..12).joinToString("\n") { "fun f$it() { if (a) b() }" }
        val c = builder.estimateComplexity(fns + "\n", functionCount = 12)
        assertTrue(c <= 10, "per-function normalization should keep this under threshold 10; got $c")
    }

    @Test
    fun `a single very branchy function is still flagged`() {
        val ifs = (1..15).joinToString("\n") { "    if (x == $it) doThing()" }
        val src = "fun hot() {\n$ifs\n}\n"
        val c = builder.estimateComplexity(src, functionCount = 1)
        assertTrue(c > 10, "a function with 15 branches must exceed threshold 10; got $c")
    }

    @Test
    fun `TypeScript optional properties are not counted as complexity`() {
        val src = """
            export interface Foo {
              a?: string
              b?: number
              c?: boolean
              d?: string
              e?: number
            }
        """.trimIndent()
        // `?:` here is optional-PROPERTY syntax, not an Elvis/ternary operator — a pure types file
        // has zero control flow and must score the baseline, not be inflated to ~6/17.
        assertEquals(1, builder.estimateComplexity(src, functionCount = 0))
    }
}
