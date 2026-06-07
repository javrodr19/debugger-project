package com.ghostdebugger.fix.engine

import com.ghostdebugger.graph.GraphBuilder
import com.ghostdebugger.parser.TsJsRegexSymbolExtractor

/**
 * Per-function complexity for `.ts`/`.js` content without a parser: find `function`/const-arrow
 * declarations over comment/string-masked content, balanced-brace-match each body, and map
 * `name -> GraphBuilder.estimateComplexity(body, 1)` (the same metric as Kotlin, single-sourced).
 * Returns the Kotlin measurer's [PerFunctionComplexity.Result] so [ExtractMethodVerifier] is reused.
 *
 * Best-effort by design (no JS grammar): name-only keys (JS has no overloading; a duplicate name sets
 * [PerFunctionComplexity.Result.collision] and the gate declines); expression-body arrows and any
 * function whose body fails to brace-balance are skipped (so an un-measurable source/target makes the
 * gate reject — conservative). Object-literal return types may mis-delimit and are also skipped/rejected.
 */
object JsTsPerFunctionComplexity {
    private val FUNCTION_DECL = Regex("""\bfunction\s+(\w+)\s*\(""")
    private val CONST_ARROW = Regex("""\b(?:const|let|var)\s+(\w+)[^=\n{(]*=\s*(?:async\s+)?\(""")

    fun measure(content: String): PerFunctionComplexity.Result {
        val masked = TsJsRegexSymbolExtractor().maskStringsAndComments(content)
        val graphBuilder = GraphBuilder()
        val map = HashMap<String, Int>()
        var collision = false

        // (name, index-of-parameter-'(', isArrow) for every declaration, over masked content.
        val decls = ArrayList<Triple<String, Int, Boolean>>()
        FUNCTION_DECL.findAll(masked).forEach { decls.add(Triple(it.groupValues[1], it.range.last, false)) }
        CONST_ARROW.findAll(masked).forEach { decls.add(Triple(it.groupValues[1], it.range.last, true)) }

        for ((name, parenOpen, isArrow) in decls) {
            val body = bodyRange(masked, parenOpen, isArrow) ?: continue
            val complexity = graphBuilder.estimateComplexity(content.substring(body.first, body.last + 1), 1)
            if (map.containsKey(name)) collision = true
            map[name] = complexity
        }
        return PerFunctionComplexity.Result(map, collision)
    }

    /** Body brace range [openBrace, closeBrace] for a decl whose parameter '(' is at [parenOpen], or null. */
    private fun bodyRange(masked: String, parenOpen: Int, isArrow: Boolean): IntRange? {
        val parenClose = matchDelimiter(masked, parenOpen, '(', ')') ?: return null
        var i = parenClose + 1
        if (isArrow) {
            val arrow = masked.indexOf("=>", i)
            if (arrow < 0) return null
            i = arrow + 2
            while (i < masked.length && masked[i].isWhitespace()) i++
            if (i >= masked.length || masked[i] != '{') return null   // expression-body arrow
            return matchDelimiter(masked, i, '{', '}')?.let { i..it }
        }
        // function declaration: skip whitespace, then an optional `: ReturnType` up to the body '{'
        while (i < masked.length && masked[i].isWhitespace()) i++
        if (i < masked.length && masked[i] == ':') {
            val brace = masked.indexOf('{', i)
            if (brace < 0) return null
            i = brace
        }
        if (i >= masked.length || masked[i] != '{') return null
        return matchDelimiter(masked, i, '{', '}')?.let { i..it }
    }

    /** Index of the closer matching the opener at [open] (balanced), or null if unbalanced before end. */
    private fun matchDelimiter(s: String, open: Int, openCh: Char, closeCh: Char): Int? {
        var depth = 0
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (c == openCh) depth++ else if (c == closeCh) { depth--; if (depth == 0) return i }
            i++
        }
        return null
    }
}
