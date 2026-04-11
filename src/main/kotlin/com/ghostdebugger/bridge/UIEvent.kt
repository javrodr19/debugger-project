package com.ghostdebugger.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class UIEventEnvelope(
    val type: String,
    val payload: JsonObject? = null
)

sealed class UIEvent {
    data class NodeClicked(val nodeId: String) : UIEvent()
    data class FixRequested(val issueId: String, val nodeId: String) : UIEvent()
    data class SimulateRequested(val entryNodeId: String) : UIEvent()
    data class WhatIfQuestion(val question: String) : UIEvent()
    data class ImpactRequested(val nodeId: String) : UIEvent()
    data class ExplainSystemRequested(val dummy: String = "") : UIEvent()
    data class AnalyzeRequested(val dummy: String = "") : UIEvent()
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
                "FIX_REQUESTED" -> UIEvent.FixRequested(
                    issueId = envelope.payload?.get("issueId")?.jsonPrimitive?.content ?: "",
                    nodeId = envelope.payload?.get("nodeId")?.jsonPrimitive?.content ?: ""
                )
                "SIMULATE_REQUESTED" -> UIEvent.SimulateRequested(
                    entryNodeId = envelope.payload?.get("entryNodeId")?.jsonPrimitive?.content ?: ""
                )
                "WHAT_IF" -> UIEvent.WhatIfQuestion(
                    question = envelope.payload?.get("question")?.jsonPrimitive?.content ?: ""
                )
                "IMPACT_REQUESTED" -> UIEvent.ImpactRequested(
                    nodeId = envelope.payload?.get("nodeId")?.jsonPrimitive?.content ?: ""
                )
                "EXPLAIN_SYSTEM" -> UIEvent.ExplainSystemRequested()
                "ANALYZE" -> UIEvent.AnalyzeRequested()
                else -> UIEvent.Unknown(message)
            }
        } catch (e: Exception) {
            UIEvent.Unknown(message)
        }
    }
}
