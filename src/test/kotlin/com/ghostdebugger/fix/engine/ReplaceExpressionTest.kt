package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplaceExpressionTest {
    private fun ctx(content: String) = FixContext(content) { null }

    @Test fun replacesFirstOccurrenceOnLine() {
        val content = "fun f() {\n    val x = foo()\n}\n"
        val e = ReplaceExpression(line = 2, find = "foo()", replacement = "bar()").toEdit(ctx(content))!!
        val after = content.substring(0, e.startOffset) + e.replacement + content.substring(e.endOffset)
        assertEquals("fun f() {\n    val x = bar()\n}\n", after)
    }

    @Test fun nullWhenFindAbsentOnLine() {
        assertNull(ReplaceExpression(line = 1, find = "foo()", replacement = "bar()").toEdit(ctx("fun f() {\n    foo()\n}\n")))
    }
}
