package com.ghostdebugger.analysis

import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.ai.OllamaService
import com.ghostdebugger.analysis.analyzers.AIAnalyzer
import com.ghostdebugger.analysis.analyzers.AsyncFlowAnalyzer
import com.ghostdebugger.analysis.analyzers.CircularDependencyAnalyzer
import com.ghostdebugger.analysis.analyzers.ComplexityAnalyzer
import com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzer
import com.ghostdebugger.analysis.analyzers.StateInitAnalyzer
import com.ghostdebugger.model.*
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.coroutines.*

class AnalysisEngine(
    private val settingsProvider: () -> GhostDebuggerSettings.State =
        { GhostDebuggerSettings.getInstance().snapshot() },
    private val apiKeyProvider: () -> String? = { ApiKeyManager.getApiKey() },
    private val progress: ProgressIndicator? = null,
    private val aiPassRunner: AiPassRunner = AiPassRunner { ctx, key ->
        val settings = settingsProvider()
        val service = com.ghostdebugger.ai.AIServiceFactory.create(settings, key)
            ?: return@AiPassRunner emptyList()
        AIAnalyzer(service, progress).analyze(ctx)
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

    suspend fun analyze(context: AnalysisContext, indicator: ProgressIndicator? = null): AnalysisResult {
        val settings = settingsProvider()
        val limitedContext = context.limitTo(settings.maxFilesToAnalyze)

        indicator?.text = "Running static analysis..."
        val staticIssues = runStaticPass(limitedContext, indicator)
        
        indicator?.checkCanceled()
        
        indicator?.text = "Running AI analysis..."
        val (aiIssues, engineStatus) = runAiPass(limitedContext, settings, indicator)
        
        indicator?.checkCanceled()
        
        val merged = mergeIssues(staticIssues + aiIssues)

        // Drop file content to save RAM once analysis is done
        limitedContext.parsedFiles.forEach { file ->
            // Trigger lines computation before dropping content
            val _lines = file.lines 
            file.content = ""
        }

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

    private suspend fun runStaticPass(context: AnalysisContext, indicator: ProgressIndicator?): List<Issue> {
        val collected = mutableListOf<Issue>()
        for (analyzer in analyzers) {
            indicator?.checkCanceled()
            indicator?.text2 = "Analyzer: ${analyzer.name}"
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
        settings: GhostDebuggerSettings.State,
        indicator: ProgressIndicator?
    ): Pair<List<Issue>, EngineStatusPayload> {
        return when (settings.aiProvider) {
            AIProvider.NONE -> emptyList<Issue>() to EngineStatusPayload(
                provider = "STATIC",
                status = EngineStatus.DISABLED,
                message = "AI provider disabled; static-only run."
            )
            AIProvider.OLLAMA -> runOllamaPass(context, settings, indicator)
            AIProvider.OPENAI -> runOpenAiPass(context, settings, indicator)
        }
    }

    private suspend fun runOpenAiPass(
        context: AnalysisContext,
        settings: GhostDebuggerSettings.State,
        indicator: ProgressIndicator?
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

        indicator?.checkCanceled()
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
                    message = "Cannot reach OpenAI. Check your network or switch to Ollama in Settings.",
                    latencyMs = latency
                )
            }
        )
    }

    private suspend fun runOllamaPass(
        context: AnalysisContext,
        settings: GhostDebuggerSettings.State,
        indicator: ProgressIndicator?
    ): Pair<List<Issue>, EngineStatusPayload> {
        if (settings.maxAiFiles <= 0) {
            return emptyList<Issue>() to EngineStatusPayload(
                provider = "OLLAMA",
                status   = EngineStatus.DISABLED,
                message  = "AI analysis is limited to 0 files in settings."
            )
        }

        indicator?.checkCanceled()
        val aiContext = context.limitAiFilesTo(settings.maxAiFiles)
        val ollamaService = OllamaService(
            endpoint        = settings.ollamaEndpoint,
            model           = settings.ollamaModel,
            timeoutMs       = settings.aiTimeoutMs,
            cacheTtlSeconds = settings.cacheTtlSeconds,
            cacheEnabled    = settings.cacheEnabled,
            cacheMaxEntries = settings.aiCacheMaxEntries
        )
        val started = System.currentTimeMillis()
        val result = runCatching {
            aiContext.parsedFiles.flatMap { file ->
                indicator?.checkCanceled()
                indicator?.text2 = "Ollama: ${file.path.substringAfterLast('/')}"
                ollamaService.detectIssues(file.path, file.content)
            }
        }
        val latency = System.currentTimeMillis() - started

        return result.fold(
            onSuccess = { issues ->
                val tagged = issues.map { it.copy(
                    sources   = if (it.sources.isNotEmpty() && it.sources != listOf(IssueSource.STATIC))
                                    it.sources else listOf(IssueSource.AI_LOCAL),
                    providers = if (it.providers.isNotEmpty() && it.providers != listOf(EngineProvider.STATIC))
                                    it.providers else listOf(EngineProvider.OLLAMA),
                    confidence = it.confidence ?: 0.7
                ) }
                tagged to EngineStatusPayload(
                    provider  = "OLLAMA",
                    status    = EngineStatus.ONLINE,
                    message   = "Ollama analysis complete (${tagged.size} issues).",
                    latencyMs = latency
                )
            },
            onFailure = { e ->
                log.warn("Ollama pass failed; static results will ship", e)
                emptyList<Issue>() to EngineStatusPayload(
                    provider  = "OLLAMA",
                    status    = EngineStatus.FALLBACK_TO_STATIC,
                    message   = "Ollama unreachable. Ensure the Ollama server is running locally.",
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
