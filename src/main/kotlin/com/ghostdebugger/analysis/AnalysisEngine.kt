package com.ghostdebugger.analysis

import com.ghostdebugger.ai.ApiKeyManager
import com.ghostdebugger.analysis.analyzers.*
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
        val (concurrency, labelPrefix) = when (settings.aiProvider) {
            com.ghostdebugger.settings.AIProvider.OLLAMA ->
                AIAnalyzer.DEFAULT_CONCURRENCY_LOCAL to "Ollama: "
            com.ghostdebugger.settings.AIProvider.OPENAI ->
                AIAnalyzer.DEFAULT_CONCURRENCY_CLOUD to "OpenAI: "
            else -> AIAnalyzer.DEFAULT_CONCURRENCY_CLOUD to "AI: "
        }
        AIAnalyzer(service, progress, concurrency, labelPrefix).analyze(ctx)
    },
    private val analyzers: List<Analyzer> = listOf(
        PsiSyntaxAnalyzer(),
        CompilationErrorAnalyzer(),
        NullSafetyAnalyzer(),
        StateInitAnalyzer(),
        AsyncFlowAnalyzer(),
        CircularDependencyAnalyzer(),
        ComplexityAnalyzer()
    )
) {
    private val log = logger<AnalysisEngine>()

    suspend fun analyze(context: AnalysisContext, indicator: ProgressIndicator? = null): AnalysisResult {
        val settings = settingsProvider()
        val limitedContext = context.limitTo(settings.maxFilesToAnalyze)

        indicator?.text = "Checking for syntax and compilation errors..."
        val earlyAnalyzers = analyzers.filterIsInstance<EarlyAnalyzer>()
        val earlyIssues = runStaticPass(earlyAnalyzers, limitedContext, indicator)
        
        indicator?.checkCanceled()

        // Compute broken files
        val brokenFilePaths = earlyIssues.map { it.filePath.replace("\\", "/") }.toSet()
        
        // Filter context for downstream passes
        val filteredFiles = limitedContext.parsedFiles.filterNot { 
            it.path.replace("\\", "/") in brokenFilePaths 
        }
        val filteredContext = limitedContext.copy(parsedFiles = filteredFiles)

        indicator?.text = "Running static analysis..."
        val lateAnalyzers = analyzers.filterNot { it is EarlyAnalyzer }
        val lateIssues = runStaticPass(lateAnalyzers, filteredContext, indicator)
        
        indicator?.checkCanceled()
        
        indicator?.text = "Running AI analysis..."
        val (aiIssues, engineStatus) = runAiPass(filteredContext, settings, indicator)
        
        indicator?.checkCanceled()
        
        val merged = mergeIssues(earlyIssues + lateIssues + aiIssues)

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

    private suspend fun runStaticPass(
        analyzersToRun: List<Analyzer>,
        context: AnalysisContext,
        indicator: ProgressIndicator?
    ): List<Issue> =
        coroutineScope {
            analyzersToRun.map { analyzer ->
                async(Dispatchers.Default) {
                    runOne(analyzer, context, indicator)
                }
            }.awaitAll().flatten()
        }

    private fun runOne(analyzer: Analyzer, context: AnalysisContext, indicator: ProgressIndicator?): List<Issue> {
        indicator?.checkCanceled()
        indicator?.text2 = "Analyzer: ${analyzer.name}"
        return try {
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
            produced
        } catch (e: Exception) {
            log.warn("Analyzer ${analyzer.name} failed; continuing", e)
            emptyList()
        }
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
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
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
        val started = System.currentTimeMillis()
        val result = runCatching { aiPassRunner.run(aiContext, "") }
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
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
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

