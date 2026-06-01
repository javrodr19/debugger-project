package com.ghostdebugger

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineForTest {
    private fun issue(id: String, path: String) = Issue(
        id = id, type = IssueType.NULL_SAFETY, severity = IssueSeverity.WARNING,
        title = "t", description = "", filePath = path, line = 1, ruleId = "AEG-CAST-KT-001"
    )

    @Test fun keepsOnlyIssuesForTheGivenFile() {
        val here = issue("a", "/proj/A.kt")
        val elsewhere = issue("b", "/proj/B.kt")
        assertEquals(listOf(here), baselineFor(listOf(here, elsewhere), "/proj/A.kt"))
    }

    @Test fun normalizesBackslashesOnBothSides() {
        val here = issue("a", "C:\\proj\\A.kt")
        assertEquals(listOf(here), baselineFor(listOf(here), "C:/proj/A.kt"))
    }

    @Test fun returnsEmptyWhenNoneMatch() {
        assertEquals(emptyList<Issue>(), baselineFor(listOf(issue("a", "/proj/A.kt")), "/proj/Z.kt"))
    }
}
