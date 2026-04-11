package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.*
import java.util.UUID

class CircularDependencyAnalyzer : Analyzer {
    override val name = "CircularDependencyAnalyzer"

    override fun analyze(context: AnalysisContext): List<Issue> {
        val issues = mutableListOf<Issue>()
        val cycles = context.graph.findCycles()

        for (cycle in cycles) {
            if (cycle.size < 2) continue

            val cycleNames = cycle.map { id ->
                context.graph.getNode(id)?.name ?: id.substringAfterLast("/")
            }

            val firstNodeId = cycle.first()
            val firstNode = context.graph.getNode(firstNodeId) ?: continue

            issues.add(
                Issue(
                    id = UUID.randomUUID().toString(),
                    type = IssueType.CIRCULAR_DEPENDENCY,
                    severity = IssueSeverity.WARNING,
                    title = "Circular dependency: ${cycleNames.take(3).joinToString(" → ")}",
                    description = "Circular import detected between: ${cycleNames.joinToString(" → ")}. " +
                            "This can cause initialization issues and make the code harder to test.",
                    filePath = firstNode.filePath,
                    line = 1,
                    codeSnippet = cycle.joinToString(" → ") { context.graph.getNode(it)?.name ?: it },
                    affectedNodes = cycle
                )
            )
        }

        return issues
    }
}
