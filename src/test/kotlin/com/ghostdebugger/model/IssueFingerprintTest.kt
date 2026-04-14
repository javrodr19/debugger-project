package com.ghostdebugger.model

import kotlin.test.Test
import kotlin.test.assertEquals

class IssueFingerprintTest {

    private fun baseIssue(
        ruleId: String? = null,
        type: IssueType = IssueType.NULL_SAFETY,
        filePath: String = "/src/A.tsx",
        line: Int = 5
    ) = Issue(
        id = "x", type = type, severity = IssueSeverity.ERROR,
        title = "t", description = "d",
        filePath = filePath, line = line,
        ruleId = ruleId
    )

    @Test
    fun `fingerprint uses ruleId when present`() {
        val i = baseIssue(ruleId = "AEG-NULL-001")
        assertEquals("AEG-NULL-001:/src/A.tsx:5", i.fingerprint())
    }

    @Test
    fun `fingerprint falls back to type name when ruleId is null`() {
        val i = baseIssue(ruleId = null)
        assertEquals("NULL_SAFETY:/src/A.tsx:5", i.fingerprint())
    }

    @Test
    fun `fingerprint is stable across equal triples`() {
        val a = baseIssue(ruleId = "AEG-NULL-001")
        val b = baseIssue(ruleId = "AEG-NULL-001")
        assertEquals(a.fingerprint(), b.fingerprint())
    }

    @Test
    fun `fingerprint differs when line differs`() {
        val a = baseIssue(ruleId = "AEG-NULL-001", line = 5)
        val b = baseIssue(ruleId = "AEG-NULL-001", line = 6)
        assert(a.fingerprint() != b.fingerprint())
    }
}
