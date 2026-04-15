package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.*
import java.util.UUID

class AsyncFlowAnalyzer : Analyzer {
    override val name = "AsyncFlowAnalyzer"
    override val ruleId = "AEG-ASYNC-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description = "Detects unhandled promise rejections, fetch calls without status check or try/catch, and setInterval/setTimeout in useEffect without a cleanup return."

    override fun analyze(context: AnalysisContext): List<Issue> {
        val issues = mutableListOf<Issue>()

        for (file in context.parsedFiles) {
            if (file.extension !in setOf("ts", "tsx", "js", "jsx")) continue

            val lines = file.lines

            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()

                // Skip comments
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed

                // Detect async function without try/catch
                checkUnhandledPromise(trimmed, index, file, lines, issues)

                // Detect fetch without error handling
                checkFetchWithoutErrorHandling(trimmed, index, file, lines, issues)

                // Detect useEffect with setInterval/setTimeout without cleanup
                checkMissingCleanup(trimmed, index, file, lines, issues)
            }
        }

        return issues
    }

    private fun checkUnhandledPromise(
        line: String,
        lineIndex: Int,
        file: com.ghostdebugger.model.ParsedFile,
        lines: List<String>,
        issues: MutableList<Issue>
    ) {
        // Find .then() without .catch()
        if (line.contains(".then(") && !line.contains(".catch(")) {
            // Check if next line contains .catch (simple multi-line check)
            val nextLine = if (lineIndex + 1 < lines.size) lines[lineIndex + 1] else ""
            if (nextLine.contains(".catch(")) return

            val snippet = lines.subList(maxOf(0, lineIndex - 1), minOf(lines.size, lineIndex + 3))
                .joinToString("\n")
            issues.add(
                Issue(
                    id = UUID.randomUUID().toString(),
                    type = IssueType.UNHANDLED_PROMISE,
                    severity = IssueSeverity.ERROR,
                    title = "Unhandled promise rejection",
                    description = "A Promise is used with .then() but without .catch(). " +
                            "Network errors or rejected promises will be silently ignored.",
                    filePath = file.path,
                    line = lineIndex + 1,
                    codeSnippet = snippet,
                    affectedNodes = listOf(file.path)
                )
            )
        }
    }

    private fun checkFetchWithoutErrorHandling(
        line: String,
        lineIndex: Int,
        file: com.ghostdebugger.model.ParsedFile,
        lines: List<String>,
        issues: MutableList<Issue>
    ) {
        // Detect: return response.json() or await fetch(...) without status check or try/catch
        if (FETCH_RETURN_PATTERN.containsMatchIn(line)) {
            // Check if there's a status check or try/catch in nearby lines
            val surroundingLines = lines.subList(maxOf(0, lineIndex - 10), lineIndex + 1)
            val hasStatusCheck = surroundingLines.any { it.contains("response.ok") || it.contains("res.ok") || it.contains(".status") }
            val hasTryCatch = surroundingLines.any { it.trim().startsWith("try") }

            if (!hasStatusCheck && !hasTryCatch) {
                val snippet = lines.subList(maxOf(0, lineIndex - 3), minOf(lines.size, lineIndex + 2))
                    .joinToString("\n")
                issues.add(
                    Issue(
                        id = UUID.randomUUID().toString(),
                        type = IssueType.MISSING_ERROR_HANDLING,
                        severity = IssueSeverity.ERROR,
                        title = "Missing error handling in fetch/async call",
                        description = "API call response is not validated before use. " +
                                "No status check, no try/catch. HTTP errors (4xx, 5xx) will silently fail.",
                        filePath = file.path,
                        line = lineIndex + 1,
                        codeSnippet = snippet,
                        affectedNodes = listOf(file.path)
                    )
                )
            }
        }
    }

    private fun checkMissingCleanup(
        line: String,
        lineIndex: Int,
        file: com.ghostdebugger.model.ParsedFile,
        lines: List<String>,
        issues: MutableList<Issue>
    ) {
        // Find setInterval inside useEffect without cleanup
        if (SET_INTERVAL_PATTERN.containsMatchIn(line)) {
            // Look backwards for useEffect and forwards for return cleanup
            val blockStart = (lineIndex - 1 downTo maxOf(0, lineIndex - 15)).firstOrNull { i ->
                lines[i].contains("useEffect")
            }

            if (blockStart != null) {
                val blockLines = lines.subList(blockStart, minOf(lines.size, lineIndex + 20))
                val hasCleanup = blockLines.any { it.contains("return") && (it.contains("clearInterval") || it.contains("clearTimeout")) }

                if (!hasCleanup) {
                    val snippet = lines.subList(maxOf(0, lineIndex - 2), minOf(lines.size, lineIndex + 3))
                        .joinToString("\n")
                    issues.add(
                        Issue(
                            id = UUID.randomUUID().toString(),
                            type = IssueType.MEMORY_LEAK,
                            severity = IssueSeverity.WARNING,
                            title = "Memory leak: interval/timeout without cleanup",
                            description = "useEffect uses setInterval or setTimeout but doesn't return a cleanup function. " +
                                    "This causes memory leaks when the component unmounts.",
                            filePath = file.path,
                            line = lineIndex + 1,
                            codeSnippet = snippet,
                            affectedNodes = listOf(file.path)
                        )
                    )
                }
            }
        }
    }

    companion object {
        private val FETCH_RETURN_PATTERN = Regex("""return\s+(?:await\s+)?(?:response|res)\.json\s*\(\s*\)""")
        private val SET_INTERVAL_PATTERN = Regex("""(?:setInterval|setTimeout)\s*\(""")
    }
}
