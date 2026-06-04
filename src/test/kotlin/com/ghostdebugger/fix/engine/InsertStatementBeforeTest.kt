package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsertStatementBeforeTest {
    private fun ctx(content: String) = FixContext(content) { null }

    @Test fun insertsIndentedLineBeforeTarget() {
        val content = "fun f() {\n    doThing()\n}\n"
        val e = InsertStatementBefore(line = 2, statement = "check()").toEdit(ctx(content))!!
        val after = content.substring(0, e.startOffset) + e.replacement + content.substring(e.endOffset)
        assertEquals("fun f() {\n    check()\n    doThing()\n}\n", after)
    }

    @Test fun nullWhenLineOutOfRange() {
        assertNull(InsertStatementBefore(line = 9, statement = "x()").toEdit(ctx("fun f() {}\n")))
    }
}
