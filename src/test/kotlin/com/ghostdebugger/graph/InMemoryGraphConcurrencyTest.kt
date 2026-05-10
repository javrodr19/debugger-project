package com.ghostdebugger.graph

import com.ghostdebugger.model.EdgeType
import com.ghostdebugger.model.GraphEdge
import com.ghostdebugger.model.GraphNode
import com.ghostdebugger.model.NodeStatus
import com.ghostdebugger.model.NodeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Stress test for InMemoryGraph adjacency-list thread safety.
 *
 * Pre-V1.4.1, the outer ConcurrentHashMap nested a non-concurrent LinkedHashSet
 * (`mutableSetOf()`) — concurrent `addEdge` calls with a shared source could
 * race on the inner `.add()` call, risking ConcurrentModificationException or
 * a corrupted target set.
 *
 * V1.4.1 switches the inner type to `ConcurrentHashMap.newKeySet()`.
 */
class InMemoryGraphConcurrencyTest {

    @Test
    fun `concurrent addEdge calls do not throw or lose targets`() {
        val graph = InMemoryGraph()
        val source = "src"
        graph.addNode(GraphNode(source, NodeType.FILE, source, "/src.kt", 1, 10, 0, NodeStatus.HEALTHY))

        val threadCount = 8
        val edgesPerThread = 1000
        val totalEdges = threadCount * edgesPerThread
        for (i in 0 until totalEdges) {
            graph.addNode(GraphNode("tgt-$i", NodeType.FILE, "tgt-$i", "/t$i.kt", 1, 10, 0, NodeStatus.HEALTHY))
        }

        val pool = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val firstError = AtomicReference<Throwable?>(null)

        try {
            for (t in 0 until threadCount) {
                pool.submit {
                    try {
                        startGate.await()
                        for (i in 0 until edgesPerThread) {
                            val targetIndex = t * edgesPerThread + i
                            graph.addEdge(GraphEdge(
                                "e-$targetIndex",
                                source,
                                "tgt-$targetIndex",
                                EdgeType.IMPORT
                            ))
                        }
                    } catch (e: Throwable) {
                        firstError.compareAndSet(null, e)
                    } finally {
                        doneGate.countDown()
                    }
                }
            }

            startGate.countDown()
            assertTrue(doneGate.await(30, TimeUnit.SECONDS), "Threads did not finish within 30s")
        } finally {
            pool.shutdown()
        }

        firstError.get()?.let { throw AssertionError("Concurrent addEdge threw: $it", it) }

        val neighbors = graph.getNeighbors(source)
        assertEquals(
            totalEdges, neighbors.size,
            "Expected $totalEdges neighbors after concurrent inserts, got ${neighbors.size}"
        )
    }
}
