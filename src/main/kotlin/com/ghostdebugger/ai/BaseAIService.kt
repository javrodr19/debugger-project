package com.ghostdebugger.ai

import com.ghostdebugger.ai.prompts.PromptTemplates
import com.ghostdebugger.ai.prompts.SystemPrompts
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.FunctionSymbol
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ProjectGraph
import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

internal abstract class BaseAIService(
    protected val timeoutMs: Long,
    cacheTtlSeconds: Long,
    protected val cacheEnabled: Boolean,
    cacheMaxEntries: Int
) : AIService {

    protected val log = logger<BaseAIService>()
    protected val cache = AICache(cacheTtlSeconds, cacheMaxEntries)
    protected val json = Json { ignoreUnknownKeys = true }

    protected val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Provider-specific model invocation. Subclasses build the request shape, set auth,
     * post to the provider, and decode the response into a plain content string.
     *
     * `jsonMode` is a hint: OpenAI uses it to set `response_format = json_object`; Ollama
     * ignores it (`/api/chat` has no equivalent).
     */
    protected abstract suspend fun callModel(
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean
    ): String

    override suspend fun detectIssues(
        filePath: String,
        fileContent: String,
        functions: List<FunctionSymbol>
    ): List<Issue> {
        return try {
            val raw = callModel(
                SystemPrompts.DEBUGGER,
                PromptTemplates.detectIssues(filePath, fileContent, functions),
                jsonMode = true
            )
            when (val r = AiJsonExtractor.extract(raw)) {
                is AiJsonExtractor.Result.Ok -> AiIssueMapper.mapIssues(r.element, filePath, fileContent)
                AiJsonExtractor.Result.Empty -> {
                    log.warn("${this::class.simpleName} JSON extraction returned Empty for $filePath (len=${raw.length})")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            log.warn("${this::class.simpleName} detectIssues failed for $filePath", e)
            emptyList()
        }
    }

    override suspend fun explainIssue(issue: Issue, codeSnippet: String): String =
        cached(codeSnippet + issue.type.name, "explain") {
            callModel(SystemPrompts.DEBUGGER, PromptTemplates.explainIssue(issue, codeSnippet), jsonMode = false)
        }

    override suspend fun suggestFix(issue: Issue, codeSnippet: String): CodeFix {
        val raw = cached(codeSnippet + issue.type.name, "fix") {
            callModel(SystemPrompts.DEBUGGER, PromptTemplates.suggestFix(issue, codeSnippet), jsonMode = false)
        }
        return parseFixResponse(raw, issue, codeSnippet)
    }

    override suspend fun explainSystem(graph: ProjectGraph): String =
        cached(graph.metadata.projectName + graph.nodes.size, "system") {
            callModel(SystemPrompts.DEBUGGER, PromptTemplates.explainSystem(graph), jsonMode = false)
        }

    private suspend fun cached(seed: Any, kind: String, produce: suspend () -> String): String {
        val key = if (cacheEnabled) cache.computeKey(seed.toString(), kind) else null
        if (key != null) cache.get(key)?.let { return it }
        val response = produce()
        if (key != null) cache.put(key, response)
        return response
    }

    protected fun parseFixResponse(raw: String, issue: Issue, originalSnippet: String): CodeFix {
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
