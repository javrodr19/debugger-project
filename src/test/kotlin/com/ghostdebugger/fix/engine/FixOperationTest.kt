package com.ghostdebugger.fix.engine

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FixOperationTest {
    @Test fun `ReplaceRange produces the matching edit when offsets are in range`() {
        val op = ReplaceRange(0, 5, "hello")
        assertEquals(TextEdit(0, 5, "hello"), op.toEdit("XXXXX world"))
    }

    @Test fun `ReplaceRange returns null when offsets are out of range`() {
        assertNull(ReplaceRange(0, 50, "x").toEdit("short"))
        assertNull(ReplaceRange(-1, 2, "x").toEdit("short"))
        assertNull(ReplaceRange(3, 2, "x").toEdit("short")) // start > end
    }

    @Test fun `FixOperation round-trips through polymorphic JSON`() {
        val op: FixOperation = ReplaceRange(1, 4, "abc")
        val json = Json.encodeToString(FixOperation.serializer(), op)
        assertEquals(op, Json.decodeFromString(FixOperation.serializer(), json))
    }
}
