package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NullSafetyAnalyzerDebugProbeTest {
    private val analyzer = NullSafetyAnalyzer()
    private fun issue(title: String) = Issue(
        id = "i", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = title, description = "", filePath = "/a.ts", line = 1, ruleId = "AEG-NULL-001"
    )

    @Test fun extractsTheVariableExpressionFromAWellFormedTitle() {
        assertEquals("user.profile", analyzer.debugProbe(issue("Null reference: user.profile may be null")))
    }

    @Test fun returnsNullForUnrecognizedTitles() {
        assertNull(analyzer.debugProbe(issue("Something unrelated")))
        assertNull(analyzer.debugProbe(issue("Null reference: x")))
    }
}
