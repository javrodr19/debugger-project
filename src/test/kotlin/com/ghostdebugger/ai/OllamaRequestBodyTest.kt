package com.ghostdebugger.ai

import com.ghostdebugger.model.ChatMessage
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Ollama's /api/chat streams unless the request says otherwise. BaseAIService's Json is configured
 * with encodeDefaults = false, so `stream = false` was omitted from the body and the server replied
 * with NDJSON that the single-shot decoder could not parse. One assertion on the serialized body
 * would have caught it, so here it is.
 */
class OllamaRequestBodyTest {

    @Test
    fun `the non-streaming request states stream explicitly`() {
        val json = Json { encodeDefaults = false }
        val body = json.encodeToString(
            OllamaChatRequest.serializer(),
            OllamaChatRequest(
                model = "llama3",
                messages = listOf(ChatMessage(role = "user", content = "hi")),
                stream = false,
            )
        )
        assertContains(body, "\"stream\"", message = "body must pin stream, got: $body")
    }
}
