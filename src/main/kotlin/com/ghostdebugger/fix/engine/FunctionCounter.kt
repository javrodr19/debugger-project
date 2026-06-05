package com.ghostdebugger.fix.engine

import com.ghostdebugger.parser.TsJsRegexSymbolExtractor

/**
 * Content-based function count for the complexity divisor used by [ComplexityVerifier]
 * (`estimateComplexity = 1 + decisionPoints / functionCount`). Counts Kotlin `fun`, JS/TS
 * `function`, and arrow `=>` declarations over comment/string-masked content (reusing the same
 * masker `estimateComplexity` uses), so keywords inside literals or doc comments are not counted.
 *
 * This is a *stability* parameter, not a fidelity one: the gate holds it constant across the
 * original and the candidate (branch-elimination never adds or removes functions) and checks a
 * strict decrease, so any reasonable, stable count yields a correct verdict. Floored at 1 to keep
 * the metric finite for a file with no detected function.
 */
object FunctionCounter {
    private val PATTERNS = listOf(
        Regex("""\bfun\b"""),
        Regex("""\bfunction\b"""),
        Regex("""=>""")
    )

    fun count(content: String): Int {
        val masked = TsJsRegexSymbolExtractor().maskStringsAndComments(content)
        val n = PATTERNS.sumOf { pattern -> pattern.findAll(masked).count() }
        return n.coerceAtLeast(1)
    }
}
