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

        // Create nodes for each file
        for (file in parsedFiles) {
            val nodeType = detectNodeType(file)
            val functionInfos = file.functions.map { FunctionInfo(name = it.name, line = it.line, isAsync = it.isAsync) }
            val variableInfos = file.variables.map { VariableInfo(name = it.name, line = it.line, kind = it.kind) }
            val node = GraphNode(
                id = normalizeId(file.path),
                type = nodeType,
                name = File(file.path).name,
                filePath = file.path,
                lineStart = 1,
                lineEnd = file.lines.size,
                complexity = estimateComplexity(file.content),
                status = NodeStatus.HEALTHY,
                issues = emptyList(),
                dependencies = emptyList(),
                dependents = emptyList(),
                functions = functionInfos,
                variables = variableInfos
            )
            graph.addNode(node)
        }

        // Create edges from dependencies
        val edgeSet = mutableSetOf<String>()
        for (dep in dependencies) {
            val sourceId = normalizeId(dep.fromPath)
            
            if (dep.toPath.startsWith("ext:")) {
                val moduleName = dep.importSource
                val targetId = "ext_${normalizeId(moduleName)}"
                
                // Inject external dependency node dynamically
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
                
                if (graph.getNode(sourceId) != null) {
                    val edgeId = "$sourceId->$targetId"
                    if (edgeSet.add(edgeId)) {
                        graph.addEdge(
                            GraphEdge(id = edgeId, source = sourceId, target = targetId, type = EdgeType.IMPORT)
                        )
                    }
                }
            } else {
                val targetId = normalizeId(dep.toPath)

                if (graph.getNode(sourceId) != null && graph.getNode(targetId) != null) {
                    val edgeId = "$sourceId->$targetId"
                    if (edgeSet.add(edgeId)) {
                        graph.addEdge(
                            GraphEdge(id = edgeId, source = sourceId, target = targetId, type = EdgeType.IMPORT)
                        )
                    }
                }
            }
        }

        return graph
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
    return 1 + COMPLEXITY_PATTERNS.sumOf { pattern ->
        pattern.findAll(content).count()
    }.coerceAtMost(20)
}

fun normalizeId(path: String): String {
    return path.replace("\\", "/").replace(" ", "_")
}

companion object {
    private val COMPLEXITY_PATTERNS = listOf(
        Regex("""\bif\b"""),
        Regex("""\belse\b"""),
        Regex("""\bfor\b"""),
        Regex("""\bwhile\b"""),
        Regex("""\bswitch\b"""),
        Regex("""\bcatch\b"""),
        Regex("""&&"""),
        Regex("""\|\|"""),
        Regex("""\?\."""),
        Regex("""\?\.let"""),
        Regex("""try\s*\{"""),
        Regex("""\?\.also""")
    )
}
}
