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

    @Test
    fun `does not rewrite varName_x inside a double-quoted string literal`() {
        // V1.4.1: pre-fix the text regex matched anywhere on the line, including
        // inside string literals — corrupting their contents.
        val fixer = NullSafetyFixer()
        val content = """
            function render() {
              const [user, setUser] = useState(null);
              console.log("debug: user.name lookup");
            }
        """.trimIndent()
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: user.may be null",
            description = "", filePath = "/src/A.tsx", line = 3,
            ruleId = "AEG-NULL-001"
        )
        assertNull(fixer.generateFix(issue, content),
            "Should not propose a fix when the only `user.` on the line is inside a string literal")
    }

    @Test
    fun `does not rewrite varName_x inside a single-quoted string literal`() {
        val fixer = NullSafetyFixer()
        val content = """
            function render() {
              const [user, setUser] = useState(null);
              const k = 'user.name';
            }
        """.trimIndent()
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: user.may be null",
            description = "", filePath = "/src/A.tsx", line = 3,
            ruleId = "AEG-NULL-001"
        )
        assertNull(fixer.generateFix(issue, content))
    }

    @Test
    fun `does not rewrite varName_x inside a line comment`() {
        val fixer = NullSafetyFixer()
        val content = """
            function render() {
              const [user, setUser] = useState(null);
              doStuff(); // user.name would be unsafe
            }
        """.trimIndent()
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: user.may be null",
            description = "", filePath = "/src/A.tsx", line = 3,
            ruleId = "AEG-NULL-001"
        )
        assertNull(fixer.generateFix(issue, content),
            "Should not propose a fix when the only `user.` on the line is inside a // comment")
    }

    @Test
    fun `does not rewrite varName_x inside a single-line block comment`() {
        val fixer = NullSafetyFixer()
        val content = """
            function render() {
              const [user, setUser] = useState(null);
              const x = 1; /* user.name would crash */
            }
        """.trimIndent()
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: user.may be null",
            description = "", filePath = "/src/A.tsx", line = 3,
            ruleId = "AEG-NULL-001"
        )
        assertNull(fixer.generateFix(issue, content))
    }

    @Test
    fun `rewrites code occurrence even when a string literal also contains varName`() {
        // Mixed line: real code access AND a string-literal mention. Should rewrite
        // the code occurrence only.
        val fixer = NullSafetyFixer()
        val content = """
            function render() {
              const [user, setUser] = useState(null);
              log("user.name"); user.name;
            }
        """.trimIndent()
        val issue = Issue(
            id = "i1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: user.may be null",
            description = "", filePath = "/src/A.tsx", line = 3,
            ruleId = "AEG-NULL-001"
        )
        val fix = fixer.generateFix(issue, content)
        assertNotNull(fix)
        // The string literal stays intact:
        assertTrue(fix!!.fixedCode.contains("\"user.name\""),
            "Expected the string literal to be untouched; got: ${fix.fixedCode}")
        // The code access gets the safe call:
        assertTrue(fix.fixedCode.contains("user?.name;"),
            "Expected the code access to get optional chaining; got: ${fix.fixedCode}")
    }
}
