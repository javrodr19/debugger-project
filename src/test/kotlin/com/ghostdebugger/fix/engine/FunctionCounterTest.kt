package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionCounterTest {
    @Test fun countsKotlinFunKeywords() {
        assertEquals(2, FunctionCounter.count("fun a() {}\nfun b() {}\n"))
    }

    @Test fun countsJsFunctionsAndArrows() {
        assertEquals(2, FunctionCounter.count("function f() {}\nconst g = () => 1\n"))
    }

    @Test fun ignoresKeywordsInStringsAndComments() {
        // `fun`/`=>` inside a string and a comment must not be counted; only the real `fun a` counts.
        assertEquals(1, FunctionCounter.count("// fun in a comment =>\nval s = \"fun x => y\"\nfun a() {}\n"))
    }

    @Test fun neverReturnsBelowOne() {
        assertEquals(1, FunctionCounter.count("val x = 1\n"))
    }
}
