package com.ghostdebugger.store

import com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzer
import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.Issue

/**
 * Pure correlation logic for [DebugObserver] — extracted so it is testable without driving an
 * `XDebugSession`/`XValue`. The observer keeps the XDebugger glue (frame, evaluation, value text) and
 * delegates these decisions. Behavior-preserving.
 */
object DebugObservationLogic {
    /** A debugger value of `null`/`undefined` CONFIRMS a null-safety finding; anything else DEMOTES it. */
    fun nullishOutcome(valueText: String): EvidenceOutcome =
        if (valueText == "null" || valueText == "undefined") EvidenceOutcome.CONFIRMED else EvidenceOutcome.DEMOTED

    /** Issues at the paused (file, line), path-normalized. */
    fun frameMatches(filePath: String, line: Int, activeIssues: List<Issue>): List<Issue> {
        val norm = filePath.replace("\\", "/")
        return activeIssues.filter { it.filePath.replace("\\", "/") == norm && it.line == line }
    }

    /** The probe expression for an issue, or null when the rule isn't debug-probeable. */
    fun probeExpressionFor(issue: Issue, analyzer: NullSafetyAnalyzer): String? =
        if (issue.ruleId == "AEG-NULL-001") analyzer.debugProbe(issue) else null
}
