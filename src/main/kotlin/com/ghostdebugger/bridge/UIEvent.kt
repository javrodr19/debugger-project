package com.ghostdebugger.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

@Serializable
data class UIEventEnvelope(
    val type: String,
    val payload: JsonObject? = null
)

sealed class UIEvent {
    data class NodeClicked(val nodeId: String) : UIEvent()
    data class NodeDoubleClicked(val nodeId: String) : UIEvent()
    data class FixRequested(val issueId: String, val nodeId: String) : UIEvent()
    data class SimulateRequested(val entryNodeId: String) : UIEvent()
    data class ImpactRequested(val nodeId: String) : UIEvent()
    data class ExplainSystemRequested(val dummy: String = "") : UIEvent()
    data class AnalyzeRequested(val dummy: String = "") : UIEvent()
    data class BreakpointSet(val filePath: String, val line: Int) : UIEvent()
    data class BreakpointRemoved(val filePath: String, val line: Int) : UIEvent()
    data class ExportReportRequested(val dummy: String = "") : UIEvent()
    data class Unknown(val raw: String) : UIEvent()
}

object UIEventParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(message: String): UIEvent {
        return try {
            val envelope = json.decodeFromString<UIEventEnvelope>(message)
            when (envelope.type) {
                "NODE_CLICKED" -> UIEvent.NodeClicked(
                    nodeId = envelope.payload?.get("nodeId")?.jsonPrimitive?.content ?: ""
                )
                "NODE_DOUBLE_CLICKED" -> UIEvent.NodeDoubleClicked(
                    nodeId = envelope.payload?.get("nodeId")?.jsonPrimitive?.content ?: ""
                )
                "FIX_REQUESTED" -> UIEvent.FixRequested(
                    issueId = envelope.payload?.get("issueId")?.jsonPrimitive?.content ?: "",
                    nodeId = envelope.payload?.get("nodeId")?.jsonPrimitive?.content ?: ""
                )
                "SIMULATE_REQUESTED" -> UIEvent.SimulateRequested(
                    entryNodeId = envelope.payload?.get("entryNodeId")?.jsonPrimitive?.content ?: ""
                )
                "IMPACT_REQUESTED" -> UIEvent.ImpactRequested(
                    nodeId = envelope.payload?.get("nodeId")?.jsonPrimitive?.content ?: ""
                )
                "EXPLAIN_SYSTEM" -> UIEvent.ExplainSystemRequested()
                "ANALYZE" -> UIEvent.AnalyzeRequested()
                "BREAKPOINT_SET" -> UIEvent.BreakpointSet(
                    filePath = envelope.payload?.get("filePath")?.jsonPrimitive?.content ?: "",
                    line = envelope.payload?.get("line")?.jsonPrimitive?.int ?: 1
                )
                "BREAKPOINT_REMOVED" -> UIEvent.BreakpointRemoved(
                    filePath = envelope.payload?.get("filePath")?.jsonPrimitive?.content ?: "",
                    line = envelope.payload?.get("line")?.jsonPrimitive?.int ?: 1
                )
                "EXPORT_REPORT" -> UIEvent.ExportReportRequested()
                else -> UIEvent.Unknown(message)
            }
        } catch (e: Exception) {
            UIEvent.Unknown(message)
        }
    }
}
