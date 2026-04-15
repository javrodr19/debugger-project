package com.ghostdebugger.ai

import com.ghostdebugger.ai.prompts.PromptTemplates
import com.ghostdebugger.ai.prompts.SystemPrompts
import com.ghostdebugger.model.*
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
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
            val content = callOllama(PromptTemplates.detectIssues(filePath, fileContent))
            parseDetectIssuesResponse(content, filePath, fileContent)
        } catch (e: Exception) {
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

    private fun parseDetectIssuesResponse(raw: String, filePath: String, fileContent: String): List<Issue> {
        // same parse logic as OpenAIService.detectIssues — extract JSON array from raw string
        return try {
            val jsonString = if (raw.contains("[")) {
                "[" + raw.substringAfter("[").substringBeforeLast("]") + "]"
            } else {
                raw
            }
            val element = json.parseToJsonElement(jsonString)
            val arr = when (element) {
                is kotlinx.serialization.json.JsonObject ->
                    element["issues"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
                is kotlinx.serialization.json.JsonArray -> element
                else -> kotlinx.serialization.json.JsonArray(emptyList())
            }
            arr.mapNotNull { item ->
                try {
                    val obj = item.jsonObject
                    Issue(
                        id = UUID.randomUUID().toString(),
                        type = runCatching {
                            IssueType.valueOf(obj["type"]?.jsonPrimitive?.content ?: "ARCHITECTURE")
                        }.getOrDefault(IssueType.ARCHITECTURE),
                        severity = runCatching {
                            IssueSeverity.valueOf(obj["severity"]?.jsonPrimitive?.content ?: "WARNING")
                        }.getOrDefault(IssueSeverity.WARNING),
                        title = obj["title"]?.jsonPrimitive?.content ?: "Detected Issue",
                        description = obj["description"]?.jsonPrimitive?.content ?: "",
                        filePath = filePath,
                        line = obj["line"]?.jsonPrimitive?.intOrNull ?: 1,
                        codeSnippet = getSnippet(fileContent, obj["line"]?.jsonPrimitive?.intOrNull ?: 1),
                        affectedNodes = listOf(filePath)
                    )
                } catch (e: Exception) {
                    log.warn("Failed to parse individual Ollama issue", e)
                    null
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to parse Ollama detectIssues output: $raw", e)
            emptyList()
        }
    }

    private fun getSnippet(content: String, lineNum: Int): String {
        val lines = content.lines()
        val start = maxOf(0, lineNum - 3)
        val end = minOf(lines.size, lineNum + 2)
        return lines.subList(start, end).joinToString("\n")
    }

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
