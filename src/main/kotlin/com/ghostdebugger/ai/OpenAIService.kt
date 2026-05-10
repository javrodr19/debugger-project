package com.ghostdebugger.ai

import com.ghostdebugger.model.ChatCompletionRequest
import com.ghostdebugger.model.ChatCompletionResponse
import com.ghostdebugger.model.ChatMessage
import com.ghostdebugger.model.ResponseFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class OpenAIService(
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val baseUrl: String = "https://api.openai.com/v1",
    timeoutMs: Long = 60_000,
    cacheTtlSeconds: Long = 3600,
    cacheEnabled: Boolean = true,
    cacheMaxEntries: Int = 256
) : BaseAIService(timeoutMs, cacheTtlSeconds, cacheEnabled, cacheMaxEntries) {

    override suspend fun callModel(
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean
    ): String = withContext(Dispatchers.IO) {
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt.trimIndent()),
                ChatMessage(role = "user", content = userPrompt)
            ),
            max_tokens = 2000,
            temperature = 0.2,
            response_format = if (jsonMode) ResponseFormat("json_object") else null
        )

        val requestBody = json.encodeToString(request)
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: "Unknown error"
                log.error("OpenAI API error: ${response.code} - $body")
                throw RuntimeException("OpenAI API communication failure: ${response.code}")
            }
            val responseBody = response.body?.string()
                ?: throw RuntimeException("Empty response from OpenAI")
            val completionResponse = try {
                json.decodeFromString<ChatCompletionResponse>(responseBody)
            } catch (e: Exception) {
                log.error("Failed to decode OpenAI response: $responseBody", e)
                throw RuntimeException("Format error in OpenAI response")
            }
            completionResponse.choices.firstOrNull()?.message?.content
                ?: throw RuntimeException("No content in OpenAI response")
        }
    }
}
