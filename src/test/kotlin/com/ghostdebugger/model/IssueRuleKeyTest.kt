package com.ghostdebugger.model

import org.junit.Assert.assertEquals
import org.junit.Test

class IssueRuleKeyTest {
    private fun issue(ruleId: String?, type: IssueType, line: Int) = Issue(
        id = "x", type = type, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = "A.kt", line = line, ruleId = ruleId
    )

    @Test fun ruleKeyUsesRuleIdWhenPresent() {
        assertEquals("AEG-CAST-KT-001", issue("AEG-CAST-KT-001", IssueType.NULL_SAFETY, 1).ruleKey())
    }

    @Test fun ruleKeyFallsBackToTypeNameWhenRuleIdNull() {
        assertEquals("NULL_SAFETY", issue(null, IssueType.NULL_SAFETY, 1).ruleKey())
    }

    @Test fun fingerprintComposesRuleKeyPathAndLine() {
        assertEquals("AEG-CAST-KT-001:A.kt:7", issue("AEG-CAST-KT-001", IssueType.NULL_SAFETY, 7).fingerprint())
    }

    @Test fun fingerprintUsesTypeNameWhenRuleIdNull() {
        assertEquals("NULL_SAFETY:A.kt:7", issue(null, IssueType.NULL_SAFETY, 7).fingerprint())
    }
}
