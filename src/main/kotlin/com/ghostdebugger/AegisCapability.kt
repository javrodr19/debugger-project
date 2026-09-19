package com.ghostdebugger

/**
 * A user-visible capability that Aegis Debug 3.0.0 does not ship enabled.
 *
 * Each constant owns the two strings the gate needs: [label] names the surface the user just
 * reached, and [why] is the one sentence explaining what state the work is actually in. Both the
 * dialog ([com.ghostdebugger.AegisCapabilityGate.messageFor]) and `docs/IMPLEMENTATION_STATUS.md`
 * read these two strings verbatim, so the wording cannot drift between them. The changelog
 * summarizes the same facts in prose rather than quoting [why] directly.
 */
enum class AegisCapability(val label: String, val why: String) {
    FIX_APPLICATION(
        label = "Apply Fix",
        why = "Aegis Debug 3.0 ships as a read-only analysis tool. The fix engine is implemented " +
            "and tested, but fix application is not enabled in this release.",
    ),
    AI_EXPLANATION(
        label = "AI Explanation",
        why = "Issue and system explanations require a local Ollama server or an OpenAI key. The " +
            "provider transport is untested end-to-end, so the feature is disabled rather than " +
            "shipped unreliable.",
    ),
    AI_ANALYSIS(
        label = "AI Analysis Pass",
        why = "The AI analysis pass is disabled in this release. All findings you see come from " +
            "the deterministic static analyzers.",
    ),
    JVM_DEPENDENCY_GRAPH(
        label = "Dependency Graph for Kotlin and Java",
        why = "Internal graph edges are resolved only for relative imports, so Kotlin and Java " +
            "projects produce an edgeless graph. Impact analysis and cycle detection are " +
            "therefore available for TypeScript and JavaScript only.",
    ),
    RULE_PACKS(
        label = "Rule Packs",
        why = "The three bundled packs cannot currently match real code, so enabling them would " +
            "add controls that never produce a finding.",
    ),
    EXTERNAL_ANALYZERS(
        label = "External Analyzer SDK",
        why = "The loader exists, but there is no published SDK artifact and no documented " +
            "contract for third-party analyzers yet.",
    ),
    PROBLEMS_VIEW_EMIT(
        label = "Problems Tool Window Integration",
        why = "Publishing to the native Problems view violates the platform's threading contract " +
            "on 2024.3. Findings are available in the Aegis Debug tool window and in the editor.",
    ),
    DEBUGGER_CROSS_CHECK(
        label = "Debugger Cross-Check",
        why = "Runtime confirmation from a paused debug session is not reachable on IntelliJ IDEA " +
            "Community. Test-suite cross-check remains active.",
    ),
}
