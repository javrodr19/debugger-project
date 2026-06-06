package com.ghostdebugger.fix.engine

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PerFunctionComplexityTest : BasePlatformTestCase() {
    fun testMeasuresEachFunctionsOwnComplexity() {
        // f body has two `if` + one `&&` = 3 decision points -> 1+3 = 4 ; g has none -> 1
        val content = "fun f(a: Boolean, b: Boolean) {\n" +
            "    if (a) {}\n" +
            "    if (b && a) {}\n" +
            "}\n" +
            "fun g() { println(1) }\n"
        val r = PerFunctionComplexity.measure(project, content)
        assertFalse(r.collision)
        assertEquals(4, r.byKey["f/2"])
        assertEquals(1, r.byKey["g/0"])
    }

    fun testExpressionBodyMeasured() {
        val content = "fun h(a: Boolean): Int = if (a) 1 else 2\n"
        val r = PerFunctionComplexity.measure(project, content)
        // expression body `if (a) 1 else 2` -> one `if` (else not counted) -> 1+1 = 2
        assertEquals(2, r.byKey["h/1"])
    }

    fun testDuplicateNameAndArityFlagsCollision() {
        val content = "fun f(a: Int) {}\nfun f(b: Int) {}\n"
        val r = PerFunctionComplexity.measure(project, content)
        assertTrue(r.collision)
    }

    fun testFunctionWithoutBodyIsSkipped() {
        // abstract fun has no body -> not in the map; the concrete one is
        val content = "abstract class C {\n    abstract fun a()\n    fun b() { if (true) {} }\n}\n"
        val r = PerFunctionComplexity.measure(project, content)
        assertNull(r.byKey["a/0"])
        assertEquals(2, r.byKey["b/0"])
    }
}
