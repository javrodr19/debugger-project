package com.ghostdebugger.ai

import com.ghostdebugger.ai.AiJsonExtractor.Result
import com.ghostdebugger.ai.AiJsonExtractor.Strategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AiJsonExtractorTest {

    @Test fun `direct parse of a JSON array`() {
        val result = AiJsonExtractor.extract("""[{"type":"NULL_SAFETY","line":3}]""")
        assertTrue(result is Result.Ok)
        assertEquals(Strategy.DIRECT, (result as Result.Ok).strategy)
        assertTrue(result.element is JsonArray)
    }

    @Test fun `direct parse of a JSON object`() {
        val result = AiJsonExtractor.extract("""{"issues":[{"type":"NULL_SAFETY","line":3}]}""")
        assertTrue(result is Result.Ok)
        assertEquals(Strategy.DIRECT, (result as Result.Ok).strategy)
        assertTrue(result.element is JsonObject)
    }

    @Test fun `fenced parse with json tag`() {
        val raw = """
            Sure, here is the analysis:
            ```json
            [{"type":"NULL_SAFETY","line":3}]
            ```
            Let me know if you need more.
        """.trimIndent()
        val result = AiJsonExtractor.extract(raw)
        assertTrue(result is Result.Ok)
        assertEquals(Strategy.FENCED, (result as Result.Ok).strategy)
    }

    @Test fun `fenced parse without language tag`() {
        val raw = """
            ```
            [{"type":"NULL_SAFETY","line":3}]
            ```
        """.trimIndent()
        val result = AiJsonExtractor.extract(raw)
        assertTrue(result is Result.Ok)
        assertEquals(Strategy.FENCED, (result as Result.Ok).strategy)
    }

    @Test fun `balanced parse with preamble prose`() {
        val raw = "Here are the issues I found: [{\"type\":\"NULL_SAFETY\",\"line\":3}]"
        val result = AiJsonExtractor.extract(raw)
        assertTrue(result is Result.Ok)
        assertEquals(Strategy.BALANCED, (result as Result.Ok).strategy)
    }

    @Test fun `balanced parse with suffix prose`() {
        val raw = "[{\"type\":\"NULL_SAFETY\",\"line\":3}] -- hope this helps."
        val result = AiJsonExtractor.extract(raw)
        assertTrue(result is Result.Ok)
        assertEquals(Strategy.BALANCED, (result as Result.Ok).strategy)
    }

    @Test fun `balanced parse handles nested braces`() {
        val raw = "Output: {\"outer\":{\"inner\":\"value\"},\"line\":5}"
        val result = AiJsonExtractor.extract(raw)
        assertTrue(result is Result.Ok)
        assertTrue((result as Result.Ok).element is JsonObject)
    }

    @Test fun `balanced parse handles escaped quotes inside string values`() {
        val raw = "Output: [{\"description\":\"The value is \\\"null\\\"\",\"line\":1}]"
        val result = AiJsonExtractor.extract(raw)
        assertTrue(result is Result.Ok, "Expected Ok, got $result")
        assertTrue((result as Result.Ok).element is JsonArray)
    }

    @Test fun `balanced parse handles bracket chars inside string values`() {
        val raw = """Output: [{"description":"arr[0] should be checked","line":2}]"""
        val result = AiJsonExtractor.extract(raw)
        assertTrue(result is Result.Ok)
        assertTrue((result as Result.Ok).element is JsonArray)
    }

    @Test fun `unbalanced trailing brace is tolerated`() {
        val raw = "[{\"type\":\"NULL_SAFETY\",\"line\":3}]}"
        val result = AiJsonExtractor.extract(raw)
        // The first balanced [ ... ] span wins; the stray } is ignored.
        assertTrue(result is Result.Ok)
    }

    @Test fun `empty input returns Empty`() {
        assertEquals(Result.Empty, AiJsonExtractor.extract(""))
    }

    @Test fun `non-JSON input returns Empty`() {
        assertEquals(Result.Empty, AiJsonExtractor.extract("I'm sorry I cannot help with that."))
    }
}
