package com.ghostdebugger.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val metadata: GraphMetadata
)

@Serializable
data class GraphNode(
    val id: String,
    val type: NodeType,
    val name: String,
    val filePath: String,
    val lineStart: Int = 0,
    val lineEnd: Int = 0,
    val complexity: Int = 1,
    val status: NodeStatus = NodeStatus.HEALTHY,
    val issues: List<Issue> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val dependents: List<String> = emptyList(),
    val position: NodePosition? = null
)

@Serializable
data class GraphEdge(
    val id: String,
    val source: String,
    val target: String,
    val type: EdgeType,
    val weight: Double = 1.0,
    val animated: Boolean = false
)

@Serializable
data class GraphMetadata(
    val projectName: String,
    val totalFiles: Int,
    val totalIssues: Int,
    val analysisTimestamp: Long,
    val healthScore: Double
)

@Serializable
data class NodePosition(val x: Double, val y: Double)

@Serializable
enum class NodeType {
    FILE, FUNCTION, CLASS, COMPONENT, HOOK, API_ROUTE, MODULE, SERVICE
}

@Serializable
enum class NodeStatus {
    HEALTHY, WARNING, ERROR
}

@Serializable
enum class EdgeType {
    IMPORT, CALL, DATA_FLOW, STATE_DEPENDENCY, INHERITANCE
}
