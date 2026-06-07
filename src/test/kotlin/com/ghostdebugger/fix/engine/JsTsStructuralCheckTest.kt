package com.ghostdebugger.fix.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsTsStructuralCheckTest {
    @Test fun balancedContentIsBalanced() {
        assertTrue(JsTsStructuralCheck.isBalanced("function f() {\n    if (a) { g([1, 2]) }\n}\n"))
    }

    @Test fun droppedClosingBraceIsUnbalanced() {
        assertFalse(JsTsStructuralCheck.isBalanced("function f() {\n    if (a) { g()\n}\n"))
    }

    @Test fun extraCloserIsUnbalanced() {
        assertFalse(JsTsStructuralCheck.isBalanced("f())\n"))
    }

    @Test fun unbalancedBracketIsUnbalanced() {
        assertFalse(JsTsStructuralCheck.isBalanced("const x = [1, 2\n"))
    }

    @Test fun imbalanceInsideStringIsIgnored() {
        // the unmatched '(' and '{' live inside a string -> masked -> balanced
        assertTrue(JsTsStructuralCheck.isBalanced("const s = \"if (a) {\"\n"))
    }
}
