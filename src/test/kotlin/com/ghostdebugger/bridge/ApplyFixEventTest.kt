package com.ghostdebugger.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplyFixEventTest {
    @Test fun `APPLY_FIX parses to ApplyFixRequested`() {
        val msg = """{"type":"APPLY_FIX","payload":{"issueId":"i1","fixId":"f1"}}"""
        val event = UIEventParser.parse(msg)
        assertTrue(event is UIEvent.ApplyFixRequested)
        assertEquals("i1", (event as UIEvent.ApplyFixRequested).issueId)
        assertEquals("f1", event.fixId)
    }

    @Test fun `APPLY_FIX with missing fields defaults to empty strings`() {
        val msg = """{"type":"APPLY_FIX","payload":{}}"""
        val event = UIEventParser.parse(msg) as UIEvent.ApplyFixRequested
        assertEquals("", event.issueId)
        assertEquals("", event.fixId)
    }
}
