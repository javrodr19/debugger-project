package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.*
import java.util.UUID

class NullSafetyAnalyzer : Analyzer {
    override val name = "NullSafetyAnalyzer"
    override val ruleId = "AEG-NULL-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description = "Detects property access on variables initialized as null/undefined without a guarding null check."

    override fun analyze(context: AnalysisContext): List<Issue> {
        val issues = mutableListOf<Issue>()

        for (file in context.parsedFiles) {
            if (file.extension !in setOf("ts", "tsx", "js", "jsx")) continue

            val lines = file.lines
            val nullStateVars = mutableSetOf<String>()

            // First pass: find variables initialized with null. Mask strings/comments
            // so we don't pick up declarations inside literals or docs.
            lines.forEach { line ->
                val masked = maskStringsAndComments(line).trim()
                USE_STATE_NULL_REGEX.find(masked)?.let { match ->
                    nullStateVars.add(match.groupValues[1])
                }
                VAR_NULL_REGEX.find(masked)?.let { match ->
                    nullStateVars.add(match.groupValues[1])
                }
            }

            if (nullStateVars.isEmpty()) continue

            // V1.4.1 P1: build a single alternation regex across all null-state vars
            // so the per-line scan is O(N) instead of O(N × M).
            val combinedRegex = Regex(
                """(?<!\?)\b(""" + nullStateVars.joinToString("|") { Regex.escape(it) } + """)\.([\w]+)"""
            )

            // Second pass: find dangerous accesses on null vars in CODE (not strings
            // or comments). Pre-V1.4.1 only skipped lines that began with // or * —
            // mid-line // and /* */ slipped through.
            lines.forEachIndexed { index, line ->
                val masked = maskStringsAndComments(line)
                if (masked.isBlank()) return@forEachIndexed

                combinedRegex.findAll(masked).forEach { match ->
                    val varName = match.groupValues[1]
                    // Same-line inline null check (e.g. `user && user.foo`).
                    val sameLineCheck = masked.contains("if ($varName") || masked.contains("if(!$varName") ||
                        masked.contains("$varName &&") || masked.contains("$varName ?")
                    if (sameLineCheck) return@forEach

                    val hasNullCheck = lines.subList(maxOf(0, index - 5), index).any { prevLine ->
                        val prevMasked = maskStringsAndComments(prevLine)
                        prevMasked.contains("if ($varName") || prevMasked.contains("if (!$varName") ||
                            prevMasked.contains("$varName &&") || prevMasked.contains("$varName ?")
                    }
                    if (hasNullCheck) return@forEach

                    val snippet = lines.subList(maxOf(0, index - 2), minOf(lines.size, index + 3))
                        .joinToString("\n")
                    issues.add(
                        Issue(
                            id = UUID.randomUUID().toString(),
                            type = IssueType.NULL_SAFETY,
                            severity = IssueSeverity.ERROR,
                            title = "Null reference: $varName may be null",
                            description = "Variable '$varName' may be null or undefined when accessing property. " +
                                "This is initialized as null and accessed without a null check.",
                            filePath = file.path,
                            line = index + 1,
                            codeSnippet = snippet,
                            affectedNodes = listOf(file.path),
                            ruleId = ruleId,
                            sources = listOf(IssueSource.STATIC),
                            providers = listOf(EngineProvider.STATIC),
                            confidence = 1.0
                        )
                    )
                }
            }
        }

        return issues
    }

    /** Mask string-literal contents and comment bodies with spaces, preserving offsets.
     *  Mirror of the helper in NullSafetyFixer — duplicated locally for package isolation. */
    private fun maskStringsAndComments(line: String): String {
        val sb = StringBuilder(line)
        var i = 0
        var inString = false
        var stringChar = ' '
        while (i < sb.length) {
            val c = sb[i]
            if (inString) {
                when {
                    c == '\\' && i + 1 < sb.length -> { sb[i] = ' '; sb[i + 1] = ' '; i += 2 }
                    c == stringChar -> { inString = false; i++ }
                    else -> { sb[i] = ' '; i++ }
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
                    c == '"' || c == '\'' || c == '`' -> { inString = true; stringChar = c; i++ }
                    else -> i++
                }
            }
        }
        return sb.toString()
    }

    companion object {
        private val USE_STATE_NULL_REGEX = Regex("""const\s+\[(\w+),\s*set\w+\]\s*=\s*useState\s*\(\s*(?:null|undefined)?\s*\)""")
        private val VAR_NULL_REGEX = Regex("""(?:let|var)\s+(\w+)\s*(?::\s*\w+)?\s*=\s*(?:null|undefined)""")
    }
}
