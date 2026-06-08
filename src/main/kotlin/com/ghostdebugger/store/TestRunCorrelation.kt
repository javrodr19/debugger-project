package com.ghostdebugger.store

import com.ghostdebugger.model.EvidenceOutcome
import com.ghostdebugger.model.Issue

/**
 * Pure correlation logic for [TestRunObserver] — extracted so it is testable without driving the real
 * `SMTRunnerEventsListener`. The observer keeps the IntelliJ glue (stacktrace + coverage data) and
 * delegates these decisions. Behavior-preserving.
 */
object TestRunCorrelation {
    /** Issues hit by any failure frame (same line + the issue's path ends with the frame's filename). */
    fun failureMatches(frames: List<ParsedFrame>, activeIssues: List<Issue>): List<Issue> =
        activeIssues.filter { issue ->
            frames.any { f -> issue.line == f.line && issue.filePath.replace("\\", "/").endsWith(f.fileName) }
        }

    /** Coverage verdict for an issue's line; null when its class was absent from the coverage data. */
    fun coverageEvidence(classFound: Boolean, isCovered: Boolean): EvidenceOutcome? = when {
        !classFound -> null
        isCovered -> EvidenceOutcome.LIKELY
        else -> EvidenceOutcome.UNREACHED
    }
}
