package com.ghostdebugger.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeFixExtensionTest {
    private fun baseFix() = CodeFix(
        id = "f1", issueId = "i1", description = "d",
        originalCode = "old", fixedCode = "new",
        filePath = "/a.tsx", lineStart = 3, lineEnd = 3
    )

    @Test fun `isDeterministic defaults to false`() {
        assertFalse(baseFix().isDeterministic)
    }

    @Test fun `confidence defaults to 0_7`() {
        assertEquals(0.7, baseFix().confidence)
    }

    @Test fun `round-trips through Json serialization`() {
        val fix = baseFix().copy(isDeterministic = true, confidence = 1.0)
        val json = Json.encodeToString(fix)
        assertTrue(json.contains("isDeterministic"))
        val back = Json.decodeFromString<CodeFix>(json)
        assertEquals(fix, back)
    }
}
