package com.ghostdebugger.store

import com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzer
import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugObservationLogicTest {
    private fun issue(
        path: String = "/proj/Foo.ts", line: Int = 7,
        rule: String = "AEG-NULL-001", title: String = "Null reference: x may be null"
    ) = Issue(
        id = "i", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = title, description = "", filePath = path, line = line, ruleId = rule
    )

    @Test fun nullishOutcomeConfirmsNullAndUndefined() {
        assertEquals(EvidenceOutcome.CONFIRMED, DebugObservationLogic.nullishOutcome("null"))
        assertEquals(EvidenceOutcome.CONFIRMED, DebugObservationLogic.nullishOutcome("undefined"))
        assertEquals(EvidenceOutcome.DEMOTED, DebugObservationLogic.nullishOutcome("42"))
        assertEquals(EvidenceOutcome.DEMOTED, DebugObservationLogic.nullishOutcome("\"hello\""))
    }

    @Test fun frameMatchesByNormalizedPathAndLine() {
        val issues = listOf(issue(path = "/proj/Foo.ts", line = 7), issue(path = "/proj/Bar.ts", line = 7))
        assertEquals(listOf(issues[0]), DebugObservationLogic.frameMatches("/proj/Foo.ts", 7, issues))
        assertEquals(listOf(issues[0]), DebugObservationLogic.frameMatches("\\proj\\Foo.ts", 7, issues))
    }

    @Test fun frameMatchesExcludesLineOrFileMismatch() {
        val issues = listOf(issue(path = "/proj/Foo.ts", line = 7))
        assertEquals(emptyList<Issue>(), DebugObservationLogic.frameMatches("/proj/Foo.ts", 8, issues))
        assertEquals(emptyList<Issue>(), DebugObservationLogic.frameMatches("/proj/Other.ts", 7, issues))
    }

    @Test fun probeExpressionForNullSafetyRuleOnly() {
        val analyzer = NullSafetyAnalyzer()
        assertEquals("user", DebugObservationLogic.probeExpressionFor(
            issue(title = "Null reference: user may be null"), analyzer))
        assertNull(DebugObservationLogic.probeExpressionFor(issue(rule = "AEG-OTHER-001"), analyzer))
    }
}
