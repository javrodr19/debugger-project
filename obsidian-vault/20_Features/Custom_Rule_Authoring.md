---
title: "Custom Rule Authoring (V3.1)"
type: "feature"
status: "active"
related_components:
  - "[[CustomRuleService]]"
  - "[[RuleMatcher]]"
  - "[[CustomRuleAnalyzer]]"
  - "[[RuleAnchorResolver]]"
tags:
  - feature
  - custom-rules
  - aegis-debug
---

# Custom Rule Authoring (V3.1)

Custom Rule Authoring allows teams to define project-specific lint and safety rules in declarative YAML files located under `.aegis/rules/*.yml`.

## Architecture & Data Flow
1. **Model & Schema (`CustomRule.kt`)**: Declarative rules specifying `id`, `language`, `severity` (`ERROR`, `WARNING`, `INFO`), `message`, `match`, and optional `fix`.
2. **Rule Matcher (`RuleMatcher.kt`)**: Bounded predicate matching over PSI elements. Includes fail-closed canary protection against `KaErrorType` to adhere to conservative-miss bias.
3. **Analyzer Integration (`CustomRuleAnalyzer.kt`)**: Executes active custom rules during the static pass and tags findings with `IssueSource.CUSTOM`.
4. **Anchor Resolution (`RuleAnchorResolver.kt`)**: Resolves replacement target ranges for declarative fix operations (`ReplaceRange`, `InsertImport`, etc.).

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
