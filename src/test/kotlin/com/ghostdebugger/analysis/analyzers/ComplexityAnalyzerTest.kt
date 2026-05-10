package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.GraphNode
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.NodeType
import com.ghostdebugger.testutil.FixtureFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class ComplexityAnalyzerTest {
    // V1.4.1: threshold is now provider-injected. Pre-1.4.1 used a hardcoded
    // private val. Default 10 retained for test parity.
    private val analyzer = ComplexityAnalyzer(thresholdProvider = { 10 })

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

    @Test
    fun `respects custom threshold from provider`() {
        // V1.4.1: with threshold lowered to 5, complexity 7 is now flagged
        // even though it would not be with the default of 10.
        val lowAnalyzer = ComplexityAnalyzer(thresholdProvider = { 5 })
        val graph = InMemoryGraph().apply {
            addNode(GraphNode(
                id = "/src/Mid.kt",
                type = NodeType.FILE,
                name = "Mid.kt",
                filePath = "/src/Mid.kt",
                complexity = 7
            ))
        }
        val ctx = FixtureFactory.context(
            files = listOf(FixtureFactory.parsedFile("/src/Mid.kt", "kt", "// stub\n")),
            graph = graph
        )
        val issues = lowAnalyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.HIGH_COMPLEXITY },
            "Threshold 5 should flag complexity 7; got $issues")
    }

    @Test
    fun `respects custom high threshold from provider`() {
        val highAnalyzer = ComplexityAnalyzer(thresholdProvider = { 50 })
        val graph = InMemoryGraph().apply {
            addNode(GraphNode(
                id = "/src/Mid.kt",
                type = NodeType.FILE,
                name = "Mid.kt",
                filePath = "/src/Mid.kt",
                complexity = 30
            ))
        }
        val ctx = FixtureFactory.context(
            files = listOf(FixtureFactory.parsedFile("/src/Mid.kt", "kt", "// stub\n")),
            graph = graph
        )
        val issues = highAnalyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.HIGH_COMPLEXITY },
            "Threshold 50 should NOT flag complexity 30; got $issues")
    }
}
