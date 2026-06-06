package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.Issue
import com.intellij.openapi.project.Project

/**
 * Per-function acceptance for AI extract-method simplifications (`AEG-CPX-001` when the candidate added
 * a function). Unlike [ComplexityVerifier] (file-average), this judges a genuine *decomposition*:
 * extracting a cohesive block from a genuinely-complex function into a new, simpler one — not gaming
 * the per-file average by adding any function. Accepts iff:
 *  - no *other* rule's per-ruleKey count increased vs baseline (the target's own rule ignored), AND
 *  - exactly one new function appeared (by `name/arity`), AND
 *  - some shared-name function got strictly simpler (the source), AND
 *  - that source's original complexity was over [threshold] (a genuine extraction target), AND
 *  - the new function is strictly simpler than the original source (genuine decomposition).
 *
 * Complexity is measured per-function by [PerFunctionComplexity] (parses both sides; no Analysis API).
 * Ambiguous overloads (same name/arity) are declined conservatively. Tier-1 PSI-validity runs first in
 * [FixPlanApplicator] and reverts a non-parsing candidate before this gate is consulted.
 */
class ExtractMethodVerifier(private val project: Project, private val threshold: Int) {
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
            if (count > (base[key] ?: 0)) return VerifyDecision.Reject("Extraction introduces new \"$key\" issue(s).")
        }

        val orig = PerFunctionComplexity.measure(project, originalContent)
        val candR = PerFunctionComplexity.measure(project, candidateContent)
        if (orig.collision || candR.collision) {
            return VerifyDecision.Reject("Ambiguous function names (overloads); cannot verify extraction.")
        }

        val newKeys = candR.byKey.keys - orig.byKey.keys
        if (newKeys.size != 1) {
            return VerifyDecision.Reject("Extraction must add exactly one function (added ${newKeys.size}).")
        }
        val extractedComplexity = candR.byKey.getValue(newKeys.first())

        val source = (orig.byKey.keys intersect candR.byKey.keys)
            .filter { candR.byKey.getValue(it) < orig.byKey.getValue(it) }
            .maxByOrNull { orig.byKey.getValue(it) - candR.byKey.getValue(it) }
            ?: return VerifyDecision.Reject("No source function got simpler.")
        val sourceOriginal = orig.byKey.getValue(source)

        if (sourceOriginal <= threshold) {
            return VerifyDecision.Reject("Source function complexity ($sourceOriginal) is not over the threshold ($threshold).")
        }
        if (extractedComplexity >= sourceOriginal) {
            return VerifyDecision.Reject("Extracted function ($extractedComplexity) is not simpler than the original source ($sourceOriginal).")
        }
        return VerifyDecision.Accept
    }
}
