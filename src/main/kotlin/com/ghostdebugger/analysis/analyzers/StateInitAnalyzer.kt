package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.*
import java.util.UUID

class StateInitAnalyzer : Analyzer {
    override val name = "StateInitAnalyzer"
    override val ruleId = "AEG-STATE-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description = "Detects React useState hooks called without an initial value whose values are later used via .map/.filter/.forEach/.reduce/.find/.some/.every/.length/.slice/.join."

    override fun analyze(context: AnalysisContext): List<Issue> {
        val issues = mutableListOf<Issue>()

        for (file in context.parsedFiles) {
            if (file.extension !in setOf("ts", "tsx", "js", "jsx")) continue

            val lines = file.content.lines()

            // Find useState() without initial value (undefined)
            val uninitializedStates = mutableMapOf<String, Int>() // varName -> line number

            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()

                // Match: const [items, setItems] = useState()  (no argument = undefined)
                val useStateNoArgRegex = Regex("""const\s+\[(\w+),\s*\w+\]\s*=\s*useState\s*\(\s*\)""")
                useStateNoArgRegex.find(trimmed)?.let { match ->
                    uninitializedStates[match.groupValues[1]] = index + 1
                }
            }

            // Now check if any uninitialized state is used in a way that assumes it's initialized
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//")) return@forEachIndexed

                for ((varName, initLine) in uninitializedStates) {
                    // Check for .map(), .filter(), .forEach(), .length on the var
                    val iterationRegex = Regex("""\b$varName\.(map|filter|forEach|reduce|find|some|every|length|slice|join)\b""")
                    val match = iterationRegex.find(trimmed)
                    if (match != null) {
                        val methodName = match.groupValues[1]
                        val snippet = lines.subList(maxOf(0, index - 1), minOf(lines.size, index + 2))
                            .joinToString("\n")
                        issues.add(
                            Issue(
                                id = UUID.randomUUID().toString(),
                                type = IssueType.STATE_BEFORE_INIT,
                                severity = IssueSeverity.ERROR,
                                title = "Uninitialized state used: $varName",
                                description = "'$varName' is declared with useState() (no initial value = undefined) " +
                                        "but is called with .$methodName on line ${index + 1}. " +
                                        "This will throw 'Cannot read properties of undefined'.",
                                filePath = file.path,
                                line = index + 1,
                                codeSnippet = snippet,
                                affectedNodes = listOf(file.path)
                            )
                        )
                    }
                }
            }
        }

        return issues
    }
}
