package com.ghostdebugger.ai

import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.openapi.diagnostic.logger

object AIServiceFactory {

    private val log = logger<AIServiceFactory>()

    /**
     * Returns the appropriate AIService for the given settings snapshot, or null if the
     * provider is NONE, OPENAI without a key, or OPENAI without cloud-upload consent.
     *
     * The consent check belongs here, and only here: this factory is the single chokepoint
     * every resolver (UIEventRouter, AnalysisOrchestrator, AnalysisEngine's AI pass) goes
     * through to construct an AIService. Gating it at this one call site means every caller —
     * including ones that don't exist yet — inherits the refusal, instead of relying on each
     * call site to remember the check individually, which is how the bypass happened.
     */
    fun create(settings: GhostDebuggerSettings.State, apiKey: String?): AIService? =
        when (settings.aiProvider) {
            AIProvider.NONE   -> null
            AIProvider.OPENAI -> {
                if (apiKey.isNullOrBlank()) null
                else if (!settings.allowCloudUpload) {
                    log.info("OpenAI requested but 'Allow cloud upload' is off; refusing to create the service")
                    null
                }
                else OpenAIService(
                    apiKey          = apiKey,
                    model           = settings.openAiModel,
                    timeoutMs       = settings.aiTimeoutMs,
                    cacheTtlSeconds = settings.cacheTtlSeconds,
                    cacheEnabled    = settings.cacheEnabled,
                    cacheMaxEntries = settings.aiCacheMaxEntries
                )
            }
            AIProvider.OLLAMA -> OllamaService(
                endpoint        = settings.ollamaEndpoint,
                model           = settings.ollamaModel,
                timeoutMs       = settings.aiTimeoutMs,
                cacheTtlSeconds = settings.cacheTtlSeconds,
                cacheEnabled    = settings.cacheEnabled,
                cacheMaxEntries = settings.aiCacheMaxEntries
            )
        }
}
