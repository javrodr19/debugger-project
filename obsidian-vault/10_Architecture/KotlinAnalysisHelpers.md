---
title: "KotlinAnalysisHelpers"
type: "architecture"
status: "active"
related_components:
  - "[[Static_Analyzers]]"
  - "[[Claude_Conventions]]"
aliases:
  - "withKtAnalysis"
tags:
  - aegis-debug
  - kotlin
  - k2
---

# KotlinAnalysisHelpers

`KotlinAnalysisHelpers` provides the mandatory single chokepoint for Kotlin K2 Analysis API calls: `withKtAnalysis`.

## Function Signature
```kotlin
fun <T> withKtAnalysis(file: KtFile, action: KaSession.(KtFile) -> T): T?
```

## Why it exists
Calling `analyze(file) { ... }` directly is forbidden because it bypasses centralized exception handling for:
1. `ProcessCanceledException` (PCE) — MUST be rethrown immediately.
2. `KaAnalysisNonPublicApiException` — thrown when experimental APIs are accessed without opt-in.
3. `KaInvalidLifetimeOwnerAccessException` — thrown when a `KaSession`-bound value escapes the block.

`withKtAnalysis` centralizes error handling, guarantees PCE rethrow, and returns `null` on internal Analysis API errors.
