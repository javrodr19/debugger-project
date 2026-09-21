---
title: "Custom Rule Authoring (V3.1)"
type: "feature"
related_components:
  - "[[Static_Analyzers]]"
  - "[[Rule_Packs]]"
source_files:
  - "src/main/kotlin/com/ghostdebugger/rules/CustomRuleService.kt"
  - "src/main/kotlin/com/ghostdebugger/rules/RuleMatcher.kt"
  - "src/main/kotlin/com/ghostdebugger/analysis/analyzers/CustomRuleAnalyzer.kt"
  - "src/main/kotlin/com/ghostdebugger/fix/engine/RuleAnchorResolver.kt"
tags:
  - feature
  - custom-rules
  - aegis-debug
---

# Custom Rule Authoring (V3.1)

Custom Rule Authoring allows teams to define project-specific lint and safety rules in declarative YAML files located under `.aegis/rules/*.yml`.

> **Gating status (3.0.0):** ungated and active. `CustomRuleService` has no `AegisCapabilityGate` call anywhere in its load path, and `CustomRuleAnalyzer` (registered in `AnalysisEngine`) runs unconditionally as one of the 12 analyzers. This is the one file in this folder that is not affected by the 3.0.0 capability gate.

## Architecture & Data Flow
1. **Model & Schema (`CustomRule.kt`)**: Declarative rules specifying `id`, `language`, `severity` (`ERROR`, `WARNING`, `WEAK_WARNING`, `INFO` — four values; `RuleSeverity` maps unrecognized YAML strings to `WARNING`), `message`, `match`, and optional `fix`.
2. **Rule Matcher (`RuleMatcher.kt`)**: Bounded predicate matching over PSI elements — but bounded to fewer of the schema's fields than the schema exposes. `RuleMatch` (`CustomRule.kt`) declares eight match fields (`element`, `name-matches`, `text-matches`, `parameter-type`, `receiver-type`, `argument-type`, `inside`, `annotated-with`) plus `unless`, but `RuleMatcher.matches()` only evaluates `element`, `name-matches`, `text-matches`/`contains-text`, `parameter-type`, and the recursive `unless`. A rule that sets `receiver-type`, `argument-type`, `inside`, or `annotated-with` parses without error, but that field is silently ignored — it neither narrows nor widens the match. The fail-closed canary against `KaErrorType` (adhering to the conservative-miss bias) is real but scoped specifically to the `parameter-type` check on `catch-clause` parameters, the only predicate that calls into the Kotlin Analysis API (`getParameterTypeString`, via `withKtAnalysis`); the other predicates are plain PSI text/name matching and never touch the Analysis API.
3. **Analyzer Integration (`CustomRuleAnalyzer.kt`)**: Executes active custom rules during the static pass and tags findings with `IssueSource.CUSTOM`.
4. **Anchor Resolution (`RuleAnchorResolver.kt`)**: Resolves a named anchor (`element-start`, `element-end`, `catch-block-open-brace`) against a PSI element to a text offset — but nothing in production calls it. `CustomRuleAnalyzer.analyze()` builds `Issue` objects from a match and never reads `rule.fix`; no other file under `src/main` reads `CustomRule.fix` either. `RuleAnchorResolver`'s only caller in the whole repository is the test `CustomFixApplyTest.testResolveAnchorOffsetForCatchClause`. In practice, a custom rule that declares a `fix:` block today produces a finding exactly like one that doesn't — the fix is modeled in the schema and the resolver exists, but there is no execution path from a matched rule's `fix` field to `FixEngine`/`FixPlanApplicator`. This is independent of the `FIX_APPLICATION` gate: even with that capability enabled, a custom rule's declared fix still would not apply, because nothing wires it in.

## YAML Schema Example
```yaml
version: 1
rules:
  - id: pce-rethrow-missing
    language: kotlin
    severity: WARNING
    message: "catch (e: Exception) must rethrow ProcessCanceledException first"
    match:
      element: catch-clause
      parameter-type: java.lang.Exception
      unless:
        contains-text: "is ProcessCanceledException) throw"
```
