package com.ghostdebugger.graph

import com.ghostdebugger.model.*
import java.util.concurrent.ConcurrentHashMap

class InMemoryGraph {
    private val nodes = ConcurrentHashMap<String, GraphNode>()
    private val edges = ConcurrentHashMap<String, GraphEdge>()
    private val adjacencyList = ConcurrentHashMap<String, MutableSet<String>>()
    private val reverseAdjacencyList = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var cachedCycles: List<List<String>>? = null

    fun addNode(node: GraphNode) {
        nodes[node.id] = node
        adjacencyList.getOrPut(node.id) { ConcurrentHashMap.newKeySet() }
        reverseAdjacencyList.getOrPut(node.id) { ConcurrentHashMap.newKeySet() }
        cachedCycles = null
    }

    fun updateNode(node: GraphNode) {
        nodes[node.id] = node
        cachedCycles = null
    }

    fun addEdge(edge: GraphEdge) {
        edges[edge.id] = edge
        adjacencyList.getOrPut(edge.source) { ConcurrentHashMap.newKeySet() }.add(edge.target)
        reverseAdjacencyList.getOrPut(edge.target) { ConcurrentHashMap.newKeySet() }.add(edge.source)
        cachedCycles = null
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
        cachedCycles?.let { return it }
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()

        // Iterative DFS — V1.4.1 rewrite. Recursive DFS could StackOverflowError on
        // monorepo-shaped graphs (deep import chains). Each Frame holds the node and
        // an iterator over outgoing neighbors; we pop the frame in post-order.
        data class Frame(val node: String, val iter: Iterator<String>)

        for (root in nodes.keys.toList()) {
            if (root in visited) continue

            val recursionStack = mutableSetOf<String>()
            val path = mutableListOf<String>()
            val stack = ArrayDeque<Frame>()

            visited.add(root)
            recursionStack.add(root)
            path.add(root)
            stack.addLast(Frame(root, (adjacencyList[root] ?: emptySet()).iterator()))

            while (stack.isNotEmpty()) {
                val top = stack.last()
                if (top.iter.hasNext()) {
                    val neighbor = top.iter.next()
                    if (neighbor !in visited) {
                        visited.add(neighbor)
                        recursionStack.add(neighbor)
                        path.add(neighbor)
                        stack.addLast(Frame(neighbor, (adjacencyList[neighbor] ?: emptySet()).iterator()))
                    } else if (neighbor in recursionStack) {
                        val start = path.indexOf(neighbor)
                        if (start >= 0) cycles.add(path.subList(start, path.size).toList())
                    }
                } else {
                    recursionStack.remove(top.node)
                    path.removeLastOrNull()
                    stack.removeLast()
                }
            }
        }

        cachedCycles = cycles
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
        cachedCycles = null
    }
}
