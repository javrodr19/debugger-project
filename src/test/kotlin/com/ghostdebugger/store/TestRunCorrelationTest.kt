package com.ghostdebugger.store

import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestRunCorrelationTest {
    private fun issue(path: String, line: Int) = Issue(
        id = "i", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
        title = "t", description = "", filePath = path, line = line, ruleId = "AEG-NULL-001"
    )

    @Test fun failureMatchesByLineAndFilename() {
        val issues = listOf(issue("/proj/src/Foo.ts", 12), issue("/proj/src/Bar.ts", 5))
        val frames = listOf(ParsedFrame("Foo.ts", 12))
        assertEquals(listOf(issues[0]), TestRunCorrelation.failureMatches(frames, issues))
    }

    @Test fun noMatchOnWrongLineOrFile() {
        val issues = listOf(issue("/proj/src/Foo.ts", 12))
        assertEquals(emptyList<Issue>(), TestRunCorrelation.failureMatches(listOf(ParsedFrame("Foo.ts", 99)), issues))
        assertEquals(emptyList<Issue>(), TestRunCorrelation.failureMatches(listOf(ParsedFrame("Other.ts", 12)), issues))
    }

    @Test fun emptyInputsYieldEmpty() {
        assertEquals(emptyList<Issue>(), TestRunCorrelation.failureMatches(emptyList(), listOf(issue("/a.ts", 1))))
        assertEquals(emptyList<Issue>(), TestRunCorrelation.failureMatches(listOf(ParsedFrame("a.ts", 1)), emptyList()))
    }

    @Test fun matchedIssueIsDistinctAcrossMultipleFrames() {
        val issues = listOf(issue("/proj/Foo.ts", 7))
        val frames = listOf(ParsedFrame("Foo.ts", 7), ParsedFrame("Foo.ts", 7))
        assertEquals(listOf(issues[0]), TestRunCorrelation.failureMatches(frames, issues))
    }

    @Test fun coverageEvidenceBranches() {
        assertNull(TestRunCorrelation.coverageEvidence(classFound = false, isCovered = false))
        assertEquals(EvidenceOutcome.LIKELY, TestRunCorrelation.coverageEvidence(classFound = true, isCovered = true))
        assertEquals(EvidenceOutcome.UNREACHED, TestRunCorrelation.coverageEvidence(classFound = true, isCovered = false))
    }
}
