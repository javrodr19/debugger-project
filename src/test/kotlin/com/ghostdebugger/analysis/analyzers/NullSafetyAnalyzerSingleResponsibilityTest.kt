package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.IssueType
import com.ghostdebugger.testutil.FixtureFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NullSafetyAnalyzerSingleResponsibilityTest {

    @Test
    fun `analyzer does not emit STATE_BEFORE_INIT issues`() {
        val code = """
            function App() {
              const [items, setItems] = useState();
              return <ul>{items.map(i => <li key={i}>{i}</li>)}</ul>;
            }
        """.trimIndent()
        val file = FixtureFactory.parsedFile("/src/App.tsx", "tsx", code)
        val ctx = FixtureFactory.context(listOf(file))
        val analyzer = NullSafetyAnalyzer()
        
        val issues = analyzer.analyze(ctx)
        
        assertTrue(issues.none { it.type == IssueType.STATE_BEFORE_INIT }, 
            "NullSafetyAnalyzer should not emit STATE_BEFORE_INIT issues anymore")
    }

    @Test
    fun `analyzer still emits NULL_SAFETY issues with corrected title`() {
        val code = """
            let user = null;
            console.log(user.name);
        """.trimIndent()
        val file = FixtureFactory.parsedFile("/src/user.ts", "ts", code)
        val ctx = FixtureFactory.context(listOf(file))
        val analyzer = NullSafetyAnalyzer()
        
        val issues = analyzer.analyze(ctx)
        
        assertEquals(1, issues.size)
        assertEquals(IssueType.NULL_SAFETY, issues[0].type)
        assertEquals("Null reference: user may be null", issues[0].title)
    }
}
