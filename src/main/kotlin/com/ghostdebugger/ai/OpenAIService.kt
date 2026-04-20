package com.ghostdebugger.ai

import com.ghostdebugger.ai.prompts.PromptTemplates
import com.ghostdebugger.ai.prompts.SystemPrompts
import com.ghostdebugger.model.*
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenAIService(
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val timeoutMs: Long = 60_000,
    cacheTtlSeconds: Long = 3600,
    private val cacheEnabled: Boolean = true,
    cacheMaxEntries: Int = 256
) : AIService {
    private val log = logger<OpenAIService>()
    private val cache = AICache(cacheTtlSeconds, cacheMaxEntries)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun detectIssues(filePath: String, fileContent: String): List<Issue> {
        val prompt = PromptTemplates.detectIssues(filePath, fileContent)
        val rawResponse = callOpenAI(prompt, SystemPrompts.DEBUGGER, jsonMode = true)
        return when (val r = AiJsonExtractor.extract(rawResponse)) {
            is AiJsonExtractor.Result.Ok -> AiIssueMapper.mapIssues(r.element, filePath, fileContent)
            AiJsonExtractor.Result.Empty -> {
                log.warn("OpenAI JSON extraction returned Empty for $filePath (len=${rawResponse.length})")
                emptyList()
            }
        }
    }

    override suspend fun explainIssue(issue: Issue, codeSnippet: String): String {
        val cacheKey = if (cacheEnabled) cache.computeKey(codeSnippet + issue.type.name, "explain") else null
        if (cacheKey != null) {
            cache.get(cacheKey)?.let { return it }
        }

        val prompt = PromptTemplates.explainIssue(issue, codeSnippet)
        val response = callOpenAI(prompt, SystemPrompts.DEBUGGER)
        if (cacheKey != null) {
            cache.put(cacheKey, response)
        }
        return response
    }

    override suspend fun suggestFix(issue: Issue, codeSnippet: String): CodeFix {
        val cacheKey = if (cacheEnabled) cache.computeKey(codeSnippet + issue.type.name, "fix") else null
        if (cacheKey != null) {
            cache.get(cacheKey)?.let {
                return parseFixResponse(it, issue, codeSnippet)
            }
        }
        val prompt = PromptTemplates.suggestFix(issue, codeSnippet)
        val response = callOpenAI(prompt, SystemPrompts.DEBUGGER)
        if (cacheKey != null) {
            cache.put(cacheKey, response)
        }
        return parseFixResponse(response, issue, codeSnippet)
    }

    private fun parseFixResponse(rawResponse: String, issue: Issue, originalSnippet: String): CodeFix {
        val explanation = rawResponse.lines()
            .firstOrNull { it.startsWith("EXPLANATION:") }
            ?.removePrefix("EXPLANATION:")?.trim()
            ?: rawResponse.substringBefore("\n").take(200)

        val codeBlockRegex = Regex("""```(?:\w*)\n([\s\S]*?)```""")
        val fixedCode = codeBlockRegex.find(rawResponse)?.groupValues?.get(1)?.trim()
            ?: originalSnippet

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = explanation,
            originalCode = originalSnippet,
            fixedCode = fixedCode,
            filePath = issue.filePath,
            lineStart = issue.line,
            lineEnd = issue.line + originalSnippet.lines().size,
            isDeterministic = false,
            confidence = 0.7
        )
    }

    override suspend fun explainSystem(graph: ProjectGraph): String {
        val cacheKey = if (cacheEnabled) cache.computeKey(graph.metadata.projectName + graph.nodes.size, "system") else null
        if (cacheKey != null) {
            cache.get(cacheKey)?.let { return it }
        }

        val prompt = PromptTemplates.explainSystem(graph)
        val response = callOpenAI(prompt, SystemPrompts.DEBUGGER)
        if (cacheKey != null) {
            cache.put(cacheKey, response)
        }
        return response
    }

    private suspend fun callOpenAI(userPrompt: String, systemPrompt: String, jsonMode: Boolean = false): String =
        withContext(Dispatchers.IO) {
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

            val response = httpClient.newCall(httpRequest).execute()

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
