package com.ghostdebugger.model

import kotlinx.serialization.Serializable

@Serializable
enum class EvidenceSource {
    TEST_FAILURE,
    TEST_COVERAGE,
    TEST_OUTCOME_PASS,
    DEBUG_OBSERVATION
}

@Serializable
enum class EvidenceOutcome {
    CONFIRMED,
    LIKELY,
    DEMOTED,
    UNREACHED
}

@Serializable
data class RuntimeEvidence(
    val fingerprint: String,
    val source: EvidenceSource,
    val outcome: EvidenceOutcome,
    val timestamp: Long,
    val context: String? = null
)
