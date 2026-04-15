package com.ghostdebugger.ai

import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AIServiceFactoryTest {
    private fun state(provider: AIProvider) = GhostDebuggerSettings.State(aiProvider = provider)

    @Test fun `NONE returns null`() {
        assertNull(AIServiceFactory.create(state(AIProvider.NONE), null))
    }

    @Test fun `OPENAI with blank key returns null`() {
        assertNull(AIServiceFactory.create(state(AIProvider.OPENAI), ""))
        assertNull(AIServiceFactory.create(state(AIProvider.OPENAI), null))
    }

    @Test fun `OPENAI with key returns OpenAIService`() {
        val svc = AIServiceFactory.create(state(AIProvider.OPENAI), "sk-test")
        assertNotNull(svc)
        assertInstanceOf(OpenAIService::class.java, svc)
    }

    @Test fun `OLLAMA returns OllamaService regardless of key`() {
        val svc = AIServiceFactory.create(state(AIProvider.OLLAMA), null)
        assertNotNull(svc)
        assertInstanceOf(OllamaService::class.java, svc)
    }

    @Test fun `OLLAMA service uses settings endpoint and model`() {
        val s = GhostDebuggerSettings.State(
            aiProvider    = AIProvider.OLLAMA,
            ollamaEndpoint = "http://custom:11434",
            ollamaModel   = "mistral"
        )
        val svc = AIServiceFactory.create(s, null) as OllamaService
        // OllamaService exposes endpoint/model for testing — see §15.3
        assertEquals("http://custom:11434", svc.endpoint)
        assertEquals("mistral", svc.model)
    }
}
