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

class OllamaService(
    internal val endpoint: String,
    internal val model: String,
    private val timeoutMs: Long = 30_000,
    cacheTtlSeconds: Long = 3600,
    private val cacheEnabled: Boolean = true,
    cacheMaxEntries: Int = 256
) : AIService {
    private val log = logger<OllamaService>()
    private val cache = AICache(cacheTtlSeconds, cacheMaxEntries)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun detectIssues(filePath: String, fileContent: String): List<Issue> {
        return try {
            val raw = callOllama(PromptTemplates.detectIssues(filePath, fileContent))
            when (val r = AiJsonExtractor.extract(raw)) {
                is AiJsonExtractor.Result.Ok -> AiIssueMapper.mapIssues(r.element, filePath, fileContent)
                AiJsonExtractor.Result.Empty -> {
                    log.warn("Ollama JSON extraction returned Empty for $filePath (len=${raw.length})")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            log.warn("Ollama detectIssues failed for $filePath", e)
            emptyList()
        }
    }

    override suspend fun explainIssue(issue: Issue, codeSnippet: String): String {
        val cacheKey = if (cacheEnabled) cache.computeKey(codeSnippet + issue.type.name, "explain") else null
        if (cacheKey != null) {
            cache.get(cacheKey)?.let { return it }
        }
        val response = callOllama(PromptTemplates.explainIssue(issue, codeSnippet))
        if (cacheKey != null) {
            cache.put(cacheKey, response)
        }
        return response
    }

    override suspend fun suggestFix(issue: Issue, codeSnippet: String): CodeFix {
        val cacheKey = if (cacheEnabled) cache.computeKey(codeSnippet + issue.type.name, "fix") else null
        if (cacheKey != null) {
            cache.get(cacheKey)?.let { return parseFixResponse(it, issue, codeSnippet) }
        }
        val response = callOllama(PromptTemplates.suggestFix(issue, codeSnippet))
        if (cacheKey != null) {
            cache.put(cacheKey, response)
        }
        return parseFixResponse(response, issue, codeSnippet)
    }

    override suspend fun explainSystem(graph: ProjectGraph): String {
        val cacheKey = if (cacheEnabled) cache.computeKey(graph.metadata.projectName + graph.nodes.size, "system") else null
        if (cacheKey != null) {
            cache.get(cacheKey)?.let { return it }
        }
        val response = callOllama(PromptTemplates.explainSystem(graph))
        if (cacheKey != null) {
            cache.put(cacheKey, response)
        }
        return response
    }

    private suspend fun callOllama(userPrompt: String): String = withContext(Dispatchers.IO) {
        val requestBody = json.encodeToString(
            OllamaChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage(role = "system", content = SystemPrompts.DEBUGGER.trimIndent()),
                    ChatMessage(role = "user", content = userPrompt)
                )
            )
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$endpoint/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string() ?: "unknown error"
            log.error("Ollama API error: ${response.code} — $body")
            throw RuntimeException("Ollama API error: ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty response from Ollama")

        json.decodeFromString<OllamaChatResponse>(responseBody).message.content
    }

    // ── Response parsers (mirrors OpenAIService private methods) ─────────────

    private fun parseFixResponse(raw: String, issue: Issue, originalSnippet: String): CodeFix {
        val explanation = raw.lines()
            .firstOrNull { it.startsWith("EXPLANATION:") }
            ?.removePrefix("EXPLANATION:")?.trim()
            ?: raw.substringBefore("\n").take(200)
        val codeBlockRegex = Regex("""```(?:\w*)\n([\s\S]*?)```""")
        val fixedCode = codeBlockRegex.find(raw)?.groupValues?.get(1)?.trim() ?: originalSnippet
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
}
