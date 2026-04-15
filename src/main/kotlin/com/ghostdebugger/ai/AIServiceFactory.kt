package com.ghostdebugger.ai

import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings

object AIServiceFactory {

    /**
     * Returns the appropriate AIService for the given settings snapshot,
     * or null if the provider is NONE or OPENAI without a key.
     */
    fun create(settings: GhostDebuggerSettings.State, apiKey: String?): AIService? =
        when (settings.aiProvider) {
            AIProvider.NONE   -> null
            AIProvider.OPENAI -> {
                if (apiKey.isNullOrBlank()) null
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
