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

            val lines = file.content.lines()
            val nullStateVars = mutableSetOf<String>()

            // First pass: find variables initialized with null
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()

                // Detect useState(null) or useState()
                val useStateNullRegex = Regex("""const\s+\[(\w+),\s*set\w+\]\s*=\s*useState\s*\(\s*(?:null|undefined)?\s*\)""")
                useStateNullRegex.find(trimmed)?.let { match ->
                    nullStateVars.add(match.groupValues[1])
                }

                // Detect let/var x = null
                val varNullRegex = Regex("""(?:let|var)\s+(\w+)\s*(?::\s*\w+)?\s*=\s*(?:null|undefined)""")
                varNullRegex.find(trimmed)?.let { match ->
                    nullStateVars.add(match.groupValues[1])
                }
            }

            // Second pass: find dangerous accesses on null vars
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()

                // Skip comments
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed

                // Check for property access on known null vars without optional chaining
                for (varName in nullStateVars) {
                    val directAccessRegex = Regex("""(?<!\?)\b$varName\.([\w]+)""")
                    if (directAccessRegex.containsMatchIn(trimmed) && !trimmed.contains("if ($varName") && !trimmed.contains("if(!$varName")) {
                        // Check it's not inside a conditional
                        val hasNullCheck = lines.subList(maxOf(0, index - 5), index).any { prevLine ->
                            prevLine.contains("if ($varName") || prevLine.contains("if (!$varName") ||
                                    prevLine.contains("$varName &&") || prevLine.contains("$varName ?")
                        }

                        if (!hasNullCheck) {
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
            }
        }

        return issues
    }
}
