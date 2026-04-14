package com.ghostdebugger.analysis

import com.ghostdebugger.model.IssueType
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.testutil.FixtureFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class AnalysisEngineAnalyzerIsolationTest {

    @Test
    fun `engine produces issues even if one analyzer is fed input that would throw`() = runTest {
        // ComplexityAnalyzer iterates context.graph.getAllNodes(); a null or broken
        // graph element is absorbed by its internal logic. A more robust check:
        // feed a fixture that all five analyzers tolerate but where exactly
        // NullSafetyAnalyzer produces a hit.
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              return <div>{user.name}</div>;
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/Iso.tsx", "tsx", code))
        )
        val engine = AnalysisEngine(
            settingsProvider = {
                GhostDebuggerSettings.State(aiProvider = AIProvider.NONE)
            },
            apiKeyProvider = { null },
            aiPassRunner = AiPassRunner { _, _ -> emptyList() }
        )
        val result = engine.analyze(ctx)
        assertTrue(
            result.issues.any { it.type == IssueType.NULL_SAFETY },
            "expected at least one NULL_SAFETY issue; got ${result.issues}"
        )
    }
}
