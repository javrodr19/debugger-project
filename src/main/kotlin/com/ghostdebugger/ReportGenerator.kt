package com.ghostdebugger

import com.ghostdebugger.model.ProjectGraph
import com.ghostdebugger.model.GraphNode
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.NodeStatus
import java.io.File

class ReportGenerator {
    
    fun generateHTMLReport(graph: ProjectGraph): String {
        val issuesCount = graph.nodes.sumOf { it.issues.size }
        val errorCount = graph.nodes.sumOf { it.issues.count { it.severity == IssueSeverity.ERROR } }
        val warningCount = graph.nodes.sumOf { it.issues.count { it.severity == IssueSeverity.WARNING } }
        
        val htmlTemplate = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Aegis Debug Report - ${graph.metadata.projectName}</title>
                <style>
                    body {
                        font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                        background-color: #0d1117;
                        color: #e6edf3;
                        margin: 0;
                        padding: 20px;
                    }
                    .container {
                        max-width: 1200px;
                        margin: 0 auto;
                    }
                    header {
                        text-align: center;
                        padding: 20px 0;
                        border-bottom: 1px solid #21262d;
                        margin-bottom: 30px;
                    }
                    h1 {
                        color: #79c0ff;
                        margin-bottom: 10px;
                    }
                    .stats {
                        display: flex;
                        justify-content: center;
                        gap: 30px;
                        flex-wrap: wrap;
                        margin: 20px 0;
                    }
                    .stat-card {
                        background: #161b22;
                        border: 1px solid #30363d;
                        border-radius: 8px;
                        padding: 15px;
                        text-align: center;
                        min-width: 120px;
                    }
                    .stat-card.error { color: #f85149; }
                    .stat-card.warning { color: #d29922; }
                    .stat-card.health { color: #3fb950; }
                    .stat-card .value {
                        font-size: 24px;
                        font-weight: bold;
                    }
                    .stat-card .label {
                        font-size: 12px;
                        opacity: 0.8;
                        text-transform: uppercase;
                        letter-spacing: 0.5px;
                    }
                    .issues-section {
                        margin-top: 30px;
                    }
                    .issues-list {
                        display: grid;
                        grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
                        gap: 20px;
                        margin-top: 20px;
                    }
                    .issue-card {
                        background: #161b22;
                        border: 1px solid #30363d;
                        border-radius: 8px;
                        padding: 15px;
                    }
                    .issue-card.error { border-left: 4px solid #f85149; }
                    .issue-card.warning { border-left: 4px solid #d29922; }
                    .issue-card.info { border-left: 4px solid #3fb950; }
                    .issue-card h3 {
                        margin: 0 0 10px 0;
                        font-size: 16px;
                    }
                    .issue-card .severity {
                        font-size: 12px;
                        font-weight: bold;
                        text-transform: uppercase;
                        letter-spacing: 0.5px;
                        margin-bottom: 5px;
                    }
                    .issue-card .file-path {
                        font-size: 11px;
                        opacity: 0.7;
                        margin-bottom: 8px;
                        word-break: break-all;
                    }
                    .issue-card .description {
                        font-size: 13px;
                        line-height: 1.5;
                    }
                    .node-details {
                        display: grid;
                        grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
                        gap: 15px;
                        margin-top: 20px;
                    }
                    .node-card {
                        background: #161b22;
                        border: 1px solid #30363d;
                        border-radius: 8px;
                        padding: 12px;
                    }
                    .node-card h3 {
                        font-size: 14px;
                        margin: 0 0 8px 0;
                        color: #79c0ff;
                    }
                    .node-card .file-path {
                        font-size: 10px;
                        opacity: 0.7;
                        margin-bottom: 5px;
                    }
                    .node-card .issues-count {
                        font-size: 11px;
                        display: flex;
                        align-items: center;
                        gap: 4px;
                    }
                    footer {
                        text-align: center;
                        margin-top: 40px;
                        padding: 20px 0;
                        border-top: 1px solid #21262d;
                        font-size: 12px;
                        opacity: 0.6;
                    }
                    pre {
                        background: #161b22;
                        border: 1px solid #30363d;
                        border-radius: 4px;
                        padding: 10px;
                        font-size: 11px;
                        overflow: auto;
                        margin: 10px 0;
                    }
                    .issue-snippet {
                        margin-top: 8px;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <header>
                        <h1>Aegis Debug Report</h1>
                        <p>Project Analysis for ${graph.metadata.projectName}</p>
                        <p>Generated on ${java.time.LocalDateTime.now()}</p>
                        
                        <div class="stats">
                            <div class="stat-card health">
                                <div class="value">${graph.metadata.healthScore.toInt()}%</div>
                                <div class="label">Health Score</div>
                            </div>
                            <div class="stat-card error">
                                <div class="value">${errorCount}</div>
                                <div class="label">Errors</div>
                            </div>
                            <div class="stat-card warning">
                                <div class="value">${warningCount}</div>
                                <div class="label">Warnings</div>
                            </div>
                            <div class="stat-card">
                                <div class="value">${issuesCount}</div>
                                <div class="label">Total Issues</div>
                            </div>
                        </div>
                    </header>

                    <section class="issues-section">
                        <h2>Issues Analysis</h2>
                        <div class="issues-list">
                            ${buildIssuesList(graph)}
                        </div>
                    </section>

                    <section class="nodes-section">
                        <h2>Module Overview</h2>
                        <div class="node-details">
                            ${buildNodesOverview(graph)}
                        </div>
                    </section>

                    <footer>
                        <p>Generated by Aegis Debug IntelliJ Plugin • ${graph.metadata.projectName}</p>
                    </footer>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        return htmlTemplate
    }
    
    private fun buildIssuesList(graph: ProjectGraph): String {
        val allIssues = mutableListOf<Issue>()
        graph.nodes.forEach { node ->
            allIssues.addAll(node.issues)
        }
        
        return allIssues.joinToString("\n") { issue ->
            val severityClass = when (issue.severity) {
                IssueSeverity.ERROR -> "error"
                IssueSeverity.WARNING -> "warning"
                else -> "info"
            }
            
            """
                <div class="issue-card $severityClass">
                    <div class="severity">${issue.severity}</div>
                    <h3>${issue.title}</h3>
                    <div class="file-path">${issue.filePath}</div>
                    <div class="description">${issue.description}</div>
                    ${if (issue.codeSnippet.isNotBlank()) """
                        <div class="issue-snippet">
                            <pre>${issue.codeSnippet}</pre>
                        </div>
                    """ else ""}
                </div>
            """.trimIndent()
        }
    }
    
    private fun buildNodesOverview(graph: ProjectGraph): String {
        return graph.nodes.joinToString("\n") { node ->
            val statusClass = when (node.status) {
                NodeStatus.ERROR -> "error"
                NodeStatus.WARNING -> "warning"
                else -> "health"
            }
            val statusText = when (node.status) {
                NodeStatus.ERROR -> "Error"
                NodeStatus.WARNING -> "Warning"
                else -> "Healthy"
            }
            """
                <div class="node-card">
                    <h3>${node.name}</h3>
                    <div class="file-path">${node.filePath.replace("/","/").split("/").takeLast(3).joinToString("/")}</div>
                    <div class="status">Status: $statusText</div>
                    <div class="issues-count">Issues: ${node.issues.size}</div>
                    ${if (node.complexity > 5) """
                        <div class="complexity">Complexity: ${node.complexity}</div>
                    """ else ""}
                </div>
            """.trimIndent()
        }
    }
}