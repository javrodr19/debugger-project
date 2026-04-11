package com.ghostdebugger.analysis

import com.ghostdebugger.analysis.analyzers.*
import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.model.*
import com.intellij.openapi.diagnostic.logger

class AnalysisEngine {
    private val log = logger<AnalysisEngine>()

    private val analyzers: List<Analyzer> = listOf(
        NullSafetyAnalyzer(),
        StateInitAnalyzer(),
        AsyncFlowAnalyzer(),
        CircularDependencyAnalyzer(),
        ComplexityAnalyzer()
    )

    suspend fun analyze(context: AnalysisContext): AnalysisResult {
        val allIssues = mutableListOf<Issue>()
        val apiKey = ApiKeyManager.getApiKey()
        
        if (!apiKey.isNullOrBlank()) {
            // Priority: Send context to deep AI Analyzer if enabled
            log.info("AI Analysis mode enabled. Starting deep scan...")
            allIssues.addAll(AIAnalyzer(apiKey).analyze(context))
            
            // Still run CircularDependency and Complexity as they are graph level tasks
            allIssues.addAll(CircularDependencyAnalyzer().analyze(context))
            allIssues.addAll(ComplexityAnalyzer().analyze(context))
        } else {
            log.info("No API Key detected. Falling back to static pattern matchers.")
            for (analyzer in analyzers) {
                try {
                    val issues = analyzer.analyze(context)
                    log.info("${analyzer.name}: found ${issues.size} issues")
                    allIssues.addAll(issues)
                } catch (e: Exception) {
                    log.warn("Analyzer ${analyzer.name} failed", e)
                }
            }
        }

        // Deduplicate issues (same file + line + type)
        val deduped = allIssues.distinctBy { Triple(it.filePath, it.line, it.type) }

        val metrics = ProjectMetrics(
            totalFiles = context.parsedFiles.size,
            totalIssues = deduped.size,
            errorCount = deduped.count { it.severity == IssueSeverity.ERROR },
            warningCount = deduped.count { it.severity == IssueSeverity.WARNING },
            infoCount = deduped.count { it.severity == IssueSeverity.INFO },
            healthScore = calculateHealthScore(context.parsedFiles.size, deduped),
            avgComplexity = context.graph.getAllNodes().map { it.complexity.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0
        )

        val hotspots = deduped
            .groupBy { it.filePath }
            .filter { (_, issues) -> issues.size >= 2 }
            .keys
            .toList()

        val risks = deduped
            .filter { it.severity == IssueSeverity.ERROR }
            .map { RiskItem(nodeId = it.filePath, riskLevel = "HIGH", reason = it.title) }

        return AnalysisResult(
            issues = deduped,
            metrics = metrics,
            hotspots = hotspots,
            risks = risks
        )
    }

    private fun calculateHealthScore(totalFiles: Int, issues: List<Issue>): Double {
        if (totalFiles == 0) return 100.0
        val errorPenalty = issues.count { it.severity == IssueSeverity.ERROR } * 15.0
        val warningPenalty = issues.count { it.severity == IssueSeverity.WARNING } * 5.0
        return (100.0 - errorPenalty - warningPenalty).coerceIn(0.0, 100.0)
    }
}
