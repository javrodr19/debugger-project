package com.ghostdebugger.fix

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FixerRegistryTest {
    @Test fun `all() returns exactly 3 entries`() { assertEquals(3, FixerRegistry.all().size) }

    @Test fun `forIssue returns fixer for known ruleId and correct type`() {
        val issue = Issue(
            id = "r1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: x.may be null", description = "",
            filePath = "/a.tsx", line = 1, ruleId = "AEG-NULL-001"
        )
        assertNotNull(FixerRegistry.forIssue(issue))
    }

    @Test fun `forIssue returns null for unknown ruleId`() {
        val issue = Issue(
            id = "r2", type = IssueType.HIGH_COMPLEXITY, severity = IssueSeverity.WARNING,
            title = "High complexity", description = "",
            filePath = "/a.kt", line = 1, ruleId = "AEG-CPX-001"
        )
        assertNull(FixerRegistry.forIssue(issue))
    }

    @Test fun `forIssue returns null when issue ruleId is null`() {
        val issue = Issue(
            id = "r3", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: x.may be null", description = "",
            filePath = "/a.tsx", line = 1, ruleId = null
        )
        assertNull(FixerRegistry.forIssue(issue))
    }

    @Test fun `forIssue returns null when canFix is false for the issue type`() {
        // AEG-ASYNC-001 is registered but only canFix UNHANDLED_PROMISE.
        val issue = Issue(
            id = "r4", type = IssueType.MEMORY_LEAK, severity = IssueSeverity.WARNING,
            title = "Memory leak", description = "",
            filePath = "/a.tsx", line = 1, ruleId = "AEG-ASYNC-001"
        )
        assertNull(FixerRegistry.forIssue(issue))
    }
}
