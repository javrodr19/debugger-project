package com.ghostdebugger.bridge

import com.ghostdebugger.model.EngineProvider
import com.ghostdebugger.model.EngineStatus
import com.ghostdebugger.model.EngineStatusPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class EngineStatusBridgeTest {
    // We cannot instantiate JcefBridge without a live IDE platform,
    // so this test verifies the serialization shape directly.

    @Test fun `EngineStatusPayload serializes ONLINE correctly`() {
        val json = Json { encodeDefaults = true }
        val payload = EngineStatusPayload(provider = "OPENAI", status = EngineStatus.ONLINE)
        val encoded = json.encodeToString(payload)
        assertTrue(encoded.contains("\"status\":\"ONLINE\""))
        assertTrue(encoded.contains("\"provider\":\"OPENAI\""))
    }

    @Test fun `EngineStatusPayload serializes FALLBACK_TO_STATIC`() {
        val json = Json { encodeDefaults = true }
        val payload = EngineStatusPayload(provider = "STATIC", status = EngineStatus.FALLBACK_TO_STATIC)
        val encoded = json.encodeToString(payload)
        assertTrue(encoded.contains("FALLBACK_TO_STATIC"))
    }
}
