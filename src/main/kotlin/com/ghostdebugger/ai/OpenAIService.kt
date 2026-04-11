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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenAIService(
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val baseUrl: String = "https://api.openai.com/v1"
) {
    private val log = logger<OpenAIService>()
    private val cache = AICache()
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun detectIssues(filePath: String, fileContent: String): List<Issue> {
        val prompt = PromptTemplates.detectIssues(filePath, fileContent)
        val rawResponse = callOpenAI(prompt, SystemPrompts.DEBUGGER, jsonMode = true)
        
        return try {
            val element = json.parseToJsonElement(rawResponse)
            val jsonArray = if (element is kotlinx.serialization.json.JsonObject) {
                if (element.containsKey("issues")) {
                    element["issues"]!!.jsonArray
                } else if (element.containsKey("type") || element.containsKey("severity")) {
                    // It returned a single issue object instead of an array
                    kotlinx.serialization.json.JsonArray(listOf(element))
                } else {
                    // Just an empty object
                    kotlinx.serialization.json.JsonArray(emptyList())
                }
            } else {
                element.jsonArray
            }
            
            jsonArray.map { item ->
                val obj = item.jsonObject
                Issue(
                    id = UUID.randomUUID().toString(),
                    type = try { IssueType.valueOf(obj["type"]?.jsonPrimitive?.content ?: "ARCHITECTURE") } catch (e: Exception) { IssueType.ARCHITECTURE },
                    severity = try { IssueSeverity.valueOf(obj["severity"]?.jsonPrimitive?.content ?: "WARNING") } catch(e:Exception){ IssueSeverity.WARNING },
                    title = obj["title"]?.jsonPrimitive?.content ?: "Detected Issue",
                    description = obj["description"]?.jsonPrimitive?.content ?: "No description provided.",
                    filePath = filePath,
                    line = obj["line"]?.jsonPrimitive?.int ?: 1,
                    codeSnippet = getSnippet(fileContent, obj["line"]?.jsonPrimitive?.int ?: 1),
                    affectedNodes = listOf(filePath)
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to parse detectIssues output: $rawResponse", e)
            emptyList()
        }
    }
    
    private fun getSnippet(content: String, lineNum: Int): String {
        val lines = content.lines()
        val start = maxOf(0, lineNum - 3)
        val end = minOf(lines.size, lineNum + 2)
        return lines.subList(start, end).joinToString("\n")
    }

    suspend fun explainIssue(issue: Issue, codeSnippet: String): String {
        val cacheKey = cache.computeKey(codeSnippet + issue.type.name, "explain")
        cache.get(cacheKey)?.let { return it }

        val prompt = PromptTemplates.explainIssue(issue, codeSnippet)
        val response = callOpenAI(prompt, SystemPrompts.DEBUGGER)
        cache.put(cacheKey, response)
        return response
    }

    suspend fun suggestFix(issue: Issue, codeSnippet: String): CodeFix {
        val cacheKey = cache.computeKey(codeSnippet + issue.type.name, "fix")
        cache.get(cacheKey)?.let {
            return parseFixResponse(it, issue, codeSnippet)
        }
        val prompt = PromptTemplates.suggestFix(issue, codeSnippet)
        val response = callOpenAI(prompt, SystemPrompts.DEBUGGER)
        cache.put(cacheKey, response)
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
            lineEnd = issue.line + originalSnippet.lines().size
        )
    }

    suspend fun explainSystem(graph: ProjectGraph): String {
        val cacheKey = cache.computeKey(graph.metadata.projectName + graph.nodes.size, "system")
        cache.get(cacheKey)?.let { return it }

        val prompt = PromptTemplates.explainSystem(graph)
        val response = callOpenAI(prompt, SystemPrompts.DEBUGGER)
        cache.put(cacheKey, response)
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
                val body = response.body?.string() ?: ""
                log.error("OpenAI API error: ${response.code} - $body")
                throw RuntimeException("OpenAI API error: ${response.code}")
            }

            val responseBody = response.body?.string()
                ?: throw RuntimeException("Empty response from OpenAI")

            val completionResponse = json.decodeFromString<ChatCompletionResponse>(responseBody)
            completionResponse.choices.firstOrNull()?.message?.content
                ?: throw RuntimeException("No content in OpenAI response")
        }

}
