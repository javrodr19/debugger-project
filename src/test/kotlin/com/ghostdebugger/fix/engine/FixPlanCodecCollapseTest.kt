package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixPlanCodecCollapseTest {
    @Test fun decodesCollapseBooleanReturnFromPlannerJson() {
        val raw = """{"issueId":"i1","operations":[{"type":"collapseBooleanReturn","line":7}]}"""
        val plan = FixPlanCodec.decode(raw)!!
        assertEquals("i1", plan.issueId)
        assertEquals(1, plan.operations.size)
        val op = plan.operations[0]
        assertTrue(op.toString(), op is CollapseBooleanReturn)
        assertEquals(7, (op as CollapseBooleanReturn).line)
    }
}
