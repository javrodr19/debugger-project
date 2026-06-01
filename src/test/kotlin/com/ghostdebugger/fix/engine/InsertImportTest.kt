package com.ghostdebugger.fix.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InsertImportTest {
    private fun ctx(content: String) = FixContext(content) { null }

    @Test fun `inserts after the last existing import`() {
        val src = "package p\n\nimport a.B\nimport a.C\n\nfun f() {}\n"
        val edit = InsertImport("a.D").toEdit(ctx(src))!!
        val out = listOf(edit).applyTo(src)
        assertEquals("package p\n\nimport a.B\nimport a.C\nimport a.D\n\nfun f() {}\n", out)
    }

    @Test fun `inserts after package when no imports`() {
        val src = "package p\n\nfun f() {}\n"
        val out = listOf(InsertImport("a.D").toEdit(ctx(src))!!).applyTo(src)
        assertEquals("package p\nimport a.D\n\nfun f() {}\n", out)
    }

    @Test fun `returns null when the import already exists`() {
        val src = "package p\n\nimport a.D\n\nfun f() {}\n"
        assertNull(InsertImport("a.D").toEdit(ctx(src)))
    }
}
