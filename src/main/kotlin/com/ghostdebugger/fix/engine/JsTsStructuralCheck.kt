package com.ghostdebugger.fix.engine

import com.ghostdebugger.parser.TsJsRegexSymbolExtractor

/**
 * Delimiter-balance check for `.ts`/`.js` — the substitute Tier-1 for the PSI parse-validity gate that
 * IntelliJ Community lacks for JS/TS. Over comment/string-masked content, every `()`, `{}`, `[]` must be
 * balanced and never close below zero. Best-effort: it catches gross delimiter malformation (a dropped or
 * extra brace) an AI extraction could introduce; it does NOT validate JS grammar.
 */
object JsTsStructuralCheck {
    fun isBalanced(content: String): Boolean {
        val masked = TsJsRegexSymbolExtractor().maskStringsAndComments(content)
        var paren = 0
        var brace = 0
        var bracket = 0
        for (c in masked) {
            when (c) {
                '(' -> paren++
                ')' -> if (--paren < 0) return false
                '{' -> brace++
                '}' -> if (--brace < 0) return false
                '[' -> bracket++
                ']' -> if (--bracket < 0) return false
            }
        }
        return paren == 0 && brace == 0 && bracket == 0
    }
}
