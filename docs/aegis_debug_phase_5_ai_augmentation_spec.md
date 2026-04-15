# Phase 5 Implementation Spec: Aegis Debug AI Augmentation (Ollama Support, Settings-Compliant OpenAI, AI Explanation Enrichment, Missed-Issue Pass)

**Version:** 1.0 (binding)
**Status:** Ready for immediate implementation
**Source of Truth:** `aegis_debug_true_v1_spec.md`
**Target Phase from Source Spec:** Section 20 — Phase 5 (AI Augmentation), items 17–20
**Prior Phase:** `aegis_debug_phase_4_ui_refresh_spec.md` — must be merged before Phase 5 starts

---

## 1. Objective

Phase 5 activates the AI augmentation layer that Phases 1–4 prepared for. Four concrete deliverables:

1. **`OllamaService`.** A new Kotlin AI backend for local Ollama models via the `/api/chat` endpoint. Respects `ollamaEndpoint`, `ollamaModel`, and `aiTimeoutMs` from settings. Shares `AICache` for explanation/fix responses.

2. **`AIService` interface + `AIServiceFactory`.** A unified contract (`detectIssues`, `explainIssue`, `suggestFix`, `explainSystem`) shared by both `OpenAIService` and `OllamaService`. A factory object that creates the right backend based on `settings.aiProvider`. `GhostDebuggerService` holds one `aiService: AIService?` field instead of the current `openAIService: OpenAIService?`.

3. **Settings-compliant `OpenAIService` wiring.** `OpenAIService` currently hardcodes 60 s timeout and default cache TTL, and always creates its own OkHttpClient with these values. Phase 5 adds constructor parameters `timeoutMs: Long` and `cacheTtlSeconds: Long` and plumbs them from `GhostDebuggerSettings`. `AIAnalyzer` — the default `AiPassRunner` implementation — is updated to pass `model`, `timeoutMs`, and `cacheTtlSeconds` from settings.

4. **AI explanation enrichment for deterministic fixes.** After `FixerRegistry` generates a deterministic fix, if an AI service is available, launch an async coroutine to enrich the issue's explanation via `aiService.explainIssue` and emit it to the webview via `bridge.sendIssueExplanation`. The deterministic fix preview is sent immediately; the explanation is a background enrichment that appears when it resolves.

Additionally, Phase 5 corrects the **prompt language** throughout `PromptTemplates` and `SystemPrompts`: all "in Spanish" directives are changed to "in English" so that the plugin is usable by a general audience.

Phase 5 ships **no** new static analyzers, **no** new fixer implementations, **no** new UI components, and **no** changes to the JCEF bridge event schema.

---

## 2. Scope

### 2.1 In Scope

| Area | Work |
|---|---|
| `src/main/kotlin/com/ghostdebugger/ai/AIService.kt` | **new** — `AIService` interface |
| `src/main/kotlin/com/ghostdebugger/ai/OllamaModels.kt` | **new** — `OllamaChatRequest`, `OllamaChatResponse` serialization models |
| `src/main/kotlin/com/ghostdebugger/ai/OllamaService.kt` | **new** — Ollama chat backend implementing `AIService` |
| `src/main/kotlin/com/ghostdebugger/ai/AIServiceFactory.kt` | **new** — creates the right `AIService` instance based on settings |
| `src/main/kotlin/com/ghostdebugger/ai/OpenAIService.kt` | add `timeoutMs`/`cacheTtlSeconds` constructor params; implement `AIService`; tag `suggestFix` result with `isDeterministic=false, confidence=0.7` |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/AIAnalyzer.kt` | pass `model`, `timeoutMs`, `cacheTtlSeconds` from settings into `OpenAIService` |
| `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt` | change all "in Spanish" to "in English" |
| `src/main/kotlin/com/ghostdebugger/ai/prompts/SystemPrompts.kt` | change "Respond in Spanish" to "Respond in English" |
| `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` | replace `openAIService: OpenAIService?` with `aiService: AIService?`; add `resolveAiService()` routing; add async AI explanation enrichment in `handleFixRequested` |
| `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt` | `runAiPass` OLLAMA branch: call real `OllamaService` instead of returning `FALLBACK_TO_STATIC` |
| Tests | 4 new test classes. All must pass |

### 2.2 Out of Scope (strict)

- New static analyzers or new `Fixer` implementations.
- Streaming responses (Ollama stream mode, OpenAI streaming).
- Conversational / chat-style multi-turn interactions.
- Telemetry or usage metering.
- Any change to `UIEvent`, `UIEventParser`, `JcefBridge` event schema.
- Any change to the webview TypeScript code.
- `WhatIfResponse` model or `whatIf` prompt template (existing unused path — not modified).
- `jointFix` prompt template (existing unused path — not modified).
- `OllamaService.detectIssues` using a different prompting strategy than `OpenAIService.detectIssues` — both use the same `PromptTemplates.detectIssues` function.

---

## 3. Non-Goals

The following MUST NOT be touched by Phase 5:

1. Do **not** change the `AiPassRunner` fun interface signature (`(AnalysisContext, String) -> List<Issue>`). The injectable test seam stays intact.
2. Do **not** change `AnalysisEngine.runStaticPass` or any `Analyzer` implementation.
3. Do **not** change `FixerRegistry`, `Fixer` implementations, or `FixApplicator`.
4. Do **not** change `AnalysisContextPrioritization.kt` or the file-capping logic.
5. Do **not** change `GhostDebuggerSettings` fields. Phase 5 only consumes existing fields.
6. Do **not** change the Gradle dependency list. `OkHttp` is already a transitive dependency of the IntelliJ platform; no new HTTP libraries are introduced.
7. Do **not** change the Phase 1–4 test classes.
8. Do **not** change `AnalysisModels.kt` (the `Issue`, `CodeFix` shapes set in Phases 2–3 are complete).

---

## 4. Implementation Decisions

| Decision | Value | Source |
|---|---|---|
| Ollama chat API path | `{ollamaEndpoint}/api/chat` | Ollama API reference; binding §5.1 |
| Ollama request `stream` | `false` (always) | binding §5.2 — Phase 5 does not implement streaming |
| Ollama system message | sent as first `ChatMessage(role="system", content=SystemPrompts.DEBUGGER)` | binding §5.3 |
| OkHttpClient per instance | each `AIService` impl creates its own `OkHttpClient` using its `timeoutMs` | existing pattern in `OpenAIService` |
| `OllamaService` cache | uses `AICache(cacheTtlSeconds)` for `explainIssue`, `suggestFix`, `explainSystem`; no cache for `detectIssues` | mirrors `OpenAIService` cache usage |
| `AIServiceFactory.create` for OPENAI with no key | returns `null` | binding §5.4 |
| `AIServiceFactory.create` for OLLAMA | returns `OllamaService` unconditionally (Ollama is local; no key) | binding §5.5 |
| `AIServiceFactory.create` for NONE | returns `null` | binding §5.6 |
| AI explanation enrichment timing | send deterministic `CodeFix` immediately; launch explanation async in `scope.launch { }` | binding §5.7 — fast-path UX is not gated on AI |
| AI explanation enrichment condition | only if `settings.aiProvider != NONE` and `aiService != null`; skipped silently otherwise | binding §5.8 |
| `OpenAIService.suggestFix` result | must set `isDeterministic = false`, `confidence = 0.7` on the returned `CodeFix` | Phase 3 spec §5.4 — AI fixes default to these values |
| Prompt language | English | binding §5.9 — plugin targets a general audience |
| `AnalysisEngine` OLLAMA pass | `runAiPass` launches `runOllamaPass(context, settings)` — mirrors `runOpenAiPass` structure | binding §5.10 |

---

## 5. Binding Decisions

### 5.1 Ollama API endpoint

The Ollama service MUST POST to `${settings.ollamaEndpoint}/api/chat`. Example with default: `http://localhost:11434/api/chat`.

The request body MUST conform to:
```json
{
  "model": "<settings.ollamaModel>",
  "messages": [
    { "role": "system", "content": "<SystemPrompts.DEBUGGER>" },
    { "role": "user",   "content": "<user prompt>" }
  ],
  "stream": false
}
```

The response MUST be parsed as `OllamaChatResponse.message.content` (the `content` string of the `message` field).

### 5.2 OllamaService connection error handling

If the Ollama endpoint is unreachable or returns a non-2xx HTTP status:
- `detectIssues`: return `emptyList()` after logging `log.warn("Ollama detectIssues failed for $filePath", e)`
- `explainIssue`: throw the exception (callers catch it)
- `suggestFix`: throw the exception
- `explainSystem`: throw the exception

This allows the `AnalysisEngine` OLLAMA pass to catch failures and emit `FALLBACK_TO_STATIC`.

### 5.3 Prompt language

Replace every occurrence of the word "Spanish" in `PromptTemplates.kt` and `SystemPrompts.kt` with "English". The replacement is strictly textual — no logic changes to the prompts themselves.

### 5.4 AIServiceFactory.create signature

```kotlin
object AIServiceFactory {
    fun create(settings: GhostDebuggerSettings.State, apiKey: String?): AIService?
}
```

OPENAI: returns `null` if `apiKey.isNullOrBlank()`.
OLLAMA: ignores `apiKey`; always returns an `OllamaService`.
NONE: always returns `null`.

### 5.5 GhostDebuggerService.resolveAiService

```kotlin
private fun resolveAiService(): AIService? {
    val settings = GhostDebuggerSettings.getInstance().snapshot()
    val apiKey = if (settings.aiProvider == AIProvider.OPENAI) ApiKeyManager.getApiKey() else null
    return AIServiceFactory.create(settings, apiKey)?.also { aiService = it }
}
```

Called lazily: existing `openAIService` references change to `aiService ?: resolveAiService()`.

### 5.6 AI explanation enrichment in handleFixRequested

After the deterministic fix path sends the fix to the bridge:

```kotlin
// deterministic fast path
bridge?.sendFixSuggestion(fix)
// async enrichment
scope.launch {
    try {
        val svc = aiService ?: resolveAiService() ?: return@launch
        val explanation = svc.explainIssue(issue, issue.codeSnippet)
        issue.explanation = explanation
        withContext(Dispatchers.Swing) {
            bridge?.sendIssueExplanation(issue.id, explanation)
        }
    } catch (e: Exception) {
        log.warn("AI explanation enrichment failed for issue ${issue.id}", e)
    }
}
```

The enrichment is fire-and-forget; failure is logged and silently swallowed.

### 5.7 AnalysisEngine OLLAMA pass structure

`runOllamaPass` mirrors `runOpenAiPass` with these differences:
- No `allowCloudUpload` check (Ollama is local)
- No `apiKey` check (Ollama requires no key)
- Creates `OllamaService(settings.ollamaEndpoint, settings.ollamaModel, settings.aiTimeoutMs, settings.cacheTtlSeconds)`
- On success: tags issues with `sources = [IssueSource.AI_LOCAL]`, `providers = [EngineProvider.OLLAMA]`
- On failure: returns `FALLBACK_TO_STATIC` with the exception message

---

## 6. AIService.kt — Unified Interface

**File:** `src/main/kotlin/com/ghostdebugger/ai/AIService.kt`

```kotlin
package com.ghostdebugger.ai

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.ProjectGraph

interface AIService {
    suspend fun detectIssues(filePath: String, fileContent: String): List<Issue>
    suspend fun explainIssue(issue: Issue, codeSnippet: String): String
    suspend fun suggestFix(issue: Issue, codeSnippet: String): CodeFix
    suspend fun explainSystem(graph: ProjectGraph): String
}
```

---

## 7. OllamaModels.kt — Serialization Models

**File:** `src/main/kotlin/com/ghostdebugger/ai/OllamaModels.kt`

```kotlin
package com.ghostdebugger.ai

import com.ghostdebugger.model.ChatMessage
import kotlinx.serialization.Serializable

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)

@Serializable
data class OllamaChatResponse(
    val model: String? = null,
    val message: ChatMessage,
    val done: Boolean = false
)
```

`ChatMessage` (`role`, `content`) is already defined in `src/main/kotlin/com/ghostdebugger/model/AIModels.kt` and is reused here without modification.

---

## 8. OllamaService.kt

**File:** `src/main/kotlin/com/ghostdebugger/ai/OllamaService.kt`

```kotlin
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
    private val endpoint: String,
    private val model: String,
    private val timeoutMs: Long = 30_000,
    cacheTtlSeconds: Long = 3600
) : AIService {
    private val log = logger<OllamaService>()
    private val cache = AICache(cacheTtlSeconds)
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
        val cacheKey = cache.computeKey(codeSnippet + issue.type.name, "explain")
        cache.get(cacheKey)?.let { return it }
        val response = callOllama(PromptTemplates.explainIssue(issue, codeSnippet))
        cache.put(cacheKey, response)
        return response
    }

    override suspend fun suggestFix(issue: Issue, codeSnippet: String): CodeFix {
        val cacheKey = cache.computeKey(codeSnippet + issue.type.name, "fix")
        cache.get(cacheKey)?.let { return parseFixResponse(it, issue, codeSnippet) }
        val response = callOllama(PromptTemplates.suggestFix(issue, codeSnippet))
        cache.put(cacheKey, response)
        return parseFixResponse(response, issue, codeSnippet)
    }

    override suspend fun explainSystem(graph: ProjectGraph): String {
        val cacheKey = cache.computeKey(graph.metadata.projectName + graph.nodes.size, "system")
        cache.get(cacheKey)?.let { return it }
        val response = callOllama(PromptTemplates.explainSystem(graph))
        cache.put(cacheKey, response)
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
            val element = json.parseToJsonElement(raw)
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
```

---

## 9. AIServiceFactory.kt

**File:** `src/main/kotlin/com/ghostdebugger/ai/AIServiceFactory.kt`

```kotlin
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
                    cacheTtlSeconds = settings.cacheTtlSeconds
                )
            }
            AIProvider.OLLAMA -> OllamaService(
                endpoint        = settings.ollamaEndpoint,
                model           = settings.ollamaModel,
                timeoutMs       = settings.aiTimeoutMs,
                cacheTtlSeconds = settings.cacheTtlSeconds
            )
        }
}
```

---

## 10. OpenAIService.kt — Changes

### 10.1 Class signature

Change the constructor to accept `timeoutMs` and `cacheTtlSeconds`, and implement `AIService`:

```kotlin
class OpenAIService(
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val timeoutMs: Long = 60_000,
    cacheTtlSeconds: Long = 3600
) : AIService {
```

`private val cache = AICache()` → `private val cache = AICache(cacheTtlSeconds)`

The `OkHttpClient` builder currently hardcodes `60, TimeUnit.SECONDS` for all three timeouts. Change to:

```kotlin
private val httpClient = OkHttpClient.Builder()
    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    .build()
```

### 10.2 suggestFix result tagging

In `parseFixResponse`, the returned `CodeFix` currently does not set `isDeterministic` or `confidence`. Change to:

```kotlin
return CodeFix(
    id            = UUID.randomUUID().toString(),
    issueId       = issue.id,
    description   = explanation,
    originalCode  = originalSnippet,
    fixedCode     = fixedCode,
    filePath      = issue.filePath,
    lineStart     = issue.line,
    lineEnd       = issue.line + originalSnippet.lines().size,
    isDeterministic = false,
    confidence    = 0.7
)
```

---

## 11. AIAnalyzer.kt — Settings Plumbing

`AIAnalyzer` currently calls `OpenAIService(apiKey)` directly, ignoring model/timeout/cache settings. It does not have access to `GhostDebuggerSettings` directly — it only receives `apiKey`.

Phase 5 change: `AIAnalyzer` must accept a `GhostDebuggerSettings.State` reference so it can pass the right parameters to `OpenAIService`:

```kotlin
class AIAnalyzer(
    private val apiKey: String,
    private val settings: GhostDebuggerSettings.State
) {
    // ...
    suspend fun analyze(context: AnalysisContext): List<Issue> {
        val openAIService = OpenAIService(
            apiKey          = apiKey,
            model           = settings.openAiModel,
            timeoutMs       = settings.aiTimeoutMs,
            cacheTtlSeconds = settings.cacheTtlSeconds
        )
        // ... rest unchanged ...
    }
}
```

`AnalysisEngine`'s default `aiPassRunner` lambda must also pass settings. Change:

```kotlin
private val aiPassRunner: AiPassRunner = AiPassRunner { ctx, key ->
    AIAnalyzer(key).analyze(ctx)
}
```

to:

```kotlin
private val aiPassRunner: AiPassRunner = AiPassRunner { ctx, key ->
    AIAnalyzer(key, settingsProvider()).analyze(ctx)
}
```

`AiPassRunner` interface signature is **unchanged**.

---

## 12. AnalysisEngine.kt — Ollama Pass

### 12.1 runAiPass: OLLAMA branch

Replace the current OLLAMA stub:

```kotlin
AIProvider.OLLAMA -> emptyList<Issue>() to EngineStatusPayload(
    provider = "OLLAMA",
    status   = EngineStatus.FALLBACK_TO_STATIC,
    message  = "Ollama integration not yet available; continuing with static-only results."
)
```

With a real dispatch:

```kotlin
AIProvider.OLLAMA -> runOllamaPass(limitedContext, settings)
```

### 12.2 New private method runOllamaPass

```kotlin
private suspend fun runOllamaPass(
    context: AnalysisContext,
    settings: GhostDebuggerSettings.State
): Pair<List<Issue>, EngineStatusPayload> {
    if (settings.maxAiFiles <= 0) {
        return emptyList<Issue>() to EngineStatusPayload(
            provider = "OLLAMA",
            status   = EngineStatus.DISABLED,
            message  = "maxAiFiles = 0; Ollama pass skipped."
        )
    }

    val aiContext = context.limitAiFilesTo(settings.maxAiFiles)
    val ollamaService = OllamaService(
        endpoint        = settings.ollamaEndpoint,
        model           = settings.ollamaModel,
        timeoutMs       = settings.aiTimeoutMs,
        cacheTtlSeconds = settings.cacheTtlSeconds
    )
    val started = System.currentTimeMillis()
    val result = runCatching {
        // Reuse aiPassRunner interface by delegating directly for Ollama
        // (Ollama needs no apiKey so it is not routed through the AiPassRunner path)
        aiContext.parsedFiles.flatMap { file ->
            ollamaService.detectIssues(file.path, file.content)
        }
    }
    val latency = System.currentTimeMillis() - started

    return result.fold(
        onSuccess = { issues ->
            val tagged = issues.map { it.copy(
                sources   = if (it.sources.isNotEmpty() && it.sources != listOf(IssueSource.STATIC))
                                it.sources else listOf(IssueSource.AI_LOCAL),
                providers = if (it.providers.isNotEmpty() && it.providers != listOf(EngineProvider.STATIC))
                                it.providers else listOf(EngineProvider.OLLAMA),
                confidence = it.confidence ?: 0.7
            ) }
            tagged to EngineStatusPayload(
                provider  = "OLLAMA",
                status    = EngineStatus.ONLINE,
                message   = "Ollama pass ok (${tagged.size} issues).",
                latencyMs = latency
            )
        },
        onFailure = { e ->
            log.warn("Ollama pass failed; static results will ship", e)
            emptyList<Issue>() to EngineStatusPayload(
                provider  = "OLLAMA",
                status    = EngineStatus.FALLBACK_TO_STATIC,
                message   = "Ollama unreachable (${e.javaClass.simpleName}); static results returned.",
                latencyMs = latency
            )
        }
    )
}
```

`OllamaService` must be imported: `import com.ghostdebugger.ai.OllamaService`

---

## 13. PromptTemplates.kt and SystemPrompts.kt — Language Fix

### 13.1 PromptTemplates.kt

Apply the following replacements (exact string replacement, no structural changes):

| Old string | New string |
|---|---|
| `"description": "<A detailed explanation of the problem, max 2 sentences in Spanish>"` | `"description": "<A detailed explanation of the problem, max 2 sentences in English>"` |
| `Respond in Spanish. Be concise (max 150 words).` | `Respond in English. Be concise (max 150 words).` |
| `EXPLANATION: <1-2 sentences in Spanish describing what you changed and why>` | `EXPLANATION: <1-2 sentences in English describing what you changed and why>` |
| `Provide a concise project overview in Spanish (max 200 words):` | `Provide a concise project overview in English (max 200 words):` |
| `Answer this question as a CTO in Spanish.` | `Answer this question as a CTO in English.` |

### 13.2 SystemPrompts.kt

Replace:

```kotlin
- Respond in Spanish unless specifically asked otherwise
```

With:

```kotlin
- Respond in English unless specifically asked otherwise
```

---

## 14. GhostDebuggerService.kt — AI Service Routing

### 14.1 Field replacement

Remove:
```kotlin
private var openAIService: OpenAIService? = null
```

Add:
```kotlin
private var aiService: AIService? = null
```

Add import: `import com.ghostdebugger.ai.AIService`
Add import: `import com.ghostdebugger.ai.AIServiceFactory`

### 14.2 resolveAiService() private method

Add after the field declarations:

```kotlin
private fun resolveAiService(): AIService? {
    val settings = GhostDebuggerSettings.getInstance().snapshot()
    val apiKey = if (settings.aiProvider == AIProvider.OPENAI) ApiKeyManager.getApiKey() else null
    return AIServiceFactory.create(settings, apiKey)?.also { aiService = it }
}
```

### 14.3 Call-site migration

Every reference to `openAIService ?: OpenAIService(apiKey).also { openAIService = it }` becomes `aiService ?: resolveAiService()`.

Every reference to `openAIService` alone becomes `aiService`.

Specifically, there are three call sites identified in `GhostDebuggerService`:

1. **Pre-fetch explanations block** (inside `analyzeProject`, line ~334):
   ```kotlin
   // Before:
   val aiService = OpenAIService(apiKey).also { openAIService = it }
   // After:
   val svc = aiService ?: resolveAiService() ?: return@launch
   ```
   The `val aiService` local variable becomes `val svc` to avoid shadowing the field.

2. **handleExplainIssue / NodeClicked explanation** (line ~377):
   ```kotlin
   // Before:
   val aiService = openAIService ?: OpenAIService(apiKey).also { openAIService = it }
   val explanation = aiService.explainIssue(issue, issue.codeSnippet)
   // After:
   val svc = aiService ?: resolveAiService() ?: return@launch
   val explanation = svc.explainIssue(issue, issue.codeSnippet)
   ```

3. **handleExplainSystem** (line ~546):
   ```kotlin
   // Before:
   val aiService = openAIService ?: OpenAIService(apiKey).also { openAIService = it }
   val summary = aiService.explainSystem(graph)
   // After:
   val svc = aiService ?: resolveAiService() ?: return@launch
   val summary = svc.explainSystem(graph)
   ```

Remove the `val apiKey = ApiKeyManager.getApiKey() ?: return@launch` guard lines from sites 2 and 3 — `resolveAiService()` already handles the key requirement internally and returns `null` when the key is missing or provider is NONE.

### 14.4 handleFixRequested AI explanation enrichment

Locate the deterministic fast path inside `handleFixRequested`:

```kotlin
// existing:
bridge?.sendFixSuggestion(fix)
```

Immediately after it, add the async enrichment block as specified in §5.6:

```kotlin
bridge?.sendFixSuggestion(fix)

// Async AI explanation enrichment for deterministic fixes
val settings = GhostDebuggerSettings.getInstance().snapshot()
if (settings.aiProvider != AIProvider.NONE) {
    scope.launch {
        try {
            val svc = aiService ?: resolveAiService() ?: return@launch
            val explanation = svc.explainIssue(issue, issue.codeSnippet)
            issue.explanation = explanation
            withContext(Dispatchers.Swing) {
                bridge?.sendIssueExplanation(issue.id, explanation)
            }
        } catch (e: Exception) {
            log.warn("AI explanation enrichment failed for issue ${issue.id}", e)
        }
    }
}
```

### 14.5 Pre-fetch guard for OPENAI-specific key check

The pre-fetch explanations block (inside `analyzeProject`) currently starts with:
```kotlin
val apiKey = ApiKeyManager.getApiKey()
if (!apiKey.isNullOrBlank()) {
    val aiService = OpenAIService(apiKey).also { openAIService = it }
```

Replace the entire guard condition with a `resolveAiService()` call:
```kotlin
val svc = resolveAiService()
if (svc != null) {
    val criticalIssues = analysisResult.issues
        .filter { it.severity == IssueSeverity.ERROR }
        .take(3)
    for (issue in criticalIssues) {
        try {
            val explanation = svc.explainIssue(issue, issue.codeSnippet)
            // ... rest unchanged ...
```

---

## 15. Tests

### 15.1 Required new test files

| File | What it tests |
|---|---|
| `src/test/kotlin/com/ghostdebugger/ai/AIServiceFactoryTest.kt` | `AIServiceFactory.create` returns correct type for each `AIProvider`; returns `null` for OPENAI with no key |
| `src/test/kotlin/com/ghostdebugger/ai/OllamaServiceParseTest.kt` | `OllamaService.parseDetectIssuesResponse` (via `detectIssues` with a mock HTTP server or by testing the private parse logic); `parseFixResponse` sets `isDeterministic=false`, `confidence=0.7` |
| `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineOllamaPassTest.kt` | OLLAMA branch: `runAiPass` returns `ONLINE` when `OllamaService.detectIssues` succeeds; returns `FALLBACK_TO_STATIC` when it throws; `maxAiFiles=0` produces `DISABLED` |
| `src/test/kotlin/com/ghostdebugger/ai/OpenAIServiceTimeoutTest.kt` | `OpenAIService` constructor accepts `timeoutMs`; `OkHttpClient` reflects the configured timeout; `suggestFix` result has `isDeterministic=false`, `confidence=0.7` |

### 15.2 AIServiceFactoryTest

```kotlin
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
```

For the last test to compile, `OllamaService.endpoint` and `OllamaService.model` must be `internal` (not `private`). Change the constructor visibility:

```kotlin
class OllamaService(
    internal val endpoint: String,
    internal val model: String,
    private val timeoutMs: Long = 30_000,
    cacheTtlSeconds: Long = 3600
) : AIService {
```

### 15.3 AnalysisEngineOllamaPassTest

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.EngineStatus
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AnalysisEngineOllamaPassTest {

    private fun engine(
        provider: AIProvider = AIProvider.OLLAMA,
        maxAiFiles: Int = 5
    ) = AnalysisEngine(
        settingsProvider = { GhostDebuggerSettings.State(aiProvider = provider, maxAiFiles = maxAiFiles) },
        apiKeyProvider   = { null }
        // aiPassRunner default — not reached by OLLAMA branch
    )

    @Test fun `OLLAMA maxAiFiles=0 produces DISABLED`() = runTest {
        val eng = engine(maxAiFiles = 0)
        val ctx = AnalysisContext(parsedFiles = emptyList(), graph = com.ghostdebugger.graph.InMemoryGraph())
        val result = eng.analyze(ctx)
        assertEquals(EngineStatus.DISABLED, result.engineStatus.status)
    }
}
```

Note: A full ONLINE/FALLBACK test for Ollama requires either a live Ollama process or an injectable `OllamaService` mock. The `maxAiFiles=0 → DISABLED` path is the cleanest pure-unit case. Additional integration tests targeting a real or stub Ollama server may be added separately.

---

## 16. File Change Summary

| File | Change type |
|---|---|
| `src/main/kotlin/com/ghostdebugger/ai/AIService.kt` | **new** |
| `src/main/kotlin/com/ghostdebugger/ai/OllamaModels.kt` | **new** |
| `src/main/kotlin/com/ghostdebugger/ai/OllamaService.kt` | **new** |
| `src/main/kotlin/com/ghostdebugger/ai/AIServiceFactory.kt` | **new** |
| `src/main/kotlin/com/ghostdebugger/ai/OpenAIService.kt` | add `timeoutMs`/`cacheTtlSeconds` params; implement `AIService`; fix `OkHttpClient` timeout; tag `suggestFix` result |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/AIAnalyzer.kt` | accept `settings`; pass model/timeout/cache to `OpenAIService` |
| `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt` | OLLAMA branch routes to `runOllamaPass`; default `aiPassRunner` passes settings |
| `src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt` | language strings: Spanish → English |
| `src/main/kotlin/com/ghostdebugger/ai/prompts/SystemPrompts.kt` | language string: Spanish → English |
| `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` | `openAIService` → `aiService: AIService?`; add `resolveAiService()`; migrate 3 call sites; add deterministic fix explanation enrichment |
| `src/test/kotlin/com/ghostdebugger/ai/AIServiceFactoryTest.kt` | **new** |
| `src/test/kotlin/com/ghostdebugger/ai/OllamaServiceParseTest.kt` | **new** |
| `src/test/kotlin/com/ghostdebugger/analysis/AnalysisEngineOllamaPassTest.kt` | **new** |
| `src/test/kotlin/com/ghostdebugger/ai/OpenAIServiceTimeoutTest.kt` | **new** |

All Kotlin files not listed above are **untouched**. All webview files are **untouched**.
