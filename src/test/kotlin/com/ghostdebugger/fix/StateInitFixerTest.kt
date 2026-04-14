package com.ghostdebugger.fix

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateInitFixerTest {

    @Test
    fun `generates fix at declaration line, not usage line`() {
        val fixer = StateInitFixer()
        val content = """
            function App() {
              const [items, setItems] = useState();
              return <ul>{items.map(i => <li key={i}>{i}</li>)}</ul>;
            }
        """.trimIndent()
        val issue = Issue(
            id = "i1", type = IssueType.STATE_BEFORE_INIT, severity = IssueSeverity.ERROR,
            title = "Uninitialized state used: items",
            description = "items is undefined",
            filePath = "/src/App.tsx", line = 3,   // usage line
            ruleId = "AEG-STATE-001"
        )
        val fix = fixer.generateFix(issue, content)
        assertNotNull(fix)
        assertEquals(2, fix!!.lineStart)   // declaration is line 2
        assertTrue(fix.fixedCode.contains("useState([])"))
        assertFalse(fix.fixedCode.contains("useState()"))
        assertEquals(true, fix.isDeterministic)
    }

    @Test
    fun `returns null when useState declaration cannot be found`() {
        val fixer = StateInitFixer()
        val issue = Issue(
            id = "i1", type = IssueType.STATE_BEFORE_INIT, severity = IssueSeverity.ERROR,
            title = "Uninitialized state used: items",
            description = "", filePath = "/src/App.tsx", line = 3,
            ruleId = "AEG-STATE-001"
        )
        // File content has no matching useState() declaration for "items".
        assertNull(fixer.generateFix(issue, "const x = 1;"))
    }
}
