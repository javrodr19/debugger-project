package com.ghostdebugger.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CustomRuleDecodeTest {
    private val yaml = """
        version: 1
        rules:
          - id: pce-rethrow-missing
            language: kotlin
            severity: WARNING
            message: "catch (e: Exception) must rethrow ProcessCanceledException first"
            match: { element: catch-clause, parameter-type: java.lang.Exception }
    """.trimIndent()

    @Test
    fun `decodes a single rule from YAML`() {
        val file = CustomRuleCodec.decode(yaml)
        assertNotNull(file)
        assertEquals(1, file!!.rules.size)
        assertEquals("pce-rethrow-missing", file.rules[0].id)
        assertEquals(RuleSeverity.WARNING, file.rules[0].severity)
        assertEquals("catch-clause", file.rules[0].match.element)
    }

    @Test
    fun `malformed YAML decodes to null, not a throw`() {
        assertNull(CustomRuleCodec.decode("rules: [ : : :"))
    }
}
