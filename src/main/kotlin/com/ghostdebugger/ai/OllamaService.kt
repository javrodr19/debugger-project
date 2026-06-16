package com.ghostdebugger.ai

import com.ghostdebugger.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class OllamaService(
    internal val endpoint: String,
    internal val model: String,
    timeoutMs: Long = 30_000,
    cacheTtlSeconds: Long = 3600,
    cacheEnabled: Boolean = true,
    cacheMaxEntries: Int = 256
) : BaseAIService(timeoutMs, cacheTtlSeconds, cacheEnabled, cacheMaxEntries) {

    override suspend fun callModel(
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean
    ): String = withContext(Dispatchers.IO) {
        val requestBody = json.encodeToString(
            OllamaChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt.trimIndent()),
                    ChatMessage(role = "user", content = userPrompt)
                )
            )
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$endpoint/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: "unknown error"
                log.error("Ollama API error: ${response.code} — $body")
                throw RuntimeException("Ollama API error: ${response.code}")
            }
            val responseBody = response.body?.string()
                ?: throw RuntimeException("Empty response from Ollama")
            json.decodeFromString<OllamaChatResponse>(responseBody).message.content
        }
    }

    override suspend fun callModelStreaming(
        systemPrompt: String,
        userPrompt: String,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val requestBody = json.encodeToString(
            OllamaChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt.trimIndent()),
                    ChatMessage(role = "user", content = userPrompt)
                ),
                stream = true
            )
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$endpoint/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val fullText = StringBuilder()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: "unknown error"
                log.error("Ollama API error: ${response.code} — $body")
                throw RuntimeException("Ollama API error: ${response.code}")
            }
            val source = response.body?.source() ?: throw RuntimeException("Empty response from Ollama")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isNotBlank()) {
                    try {
                        val chunk = json.decodeFromString<OllamaChatResponse>(line)
                        val text = chunk.message.content
                        fullText.append(text)
                        onToken(text)
                    } catch (e: Exception) {
                        if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                        // ignore malformed lines
                    }
                }
            }
        }
        fullText.toString()
    }
}
