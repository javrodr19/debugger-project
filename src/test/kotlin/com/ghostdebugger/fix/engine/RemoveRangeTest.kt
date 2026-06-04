package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoveRangeTest {
    private fun ctx(content: String) = FixContext(content) { null }
    private fun apply(content: String, op: FixOperation): String {
        val e = op.toEdit(ctx(content))!!
        return content.substring(0, e.startOffset) + e.replacement + content.substring(e.endOffset)
    }

    @Test fun removesASingleLineIncludingItsNewline() {
        assertEquals("a\nc\n", apply("a\nb\nc\n", RemoveRange(startLine = 2, endLine = 2)))
    }

    @Test fun removesAMultiLineRange() {
        assertEquals("a\n", apply("a\nb\nc\n", RemoveRange(startLine = 2, endLine = 3)))
    }

    @Test fun nullWhenStartAfterEnd() {
        assertNull(RemoveRange(startLine = 3, endLine = 2).toEdit(ctx("a\nb\nc\n")))
    }

    @Test fun nullWhenOutOfRange() {
        assertNull(RemoveRange(startLine = 5, endLine = 5).toEdit(ctx("a\nb\n")))
    }
}
