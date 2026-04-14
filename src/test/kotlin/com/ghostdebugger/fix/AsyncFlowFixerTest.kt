package com.ghostdebugger.fix

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsyncFlowFixerTest {

    @Test
    fun `appends catch handler to bare then chain`() {
        val fixer = AsyncFlowFixer()
        val content = "  fetchUser().then(u => setUser(u));\n"
        val issue = Issue(
            id = "i1", type = IssueType.UNHANDLED_PROMISE, severity = IssueSeverity.ERROR,
            title = "Unhandled promise rejection",
            description = "", filePath = "/src/A.tsx", line = 1,
            ruleId = "AEG-ASYNC-001"
        )
        val fix = fixer.generateFix(issue, content)
        assertNotNull(fix)
        assertTrue(fix!!.fixedCode.contains(".catch(console.error)"))
        assertTrue(fix.isDeterministic)
    }

    @Test
    fun `canFix returns false for MISSING_ERROR_HANDLING`() {
        val fixer = AsyncFlowFixer()
        val issue = Issue(
            id = "i1", type = IssueType.MISSING_ERROR_HANDLING, severity = IssueSeverity.ERROR,
            title = "Missing error handling", description = "",
            filePath = "/src/A.tsx", line = 1, ruleId = "AEG-ASYNC-001"
        )
        assertFalse(fixer.canFix(issue))
    }

    @Test
    fun `canFix returns false for MEMORY_LEAK`() {
        val fixer = AsyncFlowFixer()
        val issue = Issue(
            id = "i1", type = IssueType.MEMORY_LEAK, severity = IssueSeverity.WARNING,
            title = "Memory leak", description = "",
            filePath = "/src/A.tsx", line = 1, ruleId = "AEG-ASYNC-001"
        )
        assertFalse(fixer.canFix(issue))
    }
}
