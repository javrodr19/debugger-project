package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.*
import java.util.UUID

class ComplexityAnalyzer(
    /**
     * Threshold provider so the analyzer reads the live `maxComplexity` from settings on
     * every run (V1.4.1 wiring). Tests inject a fixed value via the constructor.
     * Default reads from [com.ghostdebugger.settings.GhostDebuggerSettings].
     */
    private val thresholdProvider: () -> Int = {
        if (com.intellij.openapi.application.ApplicationManager.getApplication() == null) 10
        else runCatching { com.ghostdebugger.settings.GhostDebuggerSettings.getInstance().snapshot().maxComplexity }.getOrElse { 10 }
    }
) : Analyzer {
    override val name = "ComplexityAnalyzer"
    override val ruleId = "AEG-CPX-001"
    override val defaultSeverity = IssueSeverity.WARNING
    override val description = "Flags nodes whose estimated cyclomatic complexity exceeds the configured threshold (default 10, configurable in Settings → Tools → Aegis Debug)."

    override fun analyze(context: AnalysisContext): List<Issue> {
        val issues = mutableListOf<Issue>()
        val complexityThreshold = thresholdProvider()

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
