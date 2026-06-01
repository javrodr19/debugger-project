package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue

/** Outcome of the Tier-2 verify gate. */
sealed interface VerifyDecision {
    /** The candidate fix resolved the target and introduced no regressions. */
    object Accept : VerifyDecision
    /** The candidate fix is rejected; [reason] is user-facing. */
    data class Reject(val reason: String) : VerifyDecision
}

/**
 * Pure Tier-2 decision over single-file re-analysis results, comparing per-[Issue.ruleKey] *counts*
 * rather than fingerprints so the verdict is immune to line shifts (e.g. an inserted import that
 * renumbers every following issue). A candidate is accepted iff:
 *  - no rule's occurrence count increased versus the baseline (no regression), AND
 *  - the target's rule has strictly fewer occurrences than in the baseline (target resolved).
 *
 * Known acceptable miss (consistent with the project's conservative-miss bias): a fix that resolves
 * the target instance but introduces a *different* instance of the *same* rule nets a zero count
 * delta and is accepted. Single-purpose deterministic fixers rarely do this.
 */
class FixVerifier {
    fun decide(
        target: Issue,
        baselineForFile: List<Issue>,
        candidateForFile: List<Issue>,
    ): VerifyDecision {
        val base = baselineForFile.groupingBy { it.ruleKey() }.eachCount()
        val cand = candidateForFile.groupingBy { it.ruleKey() }.eachCount()

        for ((key, count) in cand) {
            val before = base[key] ?: 0
            if (count > before) {
                return VerifyDecision.Reject("Fix introduces new \"$key\" issue(s) (was $before, now $count).")
            }
        }

        val targetKey = target.ruleKey()
        val resolved = (cand[targetKey] ?: 0) < (base[targetKey] ?: 0)
        if (!resolved) {
            return VerifyDecision.Reject("Fix did not resolve the target \"$targetKey\" issue.")
        }
        return VerifyDecision.Accept
    }
}
