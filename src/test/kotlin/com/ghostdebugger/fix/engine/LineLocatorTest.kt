package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineLocatorTest {
    private val src = "fun f() {\n    val x = user.name\n    return x\n}\n"

    @Test fun lineRangeReturnsCharSpanOfOneBasedLine() {
        // line 2 is "    val x = user.name"
        val r = LineLocator.lineRange(src, 2)!!
        assertEquals("    val x = user.name", src.substring(r.first, r.last + 1))
    }

    @Test fun lineRangeNullWhenOutOfRange() {
        assertNull(LineLocator.lineRange(src, 99))
    }

    @Test fun indexOfOnFindsTokenWithinLine() {
        val at = LineLocator.indexOfOn(src, 2, "user.")!!
        assertEquals("user.", src.substring(at, at + "user.".length))
    }

    @Test fun indexOfOnNullWhenTokenAbsentOnThatLine() {
        assertNull(LineLocator.indexOfOn(src, 3, "user."))
    }

    @Test fun lineAtMapsOffsetToOneBasedLine() {
        val at = src.indexOf("user.")
        assertEquals(2, LineLocator.lineAt(src, at))
    }
}
