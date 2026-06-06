package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractOpsTest {
    private fun apply(content: String, op: FixOperation): String? {
        val edit = op.toEdit(FixContext(content) { null }) ?: return null
        return content.substring(0, edit.startOffset) + edit.replacement + content.substring(edit.endOffset)
    }

    @Test fun replaceLinesReplacesRangeVerbatim() {
        val c = "a\nb\nc\nd\n"
        assertEquals("a\nX\nd\n", apply(c, ReplaceLines(2, 3, "X")))
    }

    @Test fun replaceLinesSingleLine() {
        val c = "a\nb\nc\n"
        assertEquals("a\n  val r = g()\nc\n", apply(c, ReplaceLines(2, 2, "  val r = g()")))
    }

    @Test fun replaceLinesOutOfRangeReturnsNull() {
        assertNull(ReplaceLines(2, 9, "X").toEdit(FixContext("a\nb\n") { null }))
    }

    @Test fun replaceLinesStartAfterEndReturnsNull() {
        assertNull(ReplaceLines(3, 2, "X").toEdit(FixContext("a\nb\nc\nd\n") { null }))
    }

    @Test fun insertLinesAfterAddsBlankSeparatedBlock() {
        val c = "a\nb\n"
        assertEquals("a\n\nNEW\nb\n", apply(c, InsertLinesAfter(1, "NEW")))
    }

    @Test fun insertLinesAfterMultiLineText() {
        val c = "a\nb\n"
        // insert point is the offset of line 2's terminating '\n', so that original '\n' is preserved
        // AFTER the inserted text — the extracted function ends up newline-terminated.
        assertEquals("a\nb\n\nfun g() {\n    h()\n}\n", apply(c, InsertLinesAfter(2, "fun g() {\n    h()\n}")))
    }

    @Test fun insertLinesAfterOutOfRangeReturnsNull() {
        assertNull(InsertLinesAfter(9, "NEW").toEdit(FixContext("a\n") { null }))
    }
}
