---
title: "BaseAIService"
type: "architecture"
status: "active"
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
- Handles prompt dispatch, `parseFixResponse`, and `detectIssues` orchestration.
- Enforces payload bounds (e.g., skips files over 2000 lines).
- Subclasses (`OllamaService` and `OpenAIService`) only implement the low-level `callModel(systemPrompt, userPrompt, jsonMode)` execution.
