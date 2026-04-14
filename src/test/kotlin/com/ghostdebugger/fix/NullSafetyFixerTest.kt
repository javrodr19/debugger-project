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

class NullSafetyFixerTest {

    @Test
    fun `generates optional chaining fix for direct property access on null var`() {
        val fixer = NullSafetyFixer()
        val content = """
            function render() {
              const [user, setUser] = useState(null);
              return <div>{user.name}</div>;
            }
        """.trimIndent()
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: user.may be null",
            description = "user may be null",
            filePath = "/src/A.tsx", line = 3,
            ruleId = "AEG-NULL-001"
        )
        val fix = fixer.generateFix(issue, content)
        assertNotNull(fix)
        assertTrue(fix!!.fixedCode.contains("user?.name"))
        assertFalse(fix.fixedCode.contains("user.name"))
        assertEquals(true, fix.isDeterministic)
        assertEquals(1.0, fix.confidence)
    }

    @Test
    fun `returns null when access is already optional-chained`() {
        val fixer = NullSafetyFixer()
        val content = """
            function render() {
              const [user, setUser] = useState(null);
              return <div>{user?.name}</div>;
            }
        """.trimIndent()
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: user.may be null",
            description = "user may be null",
            filePath = "/src/A.tsx", line = 3,
            ruleId = "AEG-NULL-001"
        )
        assertNull(fixer.generateFix(issue, content))
    }

    @Test
    fun `returns null when title does not match expected pattern`() {
        val fixer = NullSafetyFixer()
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Something completely different",
            description = "", filePath = "/src/A.tsx", line = 1,
            ruleId = "AEG-NULL-001"
        )
        assertNull(fixer.generateFix(issue, "const x = 1;"))
    }
}
