package com.ghostdebugger.model

import kotlinx.serialization.Serializable

@Serializable
data class Issue(
    val id: String,
    val type: IssueType,
    val severity: IssueSeverity,
    val title: String,
    val description: String,
    val filePath: String,
    val line: Int = 0,
    val column: Int = 0,
    val codeSnippet: String = "",
    val affectedNodes: List<String> = emptyList(),
    var explanation: String? = null,
    var suggestedFix: CodeFix? = null
)

@Serializable
enum class IssueType {
    NULL_SAFETY,
    CIRCULAR_DEPENDENCY,
    ASYNC_FLOW,
    UNHANDLED_PROMISE,
    STATE_BEFORE_INIT,
    HIGH_COMPLEXITY,
    MISSING_ERROR_HANDLING,
    DEAD_CODE,
    RESOURCE_LEAK,
    MEMORY_LEAK,
    ARCHITECTURE
}

@Serializable
enum class IssueSeverity {
    ERROR, WARNING, INFO
}

@Serializable
data class CodeFix(
    val id: String,
    val issueId: String,
    val description: String,
    val originalCode: String,
    val fixedCode: String,
    val filePath: String,
    val lineStart: Int,
    val lineEnd: Int
)

data class AnalysisResult(
    val issues: List<Issue>,
    val metrics: ProjectMetrics,
    val hotspots: List<String>,
    val risks: List<RiskItem>
)

data class ProjectMetrics(
    val totalFiles: Int,
    val totalIssues: Int,
    val errorCount: Int,
    val warningCount: Int,
    val infoCount: Int,
    val healthScore: Double,
    val avgComplexity: Double
)

data class RiskItem(
    val nodeId: String,
    val riskLevel: String,
    val reason: String
)

data class AnalysisContext(
    val graph: com.ghostdebugger.graph.InMemoryGraph,
    val project: com.intellij.openapi.project.Project,
    val parsedFiles: List<ParsedFile>
)

data class ParsedFile(
    val virtualFile: com.intellij.openapi.vfs.VirtualFile,
    val path: String,
    val extension: String,
    val content: String,
    val functions: List<FunctionSymbol> = emptyList(),
    val imports: List<ImportSymbol> = emptyList(),
    val exports: List<ExportSymbol> = emptyList(),
    val variables: List<VariableSymbol> = emptyList()
)

data class FunctionSymbol(
    val name: String,
    val line: Int,
    val isAsync: Boolean = false,
    val body: String = ""
)

data class VariableSymbol(
    val name: String,
    val line: Int,
    val kind: String = "const"
)

data class ImportSymbol(
    val source: String,
    val line: Int,
    val names: List<String> = emptyList()
)

data class ExportSymbol(
    val name: String,
    val line: Int
)

/**
 * Represents a cluster of connected nodes that are all in an error state.
 */
data class BrokenNeighborhood(
    val primaryNodeId: String,
    val neighbors: List<String>, // IDs (paths) of connected broken nodes
    val healthyContextNodes: List<String> // IDs of connected healthy nodes for type/context info
)

/**
 * Represents a logical fix that spans multiple files.
 */
@Serializable
data class MultiFileFix(
    val id: String,
    val explanation: String,
    val fileFixes: List<CodeFix>
)
