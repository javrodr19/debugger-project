package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixPlanCodecExtractOpsTest {
    @Test fun decodesReplaceLinesAndInsertLinesAfter() {
        val raw = """{"issueId":"i1","operations":[""" +
            """{"type":"replaceLines","startLine":3,"endLine":5,"text":"  val r = g(a)"},""" +
            """{"type":"insertLinesAfter","afterLine":9,"text":"fun g(a: Int) = a"}""" +
            """]}"""
        val plan = FixPlanCodec.decode(raw)!!
        assertEquals("i1", plan.issueId)
        assertEquals(2, plan.operations.size)
        val op0 = plan.operations[0]
        val op1 = plan.operations[1]
        assertTrue(op0.toString(), op0 is ReplaceLines)
        assertTrue(op1.toString(), op1 is InsertLinesAfter)
        assertEquals(3, (op0 as ReplaceLines).startLine)
        assertEquals(5, op0.endLine)
        assertEquals(9, (op1 as InsertLinesAfter).afterLine)
    }
}
