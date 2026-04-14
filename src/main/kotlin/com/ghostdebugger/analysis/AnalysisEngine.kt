package com.ghostdebugger.analysis

import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.analysis.analyzers.AIAnalyzer
import com.ghostdebugger.analysis.analyzers.AsyncFlowAnalyzer
import com.ghostdebugger.analysis.analyzers.CircularDependencyAnalyzer
import com.ghostdebugger.analysis.analyzers.ComplexityAnalyzer
import com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzer
import com.ghostdebugger.analysis.analyzers.StateInitAnalyzer
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.AnalysisResult
import com.ghostdebugger.model.EngineProvider
import com.ghostdebugger.model.EngineStatus
import com.ghostdebugger.model.EngineStatusPayload
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueSource
import com.ghostdebugger.model.ProjectMetrics
import com.ghostdebugger.model.RiskItem
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.openapi.diagnostic.logger

class AnalysisEngine(
    private val settingsProvider: () -> GhostDebuggerSettings.State =
        { GhostDebuggerSettings.getInstance().snapshot() },
    private val apiKeyProvider: () -> String? = { ApiKeyManager.getApiKey() },
    private val aiPassRunner: AiPassRunner = AiPassRunner { ctx, key ->
        AIAnalyzer(key).analyze(ctx)
    }
) {
    private val log = logger<AnalysisEngine>()

    private val analyzers: List<Analyzer> = listOf(
        NullSafetyAnalyzer(),
        StateInitAnalyzer(),
        AsyncFlowAnalyzer(),
        CircularDependencyAnalyzer(),
        ComplexityAnalyzer()
    )

    suspend fun analyze(context: AnalysisContext): AnalysisResult {
        val settings = settingsProvider()
        val limitedContext = context.limitTo(settings.maxFilesToAnalyze)

        val staticIssues = runStaticPass(limitedContext)
        val (aiIssues, engineStatus) = runAiPass(limitedContext, settings)
        val merged = mergeIssues(staticIssues + aiIssues)

        val metrics = ProjectMetrics(
            totalFiles = limitedContext.parsedFiles.size,
            totalIssues = merged.size,
            errorCount = merged.count { it.severity == IssueSeverity.ERROR },
            warningCount = merged.count { it.severity == IssueSeverity.WARNING },
            infoCount = merged.count { it.severity == IssueSeverity.INFO },
            healthScore = calculateHealthScore(limitedContext.parsedFiles.size, merged),
            avgComplexity = limitedContext.graph.getAllNodes()
                .map { it.complexity.toDouble() }
                .average()
                .takeIf { !it.isNaN() } ?: 0.0
        )

        val hotspots = merged.groupBy { it.filePath }
            .filter { (_, issues) -> issues.size >= 2 }
            .keys.toList()

        val risks = merged.filter { it.severity == IssueSeverity.ERROR }
            .map { RiskItem(nodeId = it.filePath, riskLevel = "HIGH", reason = it.title) }

        return AnalysisResult(
            issues = merged,
            metrics = metrics,
            hotspots = hotspots,
            risks = risks,
            engineStatus = engineStatus
        )
    }

    private fun runStaticPass(context: AnalysisContext): List<Issue> {
        val collected = mutableListOf<Issue>()
        for (analyzer in analyzers) {
            try {
                val produced = analyzer.analyze(context).map { issue ->
                    issue.copy(
                        ruleId = issue.ruleId ?: analyzer.ruleId,
                        sources = if (issue.sources.isNotEmpty()) issue.sources
                                  else listOf(IssueSource.STATIC),
                        providers = if (issue.providers.isNotEmpty()) issue.providers
                                    else listOf(EngineProvider.STATIC),
                        confidence = issue.confidence ?: 1.0
                    )
                }
                log.info("${analyzer.name}: produced ${produced.size} issues")
                collected.addAll(produced)
            } catch (e: Exception) {
                log.warn("Analyzer ${analyzer.name} failed; continuing", e)
            }
        }
        return collected
    }

    private suspend fun runAiPass(
        context: AnalysisContext,
        settings: GhostDebuggerSettings.State
    ): Pair<List<Issue>, EngineStatusPayload> {
        return when (settings.aiProvider) {
            AIProvider.NONE -> emptyList<Issue>() to EngineStatusPayload(
                provider = "STATIC",
                status = EngineStatus.DISABLED,
                message = "AI provider disabled; static-only run."
            )
            AIProvider.OLLAMA -> emptyList<Issue>() to EngineStatusPayload(
                provider = "OLLAMA",
                status = EngineStatus.FALLBACK_TO_STATIC,
                message = "Ollama integration not yet available; continuing with static-only results."
            )
            AIProvider.OPENAI -> runOpenAiPass(context, settings)
        }
    }

    private suspend fun runOpenAiPass(
        context: AnalysisContext,
        settings: GhostDebuggerSettings.State
    ): Pair<List<Issue>, EngineStatusPayload> {
        if (!settings.allowCloudUpload) {
            return emptyList<Issue>() to EngineStatusPayload(
                provider = "OPENAI",
                status = EngineStatus.DISABLED,
                message = "Cloud upload is off; static-only run."
            )
        }
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return emptyList<Issue>() to EngineStatusPayload(
                provider = "OPENAI",
                status = EngineStatus.FALLBACK_TO_STATIC,
                message = "No OpenAI API key configured; continuing with static-only results."
            )
        }
        if (settings.maxAiFiles <= 0) {
            return emptyList<Issue>() to EngineStatusPayload(
                provider = "OPENAI",
                status = EngineStatus.DISABLED,
                message = "maxAiFiles = 0; AI pass skipped."
            )
        }

        val aiContext = context.limitAiFilesTo(settings.maxAiFiles)
        val started = System.currentTimeMillis()
        val result = runCatching { aiPassRunner.run(aiContext, apiKey) }
        val latency = System.currentTimeMillis() - started

        return result.fold(
            onSuccess = { issues ->
                val tagged = issues.map { it.copy(
                    sources = if (it.sources.isNotEmpty() &&
                                  it.sources != listOf(IssueSource.STATIC)) it.sources
                              else listOf(IssueSource.AI_CLOUD),
                    providers = if (it.providers.isNotEmpty() &&
                                    it.providers != listOf(EngineProvider.STATIC)) it.providers
                                else listOf(EngineProvider.OPENAI),
                    confidence = it.confidence ?: 0.7
                ) }
                tagged to EngineStatusPayload(
                    provider = "OPENAI",
                    status = EngineStatus.ONLINE,
                    message = "OpenAI pass ok (${tagged.size} issues).",
                    latencyMs = latency
                )
            },
            onFailure = { e ->
                log.warn("OpenAI pass failed; static results will ship", e)
                emptyList<Issue>() to EngineStatusPayload(
                    provider = "OPENAI",
                    status = EngineStatus.FALLBACK_TO_STATIC,
                    message = "OpenAI unreachable (${e.javaClass.simpleName}); static results returned.",
                    latencyMs = latency
                )
            }
        )
    }

    private fun mergeIssues(issues: List<Issue>): List<Issue> =
        issues.groupBy { it.fingerprint() }.map { (_, group) ->
            val base = group.maxByOrNull { it.confidence ?: 0.0 } ?: group.first()
            base.copy(
                sources = group.flatMap { it.sources }.distinct(),
                providers = group.flatMap { it.providers }.distinct(),
                confidence = group.mapNotNull { it.confidence }.maxOrNull()
            )
        }

    private fun calculateHealthScore(totalFiles: Int, issues: List<Issue>): Double {
        if (totalFiles == 0) return 100.0
        val errorPenalty = issues.count { it.severity == IssueSeverity.ERROR } * 15.0
        val warningPenalty = issues.count { it.severity == IssueSeverity.WARNING } * 5.0
        return (100.0 - errorPenalty - warningPenalty).coerceIn(0.0, 100.0)
    }
}
