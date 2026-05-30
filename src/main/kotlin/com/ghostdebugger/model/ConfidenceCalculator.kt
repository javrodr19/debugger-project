package com.ghostdebugger.model

enum class Confidence {
    CONFIRMED, LIKELY, UNCONFIRMED, UNREACHED, DEMOTED;

    fun rank(): Int = when (this) {
        CONFIRMED -> 0
        LIKELY -> 1
        UNCONFIRMED -> 2
        DEMOTED -> 3
        UNREACHED -> 4
    }
}

object ConfidenceCalculator {
    fun calculate(evidence: List<RuntimeEvidence>): Confidence = when {
        evidence.isEmpty() -> Confidence.UNCONFIRMED
        evidence.any { it.outcome == EvidenceOutcome.CONFIRMED } -> Confidence.CONFIRMED
        evidence.any { it.outcome == EvidenceOutcome.DEMOTED } &&
            evidence.none { it.outcome == EvidenceOutcome.CONFIRMED } -> Confidence.DEMOTED
        evidence.any { it.outcome == EvidenceOutcome.LIKELY } -> Confidence.LIKELY
        evidence.all { it.outcome == EvidenceOutcome.UNREACHED } -> Confidence.UNREACHED
        else -> Confidence.UNCONFIRMED
    }
}
