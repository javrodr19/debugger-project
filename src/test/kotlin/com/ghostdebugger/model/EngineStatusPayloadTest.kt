package com.ghostdebugger.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineStatusPayloadTest {

    @Test
    fun `enum contains exactly ONLINE OFFLINE DEGRADED FALLBACK_TO_STATIC DISABLED`() {
        assertEquals(
            listOf("ONLINE", "OFFLINE", "DEGRADED", "FALLBACK_TO_STATIC", "DISABLED"),
            EngineStatus.values().map { it.name }
        )
    }

    @Test
    fun `payload defaults message and latency to null`() {
        val p = EngineStatusPayload(provider = "STATIC", status = EngineStatus.DISABLED)
        assertEquals(null, p.message)
        assertEquals(null, p.latencyMs)
    }

    @Test
    fun `payload serializes and deserializes via kotlinx-serialization`() {
        val src = EngineStatusPayload(
            provider = "OPENAI",
            status = EngineStatus.FALLBACK_TO_STATIC,
            message = "boom",
            latencyMs = 123L
        )
        val json = Json.encodeToString(src)
        assertTrue(json.contains("OPENAI"))
        val back = Json.decodeFromString<EngineStatusPayload>(json)
        assertEquals(src, back)
    }
}
