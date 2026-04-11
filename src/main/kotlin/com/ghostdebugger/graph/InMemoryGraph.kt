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
            val totalErrors = allNodes.flatMap { it.issues }.count { it.severity == IssueSeverity.ERROR }
            val totalWarnings = allNodes.flatMap { it.issues }.count { it.severity == IssueSeverity.WARNING }
            (100.0 - (totalErrors * 15.0) - (totalWarnings * 5.0)).coerceIn(0.0, 100.0)
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
        // Group nodes by their folder directory
        val groups = nodes.groupBy {
            val path = it.filePath.replace("\\", "/")
            val idx = path.lastIndexOf('/')
            if (idx != -1) path.substring(0, idx) else "root"
        }

        val positioned = mutableListOf<GraphNode>()
        var groupIndex = 0
        // Calculate a rough square layout for the groups
        val groupsPerRow = Math.ceil(Math.sqrt(groups.size.toDouble())).toInt().coerceAtLeast(1)

        for ((_, groupNodes) in groups) {
            val groupCol = groupIndex % groupsPerRow
            val groupRow = groupIndex / groupsPerRow

            // Base pixel coordinates for this group's bounding box
            val groupX = groupCol * 1400.0
            val groupY = groupRow * 1000.0

            // Layout nodes inside the group in a local grid
            val nodesPerRow = Math.ceil(Math.sqrt(groupNodes.size.toDouble())).toInt().coerceAtLeast(1)
            groupNodes.forEachIndexed { i, node ->
                val col = i % nodesPerRow
                val row = i / nodesPerRow

                // Local spacing within the group (300px horizontal, 200px vertical)
                val nx = groupX + col * 300.0
                val ny = groupY + row * 200.0
                positioned.add(node.copy(position = NodePosition(nx, ny)))
            }
            groupIndex++
        }

        return positioned
    }

    fun clear() {
        nodes.clear()
        edges.clear()
        adjacencyList.clear()
        reverseAdjacencyList.clear()
    }
}
