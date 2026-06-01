package com.ghostdebugger.fix.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FixContextTest {
    @Test fun `exposes content and lazily resolves psiFile only when asked`() {
        var calls = 0
        val ctx = FixContext("val a = 1") { calls++; null }
        assertEquals("val a = 1", ctx.content)
        assertEquals(0, calls)
        assertNull(ctx.psiFile)
        assertNull(ctx.psiFile)
        assertEquals(1, calls)
    }
}
