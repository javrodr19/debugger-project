package com.ghostdebugger.model

import kotlinx.serialization.Serializable

@Serializable
enum class EngineStatus {
    ONLINE,
    OFFLINE,
    DEGRADED,
    FALLBACK_TO_STATIC,
    DISABLED
}

@Serializable
data class EngineStatusPayload(
    val provider: String,
    val status: EngineStatus,
    val message: String? = null,
    val latencyMs: Long? = null
)
