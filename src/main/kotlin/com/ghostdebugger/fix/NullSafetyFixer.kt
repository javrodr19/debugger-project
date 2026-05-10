package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueType
import java.util.UUID

class NullSafetyFixer : Fixer {
    override val ruleId = "AEG-NULL-001"
    override val description =
        "Replaces direct property access on a known-null variable with optional chaining (?.)."

    private val titleVarRegex = Regex("""Null reference: (\w+)\.""")

    override fun canFix(issue: Issue): Boolean =
        issue.ruleId == ruleId && issue.type == IssueType.NULL_SAFETY

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? {
        val varName = titleVarRegex.find(issue.title)?.groupValues?.get(1) ?: return null
        val lines = fileContent.lines()
        val lineIndex = issue.line - 1
        if (lineIndex < 0 || lineIndex >= lines.size) return null
        val original = lines[lineIndex]

        // V1.4.1: mask strings + comments before matching so we don't rewrite
        // varName.x sequences that appear inside string literals or inline comments
        // (// or /* */). The masked string keeps the same character count as `original`
        // (string contents and comment bodies → spaces), so a match offset in the masked
        // string maps directly to the same offset in `original`.
        val masked = maskStringsAndComments(original)

        val safeRegex = Regex("""(?<!\?)(\b${Regex.escape(varName)})\.""")
        val match = safeRegex.find(masked) ?: return null

        // match.range.first is the start of `varName`. Group 1 captures `varName` itself.
        // Offset of the literal '.' = start + varName.length.
        val dotOffset = match.range.first + match.groupValues[1].length
        if (dotOffset >= original.length || original[dotOffset] != '.') return null
        val fixed = original.substring(0, dotOffset) + "?." + original.substring(dotOffset + 1)

        if (fixed == original) return null

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = "Add optional chaining: $varName. → $varName?.",
            originalCode = original,
            fixedCode = fixed,
            filePath = issue.filePath,
            lineStart = issue.line,
            lineEnd = issue.line,
            isDeterministic = true,
            confidence = 1.0
        )
    }

    /**
     * Returns a copy of [line] with string-literal contents and comment bodies
     * replaced by spaces. Preserves length so the caller can use offsets from the
     * masked string interchangeably with the original.
     *
     * Handles:
     *   - line comments (`// ...` to end)
     *   - block comments on a single line (`/* ... */`)
     *   - string literals with double-quote, single-quote, and backtick delimiters,
     *     including backslash escapes
     */
    private fun maskStringsAndComments(line: String): String {
        val sb = StringBuilder(line)
        var i = 0
        var inString = false
        var stringChar = ' '
        while (i < sb.length) {
            val c = sb[i]
            if (inString) {
                when {
                    c == '\\' && i + 1 < sb.length -> {
                        sb[i] = ' '; sb[i + 1] = ' '; i += 2
                    }
                    c == stringChar -> {
                        inString = false; i++
                    }
                    else -> {
                        sb[i] = ' '; i++
                    }
                }
            } else {
                when {
                    c == '/' && i + 1 < sb.length && sb[i + 1] == '/' -> {
                        while (i < sb.length) { sb[i] = ' '; i++ }
                    }
                    c == '/' && i + 1 < sb.length && sb[i + 1] == '*' -> {
                        val endRel = sb.substring(i + 2).indexOf("*/")
                        val end = if (endRel == -1) sb.length else i + 2 + endRel + 2
                        for (k in i until end) sb[k] = ' '
                        i = end
                    }
                    c == '"' || c == '\'' || c == '`' -> {
                        inString = true; stringChar = c; i++
                    }
                    else -> i++
                }
            }
        }
        return sb.toString()
    }
}
