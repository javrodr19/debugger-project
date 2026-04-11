package com.ghostdebugger.bridge

import com.ghostdebugger.model.DebugVariable
import kotlinx.serialization.Serializable

@Serializable
data class AnalysisCompletePayload(
    val errorCount: Int,
    val warningCount: Int,
    val healthScore: Double
)

@Serializable
data class IssueExplanationPayload(
    val issueId: String,
    val explanation: String
)

@Serializable
data class NodeUpdatePayload(
    val nodeId: String,
    val status: String
)

@Serializable
data class ImpactAnalysisPayload(
    val nodeId: String,
    val affectedNodes: List<String>
)

@Serializable
data class DebugFramePayload(
    val nodeId: String,
    val filePath: String,
    val line: Int,
    val variables: List<DebugVariable>
)

@Serializable
data class ExplanationsPayload(
    val issueId: String,
    val explanation: String
)
