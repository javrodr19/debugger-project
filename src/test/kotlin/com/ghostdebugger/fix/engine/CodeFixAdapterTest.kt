package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.CodeFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodeFixAdapterTest {
    private fun codeFix(lineStart: Int, lineEnd: Int, fixed: String) = CodeFix(
        id = "f1", issueId = "i1", description = "d",
        originalCode = "", fixedCode = fixed, filePath = "/x.kt",
        lineStart = lineStart, lineEnd = lineEnd, isDeterministic = true, confidence = 1.0
    )

    @Test fun `lineStartOffsets marks each line start`() {
        // "a\nbb\nc" -> line0@0, line1@2, line2@5
        assertEquals(listOf(0, 2, 5), lineStartOffsets("a\nbb\nc").toList())
    }

    @Test fun `wraps a single-line CodeFix into one ReplaceRange covering that line`() {
        val content = "val a = 1\nval b = 2\n"          // line2 = "val b = 2" at offset 10..19
        val plan = codeFix(2, 2, "val b = 3").toFixPlan(content)!!
        assertEquals(FixPlan("i1", listOf(ReplaceRange(10, 19, "val b = 3"))), plan)
        assertEquals("val a = 1\nval b = 3\n", plan.toEdits(content)!!.applyTo(content))
    }

    @Test fun `returns null when the CodeFix line range is out of bounds`() {
        assertNull(codeFix(5, 6, "x").toFixPlan("only\ntwo\n"))
    }
}
