package com.ghostdebugger.ai

import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicLong

object AiJsonExtractor {

    sealed interface Result {
        data class Ok(val element: JsonElement, val strategy: Strategy) : Result
        data object Empty : Result
    }

    enum class Strategy { DIRECT, FENCED, BALANCED }

    private val log = logger<AiJsonExtractor>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val counters: Map<Strategy, AtomicLong> =
        Strategy.entries.associateWith { AtomicLong(0) }

    fun extract(raw: String): Result {
        if (raw.isBlank()) return Result.Empty

        tryDirect(raw)?.let { return record(it, Strategy.DIRECT) }
        tryFenced(raw)?.let { return record(it, Strategy.FENCED) }
        tryBalanced(raw)?.let { return record(it, Strategy.BALANCED) }

        return Result.Empty
    }

    fun telemetrySnapshot(): Map<Strategy, Long> =
        counters.mapValues { it.value.get() }

    private fun record(element: JsonElement, strategy: Strategy): Result.Ok {
        counters[strategy]?.incrementAndGet()
        return Result.Ok(element, strategy)
    }

    private fun tryDirect(raw: String): JsonElement? = runCatching {
        json.parseToJsonElement(raw.trim())
    }.getOrNull()

    private fun tryFenced(raw: String): JsonElement? {
        val fence = FENCE_REGEX.find(raw) ?: return null
        val body = fence.groupValues[1].trim()
        return runCatching { json.parseToJsonElement(body) }.getOrNull()
    }

    private fun tryBalanced(raw: String): JsonElement? {
        val span = firstBalancedSpan(raw) ?: return null
        return runCatching { json.parseToJsonElement(span) }
            .onFailure { e -> log.warn("BALANCED strategy failed on span: ${span.take(200)}", e) }
            .getOrNull()
    }

    private fun firstBalancedSpan(raw: String): String? {
        val start = raw.indexOfAny(charArrayOf('{', '['))
        if (start < 0) return null

        val openCh = raw[start]
        val closeCh = if (openCh == '{') '}' else ']'

        var depth = 0
        var inString = false
        var escape = false
        var i = start

        while (i < raw.length) {
            val c = raw[i]
            if (escape) {
                escape = false
            } else if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    openCh -> depth++
                    closeCh -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    private val FENCE_REGEX = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
}
