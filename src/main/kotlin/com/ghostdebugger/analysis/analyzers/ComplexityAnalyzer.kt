package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.*
import java.util.UUID

class ComplexityAnalyzer : Analyzer {
    override val name = "ComplexityAnalyzer"
    override val ruleId = "AEG-CPX-001"
    override val defaultSeverity = IssueSeverity.WARNING
    override val description = "Flags nodes whose estimated cyclomatic complexity exceeds the configured threshold (default 10)."
    private val complexityThreshold = 10

    override fun analyze(context: AnalysisContext): List<Issue> {
        val issues = mutableListOf<Issue>()

        for (node in context.graph.getAllNodes()) {
            if (node.complexity > complexityThreshold) {
                val file = context.filesByPath[node.filePath] ?: continue
                issues.add(
                    Issue(
                        id = UUID.randomUUID().toString(),
                        type = IssueType.HIGH_COMPLEXITY,
                        severity = IssueSeverity.WARNING,
                        title = "High complexity: ${node.name} (${node.complexity})",
                        description = "This module has a cyclomatic complexity of ${node.complexity} (threshold: $complexityThreshold). " +
                                "High complexity makes code harder to test, understand, and maintain.",
                        filePath = node.filePath,
                        line = 1,
                        codeSnippet = file.lines.take(10).joinToString("\n"),
                        affectedNodes = listOf(node.id)
                    )
                )
            }
        }

        return issues
    }
}
