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

## 2. V1.1 Amendment: Syntax & Compilation (2026-04-16)

Post-V1 launch, the "Syntax Gap" was identified: a file with invalid syntax could slip through the pattern-based analyzers unflagged. V1.1 closed this gap with:

- **Early-pass Analyzers**: `PsiSyntaxAnalyzer` (AEG-SYNTAX-001) and `CompilationErrorAnalyzer` (AEG-COMPILE-001) now run before all other rules.
- **Two-Phase Engine**: The engine partitions analyzers into `early` and `late` sets.
- **Broken-File Skip**: Any file flagged in the early pass is excluded from pattern-based and AI analysis to prevent false-positive cascades.
- **Daemon Harvesting**: Integration with `DaemonCodeAnalyzerImpl` to surface real-time IDE compilation errors.

---

## 3. V1.1.1 Amendment: Stale-Content Fix (2026-04-17)

A critical correctness bug was identified in V1.1: analyzers in the `late` and AI passes operated on disk bytes (`VirtualFile.contentsToByteArray()`), which remained stale after an editor edit until the user manually saved. This caused "phantom" errors where an issue would remain flagged even after being corrected in the editor. V1.1.1 resolved this with:

- **Document-First Reading**: `FileScanner.parsedFiles()` now prefers the live IDE `Document` text, falling back to disk bytes only when no document exists.
- **PSI-Sync Guarantee**: `PsiDocumentManager.commitAllDocuments()` is called at the start of every analysis run to ensure the PSI tree (used by early analyzers) perfectly mirrors the Document state.
- **Regression Tests**: Added `FileScannerDocumentReadTest` and `AnalysisEnginePostEditRerunTest` to ensure future analyzers inherit this live-text behavior.

---

## 4. V1.1.2 Amendment: Compilation analyzer fix + test-infra unblock (2026-04-18)

V1.1.2 resolved two related defects that had been latent since V1.1.

**`AEG-COMPILE-001` silent no-op (user-facing).** `CompilationErrorAnalyzer.harvestFile` invoked `DaemonCodeAnalyzerImpl.runMainPasses` without satisfying the IntelliJ Platform's thread-local contracts: the caller must install a `DaemonProgressIndicator` via `ProgressManager.runProcess`, the call must be wrapped in a `HighlightingSession`, and all of this must run inside a read action. All three preconditions were missing; the platform threw, a broad `catch (Throwable)` caught the exception, and the analyzer silently returned zero findings on every file. The harvest path is now wired correctly, and compilation findings actually reach the UI.

**IDEA 2025.1+ compatibility.** While running `verifyPlugin` against the full declared `since-build="232.0" until-build="261.*"` range, `HighlightingSessionImpl.runInsideHighlightingSession` was reported as an unresolved method on IU-251.23774.435 — the signature grew a required `CodeInsightContext` parameter in 2025.1 as part of the "multiverse" feature, making the pre-2025.1 5-argument form a `NoSuchMethodError` at runtime. The analyzer now resolves the static method reflectively at class-load, caches the arg count, and supplies `CodeInsightContextKt.anyContext()` on 2025.1+ (a stable public Kotlin helper). This removes the direct compile-time reference to the internal method, so the verifier no longer flags it as an internal-API usage on 2023.2 / 2024.1 either. All four verifier targets — IU 2023.2.6, 2024.1.6, 2024.3.2.2, 2025.1 — now report *Compatible* with zero compatibility problems.

**`BasePlatformTestCase` "indexing hang" (internal).** The V1.1 and V1.1.1 test suites appeared to hang in fixture `setUp()` at `IndexingTestUtil.waitUntilIndexesAreReady`. The root cause was a classpath collision rather than an indexing or sandbox issue: `kotlinx-coroutines-core:1.9.0` — pulled in both directly and transitively (notably via `mockk`) — shadowed the IntelliJ-forked `kotlinx-coroutines-core-jvm-*-intellij.jar`, which exposes platform-internal methods like `runBlockingWithParallelismCompensation`. When the stock jar won resolution, `UnindexedFilesScanner.collectIndexableFilesConcurrently` crashed with a `NoSuchMethodError` inside its scanning coroutine, indexes never signaled "ready", and the daemon polled forever. The fix keeps `kotlinx-coroutines-core` as `compileOnly` and adds a configuration-level `exclude` on both `runtimeClasspath` and `testRuntimeClasspath` — per-dependency excludes are insufficient because `mockk` reintroduces the jar transitively.

The `org.jetbrains.intellij.platform` `2.2.1` → `2.14.0` bump (and Gradle `8.x` → `9.0` wrapper bump, required by the newer plugin) was originally pursued under the wrong hypothesis about the hang and is retained because the 2.14.x `TestFrameworkType` wiring is cleaner and already integrated.

With both fixes in place, five `BasePlatformTestCase` tests (`PsiSyntaxAnalyzerTest`, `CompilationErrorAnalyzerTest`, `AnalysisEngineEarlyPassTest`, `FileScannerDocumentReadTest`, `AnalysisEnginePostEditRerunTest`) execute, and the full test suite reports 117/117 passing.

---

## 5. JetBrains Marketplace Deployment Instructions

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
`build/distributions/ghostdebugger-<version>.zip` (e.g. `ghostdebugger-1.1.2.zip`).

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
4. Upload the `ghostdebugger-<version>.zip` file.
5. Review the release notes (automatically pulled from `plugin.xml`).
6. Submit for review.

### Step 4: Post-Submission
- Review typically takes 1–3 business days.
- Once approved, the plugin will be live for all users within the specified `idea-version` range.

---

## 3. Reference Documents (Archived)
The original detailed specifications for Phases 1 through 8, the V1 Audit, and the Remediation Spec have been summarized into this history file. The **True V1 Spec** (`docs/aegis_debug_true_v1_spec.md`) remains the primary architectural reference for the current state of the codebase.
