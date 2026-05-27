package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.AegisPythonAnalysisTestCase
import org.junit.Test

class PythonAsyncFlowAnalyzerTest : AegisPythonAnalysisTestCase() {

    @Test
    fun testUnawaitedCall() {
        val source = """
            async def fetch_data():
                return 42

            async def main():
                fetch_data()
        """.trimIndent()
        expectFinding(source, "AEG-ASYNC-PY-001", 5) { PythonAsyncFlowAnalyzer() }
    }

    @Test
    fun testAwaitedCall() {
        val source = """
            async def fetch_data():
                return 42

            async def main():
                await fetch_data()
        """.trimIndent()
        expectNoFinding(source, "AEG-ASYNC-PY-001") { PythonAsyncFlowAnalyzer() }
    }
}
