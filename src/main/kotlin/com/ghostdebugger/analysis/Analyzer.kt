package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity

interface Analyzer {
    /** Stable human-friendly name. Used in logs. */
    val name: String

    /** Stable rule identifier. MUST be non-blank, stable across releases, and unique per analyzer. Format: `AEG-<CATEGORY>-<NNN>`. */
    val ruleId: String

    /** Default severity assigned to issues produced by this analyzer unless the analyzer overrides per-issue. */
    val defaultSeverity: IssueSeverity

    /** One-sentence description of what this rule detects. Shown in future UI surfaces and used by tests. */
    val description: String

    fun analyze(context: AnalysisContext): List<Issue>

    /**
     * Returns the expression string to evaluate in the current debug frame, or null if this
     * analyzer's findings cannot be debug-confirmed. Default implementation returns null.
     */
    fun debugProbe(issue: Issue): String? = null
}

/** Marker for analyzers that must run before all others so the engine can
 *  compute the set of broken files and skip downstream analyzers on them. */
interface EarlyAnalyzer : Analyzer

