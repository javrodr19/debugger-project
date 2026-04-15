package com.ghostdebugger.graph

import com.ghostdebugger.model.GraphNode
import com.ghostdebugger.model.GraphEdge
import com.ghostdebugger.model.NodeType
import com.ghostdebugger.model.NodeStatus
import com.ghostdebugger.model.EdgeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class InMemoryGraphCycleCacheTest {

    @Test
    fun testCycleCaching() {
        val graph = InMemoryGraph()
        
        graph.addNode(GraphNode("A", NodeType.FILE, "A", "A", 1, 10, 5, NodeStatus.HEALTHY))
        graph.addNode(GraphNode("B", NodeType.FILE, "B", "B", 1, 10, 5, NodeStatus.HEALTHY))
        graph.addEdge(GraphEdge("e1", "A", "B", EdgeType.IMPORT))
        graph.addEdge(GraphEdge("e2", "B", "A", EdgeType.IMPORT))

        // First call
        val cycles1 = graph.findCycles()
        assertEquals(1, cycles1.size)
        
        // Second call, should return same object
        val cycles2 = graph.findCycles()
        assertSame(cycles1, cycles2, "Cycles should be cached")
        
        // Adding an edge should invalidate cache
        graph.addNode(GraphNode("C", NodeType.FILE, "C", "C", 1, 10, 5, NodeStatus.HEALTHY))
        graph.addEdge(GraphEdge("e3", "B", "C", EdgeType.IMPORT))
        
        val cycles3 = graph.findCycles()
        assertNotSame(cycles1, cycles3, "Cache should be invalidated after graph modification")
    }
}
