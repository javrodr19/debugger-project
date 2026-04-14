package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.GraphNode
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.NodeType
import com.ghostdebugger.testutil.FixtureFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class ComplexityAnalyzerTest {
    private val analyzer = ComplexityAnalyzer()

    @Test
    fun `flags node with complexity above threshold`() {
        val graph = InMemoryGraph().apply {
            addNode(GraphNode(
                id = "/src/Big.kt",
                type = NodeType.FILE,
                name = "Big.kt",
                filePath = "/src/Big.kt",
                complexity = 15
            ))
        }
        val ctx = FixtureFactory.context(
            files = listOf(FixtureFactory.parsedFile("/src/Big.kt", "kt", "// stub\n")),
            graph = graph
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.HIGH_COMPLEXITY }, issues.toString())
    }

    @Test
    fun `does not flag node at or below threshold`() {
        val graph = InMemoryGraph().apply {
            addNode(GraphNode(
                id = "/src/Small.kt",
                type = NodeType.FILE,
                name = "Small.kt",
                filePath = "/src/Small.kt",
                complexity = 10
            ))
        }
        val ctx = FixtureFactory.context(
            files = listOf(FixtureFactory.parsedFile("/src/Small.kt", "kt", "// stub\n")),
            graph = graph
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.HIGH_COMPLEXITY }, issues.toString())
    }
}
