package com.ghostdebugger.graph

import com.ghostdebugger.model.*
import com.ghostdebugger.parser.DependencyRelation
import com.ghostdebugger.parser.TsJsRegexSymbolExtractor
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
                complexity = estimateComplexity(file.content, file.functions.size),
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
internal fun estimateComplexity(content: String, functionCount: Int): Int {
    // Mask comments and string/char literals so control-flow keywords inside them aren't counted
    // (reuses the TS/JS extractor's masker, which preserves line structure). A doc comment that
    // merely mentions "if"/"for" used to inflate the score.
    val masked = TsJsRegexSymbolExtractor().maskStringsAndComments(content)
    val decisionPoints = COMPLEXITY_PATTERNS.sumOf { pattern -> pattern.findAll(masked).count() }
    // Per-function average keeps the McCabe-standard threshold (default 10) meaningful: a large
    // file of simple functions no longer trips it, while a single very branchy function still does.
    // Averaging biases toward false negatives over false positives (CLAUDE.md › Analyzer bias).
    // No `.coerceAtMost(20)` cap — that capped the sum so 27 structurally different files all
    // reported exactly 21, making the metric unable to rank anything.
    return 1 + decisionPoints / functionCount.coerceAtLeast(1)
}

fun normalizeId(path: String): String {
    return path.replace("\\", "/").replace(" ", "_")
}

companion object {
    // Decision points for a cyclomatic-complexity proxy. Deliberately excludes:
    //   - `else` (the paired `if` already counts that branch — counting both double-counts)
    //   - `?.` / `?.let` / `?.also` (safe calls are GOOD null handling, not complexity; `?.let`
    //     and `?.also` were also subsumed by the `?.` pattern, triple-counting one call)
    //   - `try {` (the `catch` already counts the exceptional path)
    //   - `?:` — in TypeScript this is optional-PROPERTY syntax (`prop?: Type`), not Elvis, so it
    //     inflated pure type files (types/index.ts had 16 optional props -> "complexity" 17) and
    //     trivial Kotlin Elvis defaults (`?: ""`). Dropping it under-counts Kotlin Elvis slightly,
    //     which is fine under the conservative-miss bias.
    // Adds `when` / `case` which the old set missed.
    private val COMPLEXITY_PATTERNS = listOf(
        Regex("""\bif\b"""),
        Regex("""\bfor\b"""),
        Regex("""\bwhile\b"""),
        Regex("""\bwhen\b"""),
        Regex("""\bswitch\b"""),
        Regex("""\bcase\b"""),
        Regex("""\bcatch\b"""),
        Regex("""&&"""),
        Regex("""\|\|""")
    )
}
}
