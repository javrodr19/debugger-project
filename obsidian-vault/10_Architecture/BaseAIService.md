---
title: "BaseAIService"
type: "architecture"
related_components:
  - "[[AnalysisOrchestrator]]"
  - "[[UIEventRouter]]"
aliases:
  - "AIService"
tags:
  - aegis-debug
  - ai
  - kotlin
---

# BaseAIService

`BaseAIService` is an abstract parent service extracted in V1.5 to eliminate code duplication between Ollama and OpenAI backends.

## Responsibilities
- Owns AI cache lifecycle (`AICache`).
- Handles prompt dispatch and `detectIssues` orchestration. Fix proposals go through `proposeFixPlan(issue, fileContent, feedback): FixPlan?` (`BaseAIService.kt:110`), which decodes the model's response via `FixPlanCodec.decode` into a `FixPlan` for [[FixEngine]] — the V1.5-era `parseFixResponse` name no longer exists in source.
- Does **not** itself enforce a payload bound. The 2000-line-file skip lives in the caller, `AIAnalyzer` (`analysis/analyzers/AIAnalyzer.kt:26`), which filters files out before `detectIssues` is ever invoked; `BaseAIService` has no length check of its own.
- Subclasses (`OllamaService` and `OpenAIService`) implement two low-level hooks, not one: `callModel(systemPrompt, userPrompt, jsonMode)` and `callModelStreaming(systemPrompt, userPrompt, onToken)` (`BaseAIService.kt:44-54`).
