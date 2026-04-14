package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.EdgeType
import com.ghostdebugger.model.GraphEdge
import com.ghostdebugger.model.GraphNode
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.NodeType
import com.ghostdebugger.testutil.FixtureFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class CircularDependencyAnalyzerTest {
    private val analyzer = CircularDependencyAnalyzer()

    @Test
    fun `flags two-node cycle`() {
        val graph = InMemoryGraph().apply {
            addNode(GraphNode(id = "A", type = NodeType.FILE, name = "A.kt", filePath = "/src/A.kt"))
            addNode(GraphNode(id = "B", type = NodeType.FILE, name = "B.kt", filePath = "/src/B.kt"))
            addEdge(GraphEdge(id = "A->B", source = "A", target = "B", type = EdgeType.IMPORT))
            addEdge(GraphEdge(id = "B->A", source = "B", target = "A", type = EdgeType.IMPORT))
        }
        val ctx = FixtureFactory.context(files = emptyList(), graph = graph)
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.CIRCULAR_DEPENDENCY }, issues.toString())
    }

    @Test
    fun `does not flag a linear chain`() {
        val graph = InMemoryGraph().apply {
            addNode(GraphNode(id = "A", type = NodeType.FILE, name = "A.kt", filePath = "/src/A.kt"))
            addNode(GraphNode(id = "B", type = NodeType.FILE, name = "B.kt", filePath = "/src/B.kt"))
            addNode(GraphNode(id = "C", type = NodeType.FILE, name = "C.kt", filePath = "/src/C.kt"))
            addEdge(GraphEdge(id = "A->B", source = "A", target = "B", type = EdgeType.IMPORT))
            addEdge(GraphEdge(id = "B->C", source = "B", target = "C", type = EdgeType.IMPORT))
        }
        val ctx = FixtureFactory.context(files = emptyList(), graph = graph)
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.CIRCULAR_DEPENDENCY }, issues.toString())
    }
}
