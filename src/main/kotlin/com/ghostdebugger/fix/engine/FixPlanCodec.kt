package com.ghostdebugger.fix.engine

import com.ghostdebugger.ai.AiJsonExtractor
import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.Json

/**
 * Decodes raw model output into a [FixPlan]. Reuses [AiJsonExtractor] for robust extraction
 * (direct / fenced / balanced), then kotlinx closed-polymorphic decoding of the sealed
 * [FixOperation] (discriminator `type`, e.g. `replaceRange`). Returns null on any failure —
 * empty output, malformed JSON, or an unknown/unsupported operation type — so a bad AI proposal
 * is simply discarded (the verify gate is the safety net for plans that do decode).
 */
object FixPlanCodec {
    private val log = logger<FixPlanCodec>()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
    }

    fun decode(raw: String): FixPlan? {
        val element = when (val r = AiJsonExtractor.extract(raw)) {
            is AiJsonExtractor.Result.Ok -> r.element
            AiJsonExtractor.Result.Empty -> return null
        }
        return runCatching { json.decodeFromJsonElement(FixPlan.serializer(), element) }
            .onFailure { e -> log.info("FixPlan decode failed: ${e.message}") }
            .getOrNull()
    }
}
