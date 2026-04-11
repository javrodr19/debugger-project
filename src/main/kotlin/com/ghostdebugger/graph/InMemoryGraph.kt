package com.ghostdebugger.graph

import com.ghostdebugger.model.*
import java.util.concurrent.ConcurrentHashMap

class InMemoryGraph {
    private val nodes = ConcurrentHashMap<String, GraphNode>()
    private val edges = ConcurrentHashMap<String, GraphEdge>()
    private val adjacencyList = ConcurrentHashMap<String, MutableSet<String>>()
    private val reverseAdjacencyList = ConcurrentHashMap<String, MutableSet<String>>()

    fun addNode(node: GraphNode) {
        nodes[node.id] = node
        adjacencyList.getOrPut(node.id) { mutableSetOf() }
        reverseAdjacencyList.getOrPut(node.id) { mutableSetOf() }
    }

    fun updateNode(node: GraphNode) {
        nodes[node.id] = node
    }

    fun addEdge(edge: GraphEdge) {
        edges[edge.id] = edge
        adjacencyList.getOrPut(edge.source) { mutableSetOf() }.add(edge.target)
        reverseAdjacencyList.getOrPut(edge.target) { mutableSetOf() }.add(edge.source)
    }

    fun getNode(id: String): GraphNode? = nodes[id]

    fun getAllNodes(): List<GraphNode> = nodes.values.toList()

    fun getAllEdges(): List<GraphEdge> = edges.values.toList()

    fun getNeighbors(id: String): List<GraphNode> {
        return adjacencyList[id]?.mapNotNull { nodes[it] } ?: emptyList()
    }

    fun getDependents(id: String): List<GraphNode> {
        return reverseAdjacencyList[id]?.mapNotNull { nodes[it] } ?: emptyList()
    }

    fun findCycles(): List<List<String>> {
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val path = mutableListOf<String>()

        fun dfs(nodeId: String) {
            visited.add(nodeId)
            recursionStack.add(nodeId)
            path.add(nodeId)

            adjacencyList[nodeId]?.forEach { neighbor ->
                if (!visited.contains(neighbor)) {
                    dfs(neighbor)
                } else if (recursionStack.contains(neighbor)) {
                    val cycleStart = path.indexOf(neighbor)
                    if (cycleStart >= 0) {
                        cycles.add(path.subList(cycleStart, path.size).toList())
                    }
                }
            }

            path.removeLastOrNull()
            recursionStack.remove(nodeId)
        }

        nodes.keys.forEach { nodeId ->
            if (!visited.contains(nodeId)) {
                dfs(nodeId)
            }
        }

        return cycles
    }

    fun calculateImpact(nodeId: String): List<String> {
        val affected = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(nodeId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            reverseAdjacencyList[current]?.forEach { dependent ->
                if (affected.add(dependent)) {
                    queue.add(dependent)
                }
            }
        }

        return affected.toList()
    }

    fun toProjectGraph(projectName: String = "Project"): ProjectGraph {
        val allNodes = getAllNodes()
        val allEdges = getAllEdges()
        val cycles = findCycles()
        val cycleEdgeIds = buildCycleEdgeIds(cycles)

        val totalIssues = allNodes.sumOf { it.issues.size }
        val healthScore = if (allNodes.isEmpty()) 100.0 else {
            val healthyCount = allNodes.count { it.status == NodeStatus.HEALTHY }
            (healthyCount.toDouble() / allNodes.size) * 100
        }

        return ProjectGraph(
            nodes = positionNodes(allNodes),
            edges = allEdges.map { edge -> edge.copy(isCycle = edge.id in cycleEdgeIds) },
            metadata = GraphMetadata(
                projectName = projectName,
                totalFiles = allNodes.size,
                totalIssues = totalIssues,
                analysisTimestamp = System.currentTimeMillis(),
                healthScore = healthScore,
                cycles = cycles
            )
        )
    }

    private fun buildCycleEdgeIds(cycles: List<List<String>>): Set<String> {
        val ids = mutableSetOf<String>()
        for (cycle in cycles) {
            for (i in cycle.indices) {
                val src = cycle[i]
                val tgt = cycle[(i + 1) % cycle.size]
                edges.values.find { it.source == src && it.target == tgt }?.let { ids.add(it.id) }
            }
        }
        return ids
    }

    private fun positionNodes(nodes: List<GraphNode>): List<GraphNode> {
        // Simple force-directed-like layout using topology
        val positioned = mutableListOf<GraphNode>()
        val levels = computeLevels()

        nodes.forEachIndexed { index, node ->
            val level = levels[node.id] ?: (index / 5)
            val posInLevel = nodes.filter { (levels[it.id] ?: 0) == level }.indexOf(node)
            val x = level * 250.0 + 100
            val y = posInLevel * 150.0 + 100
            positioned.add(node.copy(position = NodePosition(x, y)))
        }

        return positioned
    }

    private fun computeLevels(): Map<String, Int> {
        val levels = mutableMapOf<String, Int>()
        val roots = nodes.keys.filter { reverseAdjacencyList[it].isNullOrEmpty() }

        fun assignLevel(nodeId: String, level: Int, visitedPath: Set<String>) {
            // Prevent infinite recursion in case of cycles
            if (visitedPath.contains(nodeId)) return
            
            val currentLevel = levels[nodeId] ?: -1
            if (level > currentLevel) {
                levels[nodeId] = level
                val newPath = visitedPath + nodeId
                adjacencyList[nodeId]?.forEach { assignLevel(it, level + 1, newPath) }
            }
        }

        roots.forEach { assignLevel(it, 0, emptySet()) }
        nodes.keys.forEach { if (!levels.containsKey(it)) levels[it] = 0 }

        return levels
    }

    fun clear() {
        nodes.clear()
        edges.clear()
        adjacencyList.clear()
        reverseAdjacencyList.clear()
    }
}
