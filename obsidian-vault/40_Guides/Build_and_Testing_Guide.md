---
title: "Build and Testing Guide"
type: "guide"
status: "active"
related_components:
  - "[[Claude_Conventions]]"
  - "[[Agent_Guardrails]]"
aliases:
  - "Build Guide"
  - "Testing Guide"
tags:
  - aegis-debug
  - gradle
  - testing
---

# Build and Testing Guide

This guide covers how to build, test, and verify the Aegis Debug plugin.

## 1. Prerequisites (JetBrains Runtime `JAVA_HOME`)

Gradle's `instrumentTestCode` task requires `JAVA_HOME` to point to a **JetBrains Runtime (JBR)**, not a generic JDK (like OpenJDK or Microsoft JDK). Standard JDKs lack the `Packages` directory required by the IntelliJ Platform Gradle Plugin (IPGP) bytecode instrumenter, causing build failures.

### Locating JBR & Setting Environment Variables
```bash
# Find bundled JBR
find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1

# Set JAVA_HOME and PATH
export JAVA_HOME=/path/to/jbr
export PATH=$JAVA_HOME/bin:$PATH
```

## 2. Common Gradle Commands

- `./gradlew compileKotlin` — Compiles Kotlin source code.
- `./gradlew test` — Runs the complete test suite.
- `./gradlew test --tests "com.ghostdebugger.*"` — Runs specific test classes.
- `./gradlew verifyPlugin` — Runs the IntelliJ Plugin Verifier against target IDE versions (e.g., 2024.3, 2025.1).
- `./gradlew buildPlugin` — Packages the plugin zip file to `build/distributions/ghostdebugger-<version>.zip`.

## 3. Writing Tests

### Test Class Hierarchies
- **`BasePlatformTestCase`**: Standard IntelliJ light platform test case for general IDE integration.
- **`AegisKotlinAnalysisTestCase`**: Required base class for any test exercising Kotlin Analysis API analyzers.
  - Pulls in `kotlin-stdlib.jar` via `AegisKotlinStdlibProjectDescriptor`.
  - Runs **off-EDT** (`runInDispatchThread() = false`) because Analysis API throws `ProhibitedAnalysisException` on the EDT.

### Determinism Rules
- **No `Thread.sleep`**: Use platform test helpers like `runInEdtAndWait` or `runWriteCommandAction`.
- **Analyzer Tests**: Every new analyzer must include:
  1. At least one positive test case (finding reported).
  2. At least one negative test case (no finding reported).
  3. At least one ambiguous-type case (conservative-miss test: must NOT flag).
