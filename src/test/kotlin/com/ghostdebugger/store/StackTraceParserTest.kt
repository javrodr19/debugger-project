package com.ghostdebugger.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StackTraceParserTest {
    @Test fun parsesJvmParenthesizedFrame() {
        assertTrue(StackTraceParser.parse("\tat com.example.Foo.bar(Foo.kt:42)").contains(ParsedFrame("Foo.kt", 42)))
    }

    @Test fun parsesNodeWebpackFrame() {
        assertTrue(StackTraceParser.parse("at foo (webpack:///./src/Foo.ts:125:7)").contains(ParsedFrame("Foo.ts", 125)))
    }

    @Test fun parsesRawPathFrame() {
        assertTrue(StackTraceParser.parse("test/index.spec.js:15:10").contains(ParsedFrame("index.spec.js", 15)))
    }

    @Test fun stripsWindowsBackslashPathToSimpleName() {
        assertTrue(StackTraceParser.parse("at C:\\proj\\src\\File.ts:30:2").contains(ParsedFrame("File.ts", 30)))
    }

    @Test fun blankNullOrNonFrameYieldsEmpty() {
        assertTrue(StackTraceParser.parse(null).isEmpty())
        assertTrue(StackTraceParser.parse("   ").isEmpty())
        assertTrue(StackTraceParser.parse("no stack frames here").isEmpty())
    }

    @Test fun deduplicatesByFileAndLine() {
        val frames = StackTraceParser.parse("at a (Foo.ts:1:1)\nat b (Foo.ts:1:9)")
        assertEquals(1, frames.count { it == ParsedFrame("Foo.ts", 1) })
    }
}
