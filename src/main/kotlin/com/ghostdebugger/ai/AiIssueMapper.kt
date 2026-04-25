package com.ghostdebugger.ai

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.UUID

object AiIssueMapper {
    private val log = logger<AiIssueMapper>()

    fun mapIssues(element: JsonElement, filePath: String, fileContent: String): List<Issue> {
        val array: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> when {
                element.containsKey("issues") -> element["issues"]?.jsonArray ?: JsonArray(emptyList())
                element.containsKey("type") && element.containsKey("line") ->
                    JsonArray(listOf(element))
                else -> JsonArray(emptyList())
            }
            else -> JsonArray(emptyList())
        }

        return array.mapNotNull { item ->
            runCatching { toIssue(item.jsonObject, filePath, fileContent) }
                .onFailure { e ->
                    val preview = item.toString().take(120)
                    log.warn("Failed to map AI issue item for $filePath: $preview", e)
                }
                .getOrNull()
        }
    }

    private fun toIssue(obj: JsonObject, filePath: String, fileContent: String): Issue? {
        val typeStr = obj["type"]?.jsonPrimitive?.content ?: return null
        val line = obj["line"]?.jsonPrimitive?.intOrNull ?: return null

        val type = runCatching { IssueType.valueOf(typeStr) }
            .onFailure { log.info("Unknown AI issue type '$typeStr' for $filePath; defaulting to ARCHITECTURE") }
            .getOrDefault(IssueType.ARCHITECTURE)
        val severityStr = obj["severity"]?.jsonPrimitive?.content ?: "WARNING"
        val severity = runCatching { IssueSeverity.valueOf(severityStr) }
            .onFailure { log.info("Unknown AI severity '$severityStr' for $filePath; defaulting to WARNING") }
            .getOrDefault(IssueSeverity.WARNING)

        return Issue(
            id = UUID.randomUUID().toString(),
            type = type,
            severity = severity,
            title = obj["title"]?.jsonPrimitive?.content ?: "Detected Issue",
            description = obj["description"]?.jsonPrimitive?.content ?: "",
            filePath = filePath,
            line = line,
            codeSnippet = snippet(fileContent, line),
            affectedNodes = listOf(filePath)
        )
    }

    private fun snippet(content: String, lineNum: Int): String {
        val lines = content.lines()
        val start = maxOf(0, lineNum - 3)
        val end = minOf(lines.size, lineNum + 2)
        return lines.subList(start, end).joinToString("\n")
    }
}
