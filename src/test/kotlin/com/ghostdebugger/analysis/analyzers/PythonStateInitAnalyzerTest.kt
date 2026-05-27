package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.AegisPythonAnalysisTestCase
import org.junit.Test

class PythonStateInitAnalyzerTest : AegisPythonAnalysisTestCase() {

    @Test
    fun testReadBeforeAssignment() {
        val source = """
            class Controller:
                def __init__(self):
                    print(self.active)
                    self.active = True
        """.trimIndent()
        expectFinding(source, "AEG-STATE-PY-001", 3) { PythonStateInitAnalyzer() }
    }

    @Test
    fun testInspectFunctionParsing() {
        try {
            Class.forName("com.jetbrains.python.parsing.FunctionParsing")
        } catch (e: ExceptionInInitializerError) {
            println("INITIALIZER EXCEPTION CAUSE:")
            e.cause?.printStackTrace(System.out)
            val cause = e.cause
            if (cause is java.lang.IllegalStateException) {
                println("ILLEGAL STATE EXCEPTION MESSAGE: ${cause.message}")
            }
        } catch (e: Throwable) {
            println("OTHER THROWABLE: $e")
            e.cause?.printStackTrace(System.out)
        }
    }
}
