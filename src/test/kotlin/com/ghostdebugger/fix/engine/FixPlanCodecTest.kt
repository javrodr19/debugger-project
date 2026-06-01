package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixPlanCodecTest {

    @Test fun decodesADirectJsonPlan() {
        val raw = """{"issueId":"i1","operations":[{"type":"replaceRange","startOffset":0,"endOffset":3,"text":"x"}]}"""
        val plan = FixPlanCodec.decode(raw)!!
        assertEquals("i1", plan.issueId)
        assertEquals(1, plan.operations.size)
        assertTrue(plan.operations[0] is ReplaceRange)
        assertEquals(ReplaceRange(0, 3, "x"), plan.operations[0])
    }

    @Test fun decodesAFencedJsonPlanWithMultipleOps() {
        val raw = """
            Here is the plan:
            ```json
            {"issueId":"i2","operations":[
              {"type":"insertImport","fqName":"a.b.C"},
              {"type":"convertToSafeCast","asOffset":42}
            ]}
            ```
        """.trimIndent()
        val plan = FixPlanCodec.decode(raw)!!
        assertEquals("i2", plan.issueId)
        assertEquals(listOf(InsertImport("a.b.C"), ConvertToSafeCast(42)), plan.operations)
    }

    @Test fun returnsNullOnGarbage() {
        assertNull(FixPlanCodec.decode("I could not produce a plan."))
    }

    @Test fun returnsNullOnUnknownOperationType() {
        val raw = """{"issueId":"i","operations":[{"type":"frobnicate","x":1}]}"""
        assertNull(FixPlanCodec.decode(raw))
    }

    @Test fun returnsNullOnEmpty() {
        assertNull(FixPlanCodec.decode(""))
    }
}
