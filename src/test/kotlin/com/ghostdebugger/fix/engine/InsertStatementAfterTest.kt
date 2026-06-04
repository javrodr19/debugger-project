package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsertStatementAfterTest {
    private fun ctx(content: String) = FixContext(content) { null }

    @Test fun insertsIndentedLineAfterTarget() {
        val content = "fun f() {\n    doThing()\n}\n"
        val e = InsertStatementAfter(line = 2, statement = "cleanup()").toEdit(ctx(content))!!
        val after = content.substring(0, e.startOffset) + e.replacement + content.substring(e.endOffset)
        assertEquals("fun f() {\n    doThing()\n    cleanup()\n}\n", after)
    }

    @Test fun appendsAfterLastLineWithoutTrailingNewline() {
        val content = "line1"  // no trailing newline
        val e = InsertStatementAfter(line = 1, statement = "line2").toEdit(ctx(content))!!
        val after = content.substring(0, e.startOffset) + e.replacement + content.substring(e.endOffset)
        assertEquals("line1\nline2", after)
    }

    @Test fun nullWhenLineOutOfRange() {
        assertNull(InsertStatementAfter(line = 9, statement = "x()").toEdit(ctx("fun f() {}\n")))
    }
}
