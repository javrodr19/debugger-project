# Aegis Debug — V1 Development History & Deployment Guide

This document summarizes the development journey of Aegis Debug V1 (April 2026) and provides instructions for maintaining and deploying the plugin.

## 1. Development Summary (Phases 1-8)

Aegis Debug was built in eight distinct phases to transition from a conceptual "GhostDebugger" to a production-ready, privacy-first debugging suite.

### Phase 1-3: Foundations & Core Engine
- Established the **Static-First** architecture.
- Implemented the five core deterministic analyzers: Null Safety, State Initialization, Async Flow, Circular Dependencies, and Complexity.
- Built the **Deterministic Fixer** pipeline: regex-based fixes with a PSI-validity check and automatic rollback on parse errors.

### Phase 4-5: UI & AI Augmentation
- Created the **NeuroMap**: a visual project graph built with React Flow and JCEF.
- Implemented the **Engine Status Pill** for real-time visibility into the analysis state.
- Integrated **Ollama (Local)** and **OpenAI (Cloud)** as optional, privacy-gated augmentation layers.
- Secured API keys using the IntelliJ `PasswordSafe` API.

### Phase 6-8: Hardening, Performance & Stability
- Refined the multi-threaded analysis engine with `Coroutines` and `Semaphores`.
- Implemented cancellation support and progress reporting across the entire pipeline.
- Conducted a full audit to ensure language-scope honesty (TS/JS primary, Kotlin/Java graph-only).
- Optimized performance for large repositories (up to 500 files default cap).
- Finalized marketplace metadata, icons, and legal disclosures (`DATA_HANDLING.md`).

---

## 2. JetBrains Marketplace Deployment Instructions

To ship a new version of Aegis Debug to the marketplace, follow these steps:

### Prerequisites
- Access to the `team@aegisdebug.dev` vendor account on [JetBrains Marketplace](https://plugins.jetbrains.com/).
- A clean, passing build of the plugin.

### Step 1: Prepare the Build
Ensure the version in `build.gradle.kts` and the `CHANGELOG.md` are updated and consistent.
```bash
./gradlew clean test verifyPlugin buildPlugin
```
The deployable artifact will be generated at:
`build/distributions/ghostdebugger-1.0.0.zip`

### Step 2: Manual Verification (Final Smoke Test)
Before uploading, install the generated ZIP into a fresh IntelliJ instance:
1. `Settings` -> `Plugins` -> `⚙️` -> `Install Plugin from Disk...`
2. Select the ZIP from `build/distributions/`.
3. Verify:
   - NeuroMap renders on a real project.
   - Fixes apply and undo correctly.
   - Settings toggle AI providers as expected.

### Step 3: Upload to Marketplace
1. Log in to the [JetBrains Marketplace Vendor Portal](https://plugins.jetbrains.com/author/me).
2. Select **Aegis Debug**.
3. Click **Upload New Version**.
4. Upload the `ghostdebugger-1.0.0.zip` file.
5. Review the release notes (automatically pulled from `plugin.xml`).
6. Submit for review.

### Step 4: Post-Submission
- Review typically takes 1–3 business days.
- Once approved, the plugin will be live for all users within the specified `idea-version` range.

---

## 3. Reference Documents (Archived)
The original detailed specifications for Phases 1 through 8, the V1 Audit, and the Remediation Spec have been summarized into this history file. The **True V1 Spec** (`docs/aegis_debug_true_v1_spec.md`) remains the primary architectural reference for the current state of the codebase.
