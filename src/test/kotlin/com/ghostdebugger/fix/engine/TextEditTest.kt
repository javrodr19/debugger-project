package com.ghostdebugger.fix.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class TextEditTest {
    @Test fun `applies a single edit`() {
        val out = listOf(TextEdit(0, 5, "hello")).applyTo("XXXXX world")
        assertEquals("hello world", out)
    }

    @Test fun `applies multiple non-overlapping edits regardless of list order`() {
        val edits = listOf(TextEdit(0, 1, "A"), TextEdit(6, 7, "B"))
        assertEquals("Aello Borld", edits.applyTo("hello world"))
    }

    @Test fun `replacement that changes length does not corrupt later offsets`() {
        val edits = listOf(TextEdit(0, 1, "LONG"), TextEdit(6, 7, "B"))
        assertEquals("LONGello Borld", edits.applyTo("hello world"))
    }
}
