package com.ghostdebugger.graph

import com.ghostdebugger.model.*
import com.ghostdebugger.parser.DependencyRelation
import java.io.File

class GraphBuilder {

    fun build(
        parsedFiles: List<com.ghostdebugger.model.ParsedFile>,
        dependencies: List<DependencyRelation>
    ): InMemoryGraph {
        val graph = InMemoryGraph()
        
        createFileNodes(graph, parsedFiles)
        createDependencyEdges(graph, dependencies)

        return graph
    }

    private fun createFileNodes(graph: InMemoryGraph, parsedFiles: List<com.ghostdebugger.model.ParsedFile>) {
        for (file in parsedFiles) {
            val nodeType = detectNodeType(file)
            val functions = file.functions.map { FunctionInfo(name = it.name, line = it.line, isAsync = it.isAsync) }
            val variables = file.variables.map { VariableInfo(name = it.name, line = it.line, kind = it.kind) }
            
            val node = GraphNode(
                id = normalizeId(file.path),
                type = nodeType,
                name = File(file.path).name,
                filePath = file.path,
                lineStart = 1,
                lineEnd = file.content.lines().size,
                complexity = estimateComplexity(file.content),
                status = NodeStatus.HEALTHY,
                functions = functions,
                variables = variables
            )
            graph.addNode(node)
        }
    }

    private fun createDependencyEdges(graph: InMemoryGraph, dependencies: List<DependencyRelation>) {
        val edgeSet = mutableSetOf<String>()
        for (dep in dependencies) {
            val sourceId = normalizeId(dep.fromPath)
            val sourceNode = graph.getNode(sourceId) ?: continue

            if (dep.toPath.startsWith("ext:")) {
                handleExternalDependency(graph, dep, sourceId, edgeSet)
            } else {
                handleInternalDependency(graph, dep, sourceId, edgeSet)
            }
        }
    }

    private fun handleExternalDependency(
        graph: InMemoryGraph,
        dep: DependencyRelation,
        sourceId: String,
        edgeSet: MutableSet<String>
    ) {
        val moduleName = dep.importSource
        val targetId = "ext_${normalizeId(moduleName)}"
        
        if (graph.getNode(targetId) == null) {
            graph.addNode(
                GraphNode(
                    id = targetId,
                    type = NodeType.MODULE,
                    name = moduleName,
                    filePath = dep.toPath,
                    status = NodeStatus.HEALTHY
                )
            )
        }
        
        addEdgeIfPossible(graph, sourceId, targetId, edgeSet)
    }

    private fun handleInternalDependency(
        graph: InMemoryGraph,
        dep: DependencyRelation,
        sourceId: String,
        edgeSet: MutableSet<String>
    ) {
        val targetId = normalizeId(dep.toPath)
        if (graph.getNode(targetId) != null) {
            addEdgeIfPossible(graph, sourceId, targetId, edgeSet)
        }
    }

    private fun addEdgeIfPossible(
        graph: InMemoryGraph,
        sourceId: String,
        targetId: String,
        edgeSet: MutableSet<String>
    ) {
        val edgeId = "$sourceId->$targetId"
        if (edgeSet.add(edgeId)) {
            graph.addEdge(
                GraphEdge(
                    id = edgeId,
                    source = sourceId,
                    target = targetId,
                    type = EdgeType.IMPORT
                )
            )
        }
    }

    fun applyIssues(graph: InMemoryGraph, issues: List<Issue>): InMemoryGraph {
        val issuesByFile = issues.groupBy { normalizeId(it.filePath) }

        for ((nodeId, nodeIssues) in issuesByFile) {
            val node = graph.getNode(nodeId) ?: continue
            val status = when {
                nodeIssues.any { it.severity == IssueSeverity.ERROR } -> NodeStatus.ERROR
                nodeIssues.any { it.severity == IssueSeverity.WARNING } -> NodeStatus.WARNING
                else -> NodeStatus.HEALTHY
            }
            graph.updateNode(node.copy(issues = nodeIssues, status = status))
        }

        return graph
    }

    private fun detectNodeType(file: com.ghostdebugger.model.ParsedFile): NodeType {
        val name = File(file.path).name.lowercase()
        val content = file.content

        return when {
            name.endsWith(".tsx") || name.endsWith(".jsx") -> {
                when {
                    content.contains("useEffect") || content.contains("useState") -> NodeType.COMPONENT
                    name.startsWith("use") -> NodeType.HOOK
                    else -> NodeType.COMPONENT
                }
            }
            file.path.contains("/hooks/") || file.path.contains("\\hooks\\") -> NodeType.HOOK
            file.path.contains("/services/") || file.path.contains("\\services\\") -> NodeType.SERVICE
            file.path.contains("/api/") || file.path.contains("\\api\\") -> NodeType.API_ROUTE
            name.endsWith(".kt") || name.endsWith(".java") -> NodeType.FILE
            else -> NodeType.MODULE
        }
    }

    private fun estimateComplexity(content: String): Int {
        val complexityKeywords = listOf(
            "if ", "else ", "for ", "while ", "switch ", "catch ",
            "&&", "||", "?.", "?.let", "try {", "?.also"
        )
        return 1 + complexityKeywords.sumOf { keyword ->
            content.split(keyword).size - 1
        }.coerceAtMost(20)
    }

    fun normalizeId(path: String): String {
        return path.replace("\\", "/").replace(" ", "_")
    }
}
