package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.AegisPythonAnalysisTestCase
import org.junit.Test

class PythonNullSafetyAnalyzerTest : AegisPythonAnalysisTestCase() {

    @Test
    fun testNullSafetyViolationOnAttributeAccess() {
        val source = """
            def process(x: int | None):
                print(x.numerator)
        """.trimIndent()
        expectFinding(source, "AEG-NONE-PY-001", 2) { PythonNullSafetyAnalyzer() }
    }

    @Test
    fun testNullSafetySafeWithGuard() {
        val source = """
            def process(x: int | None):
                if x is not None:
                    print(x.numerator)
        """.trimIndent()
        expectNoFinding(source, "AEG-NONE-PY-001") { PythonNullSafetyAnalyzer() }
    }

    @Test
    fun testExplicitNoneAccess() {
        val source = """
            def do_something():
                y = None
                print(y.something)
        """.trimIndent()
        expectFinding(source, "AEG-NONE-PY-001", 3) { PythonNullSafetyAnalyzer() }
    }
}
