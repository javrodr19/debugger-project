package com.ghostdebugger.fix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsTsPerFunctionComplexityTest {
    @Test fun measuresFunctionAndConstArrowBodies() {
        // f: 2 if + 1 && = 3 -> 4 ; g (const arrow): 1 if -> 2
        val content = "function f(a, b) {\n    if (a) {}\n    if (b && a) {}\n}\n" +
            "const g = (c) => {\n    if (c) {}\n}\n"
        val r = JsTsPerFunctionComplexity.measure(content)
        assertFalse(r.collision)
        assertEquals(4, r.byKey["f"])
        assertEquals(2, r.byKey["g"])
    }

    @Test fun skipsExpressionBodyArrow() {
        val content = "const h = (x) => x + 1\n"
        val r = JsTsPerFunctionComplexity.measure(content)
        assertNull(r.byKey["h"])
    }

    @Test fun bracematchesNestedBracesInBody() {
        // object literal inside the body must not end the body early; only the `if` counts -> 2
        val content = "function obj(a) {\n    const x = { k: 1 }\n    if (a) {}\n}\n"
        val r = JsTsPerFunctionComplexity.measure(content)
        assertEquals(2, r.byKey["obj"])
    }

    @Test fun ignoresKeywordsAndBracesInStrings() {
        // `if` and `{` live inside a string -> masked -> body has no decision points -> 1
        val content = "function s() {\n    const t = \"if (x) {\"\n}\n"
        val r = JsTsPerFunctionComplexity.measure(content)
        assertEquals(1, r.byKey["s"])
    }

    @Test fun flagsDuplicateNameCollision() {
        val content = "function dup(a) { if (a) {} }\nfunction dup(b) { if (b) {} }\n"
        val r = JsTsPerFunctionComplexity.measure(content)
        assertTrue(r.collision)
    }
}
