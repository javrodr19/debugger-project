package com.ghostdebugger.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import com.intellij.openapi.diagnostic.logger

@Serializable
data class UIEventEnvelope(
    val type: String,
    val payload: JsonObject? = null
)

sealed class UIEvent {
    data class NodeClicked(val nodeId: String) : UIEvent()
    data class NodeDoubleClicked(val nodeId: String) : UIEvent()
    data class FixRequested(val issueId: String, val nodeId: String) : UIEvent()

    data class ImpactRequested(val nodeId: String) : UIEvent()
    object ExplainSystemRequested : UIEvent()
    object AnalyzeRequested : UIEvent()
    data class BreakpointSet(val filePath: String, val line: Int) : UIEvent()
    data class BreakpointRemoved(val filePath: String, val line: Int) : UIEvent()
    object ExportReportRequested : UIEvent()

    // Debug step controls
    object DebugStepOver : UIEvent()
    object DebugStepInto : UIEvent()
    object DebugStepOut : UIEvent()
    object DebugResume : UIEvent()
    object DebugPause : UIEvent()

    data class Unknown(val raw: String) : UIEvent()
}

object UIEventParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val log = logger<UIEventParser>()

    fun parse(message: String): UIEvent {
        return try {
            val envelope = json.decodeFromString<UIEventEnvelope>(message)
            parseEnvelope(envelope, message)
        } catch (e: Exception) {
            log.error("Parsing failed for message: $message", e)
            UIEvent.Unknown(message)
        }
    }

    private fun parseEnvelope(envelope: UIEventEnvelope, raw: String): UIEvent {
        return when (envelope.type) {
            "NODE_CLICKED", "NODE_DOUBLE_CLICKED", "IMPACT_REQUESTED" -> 
                parseNodeEvent(envelope)
            "FIX_REQUESTED" -> 
                parseFixEvent(envelope)
            "BREAKPOINT_SET", "BREAKPOINT_REMOVED" -> 
                parseBreakpointEvent(envelope)
            "EXPLAIN_SYSTEM" -> UIEvent.ExplainSystemRequested
            "ANALYZE" -> UIEvent.AnalyzeRequested
            "EXPORT_REPORT" -> UIEvent.ExportReportRequested
            "DEBUG_STEP_OVER", "DEBUG_STEP_INTO", "DEBUG_STEP_OUT", "DEBUG_RESUME", "DEBUG_PAUSE" -> 
                parseDebugEvent(envelope.type)
            else -> UIEvent.Unknown(raw)
        }
    }

    private fun parseNodeEvent(envelope: UIEventEnvelope): UIEvent {
        val nodeId = envelope.payload?.get("nodeId")?.jsonPrimitive?.content ?: ""
        return when (envelope.type) {
            "NODE_CLICKED" -> UIEvent.NodeClicked(nodeId)
            "NODE_DOUBLE_CLICKED" -> UIEvent.NodeDoubleClicked(nodeId)
            "IMPACT_REQUESTED" -> UIEvent.ImpactRequested(nodeId)
            else -> UIEvent.Unknown(envelope.type)
        }
    }

    private fun parseFixEvent(envelope: UIEventEnvelope): UIEvent {
        val issueId = envelope.payload?.get("issueId")?.jsonPrimitive?.content ?: ""
        val nodeId = envelope.payload?.get("nodeId")?.jsonPrimitive?.content ?: ""
        return UIEvent.FixRequested(issueId, nodeId)
    }

    private fun parseBreakpointEvent(envelope: UIEventEnvelope): UIEvent {
        val filePath = envelope.payload?.get("filePath")?.jsonPrimitive?.content ?: ""
        val line = envelope.payload?.get("line")?.jsonPrimitive?.int ?: 1
        return if (envelope.type == "BREAKPOINT_SET") 
            UIEvent.BreakpointSet(filePath, line) 
        else 
            UIEvent.BreakpointRemoved(filePath, line)
    }

    private fun parseDebugEvent(type: String): UIEvent {
        return when (type) {
            "DEBUG_STEP_OVER" -> UIEvent.DebugStepOver
            "DEBUG_STEP_INTO" -> UIEvent.DebugStepInto
            "DEBUG_STEP_OUT" -> UIEvent.DebugStepOut
            "DEBUG_RESUME" -> UIEvent.DebugResume
            "DEBUG_PAUSE" -> UIEvent.DebugPause
            else -> UIEvent.Unknown(type)
        }
    }
}

