package com.ghostdebugger.fix.engine

import com.ghostdebugger.graph.GraphBuilder
import com.ghostdebugger.model.Issue

/**
 * Complexity-aware acceptance for simplification fixes (`AEG-CPX-001`). Accepts iff:
 *  - no *other* rule's per-ruleKey count increased vs the baseline (the target's own complexity rule
 *    is ignored — it is graph-level and absent from single-file re-analysis), AND
 *  - the recomputed [estimateComplexity] strictly decreases from original to candidate.
 *
 * The complexity-decrease replaces the count gate's "target resolved", which cannot apply to a
 * graph-level metric. [functionCount] is the flagged file's function count (stable under deterministic
 * branch-elimination; the AI extract-method follow-on will recompute it per candidate).
 */
class ComplexityVerifier(private val functionCount: Int) {
    private val graphBuilder = GraphBuilder()

    fun decide(
        target: Issue,
        baselineForFile: List<Issue>,
        originalContent: String,
        candidateContent: String,
        candidateForFile: List<Issue>,
    ): VerifyDecision {
        val targetKey = target.ruleKey()
        val base = baselineForFile.filterNot { it.ruleKey() == targetKey }.groupingBy { it.ruleKey() }.eachCount()
        val cand = candidateForFile.filterNot { it.ruleKey() == targetKey }.groupingBy { it.ruleKey() }.eachCount()
        for ((key, count) in cand) {
            if (count > (base[key] ?: 0)) {
                return VerifyDecision.Reject("Simplification introduces new \"$key\" issue(s).")
            }
        }
        val before = graphBuilder.estimateComplexity(originalContent, functionCount)
        val after = graphBuilder.estimateComplexity(candidateContent, functionCount)
        if (after >= before) {
            return VerifyDecision.Reject("Complexity did not decrease ($before -> $after).")
        }
        return VerifyDecision.Accept
    }
}
