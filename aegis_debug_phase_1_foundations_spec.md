# Phase 1 Implementation Spec: Aegis Debug Foundations (Rebrand, Settings Refactor, Analyzer Contracts, Test Baseline)

**Version:** 1.0 (binding)
**Status:** Ready for immediate implementation
**Source of Truth:** `aegis_debug_true_v1_spec.md`
**Target Phase from Source Spec:** Section 20 — Phase 1 (Foundations), items 1–4
**Prior Working Name Of Repo:** `ghostdebugger` (Gradle root) / `com.ghostdebugger` (Kotlin package) — retained internally; see §5

---

## 1. Objective

Deliver the non-negotiable foundations for Aegis Debug V1 so that Phases 2–6 can proceed without rework:

1. **Rebrand** every user-facing surface and build artifact from **GhostDebugger → Aegis Debug**, while preserving the existing plugin ID `com.ghostdebugger`, Kotlin package `com.ghostdebugger`, tool-window internal id `GhostDebugger`, persisted-state file name `ghostdebugger.xml`, and PasswordSafe service name `GhostDebugger` to avoid data loss on upgrade.
2. **Refactor** `GhostDebuggerSettings` to match source spec §5 verbatim: introduce `AIProvider { NONE, OPENAI, OLLAMA }` defaulting to `NONE`, add `ollamaEndpoint`, `ollamaModel`, `aiTimeoutMs`, `allowCloudUpload`, `analyzeOnlyChangedFiles`, `maxAiFiles`, add `snapshot()` returning an immutable copy, add `update { }` mutator, and add `validate()` on load.
3. **Formalize analyzer contracts.** Extend the `Analyzer` interface with rule metadata (`ruleId`, `defaultSeverity`, `description`) required by source spec §6 ("no analyzer ships in V1 unless it has a documented rule definition, positive test case, negative test case, severity mapping").
4. **Stand up the test baseline.** Introduce `src/test/kotlin/...` with JUnit 5 + MockK, provide one positive fixture and one negative fixture per required V1 analyzer (`NullSafetyAnalyzer`, `StateInitAnalyzer`, `AsyncFlowAnalyzer`, `CircularDependencyAnalyzer`, `ComplexityAnalyzer`), and add settings-validation / provider-fallback unit tests.

Phase 1 ships no behavioral analysis changes, no PSI fixes, no bridge changes, and no new analyzers. It only lays the load-bearing surface for later phases.

---

## 2. Scope

### 2.1 In Scope

| Area | Work |
|---|---|
| Branding (build) | `build.gradle.kts` plugin `name`, vendor, description. |
| Branding (plugin descriptor) | `src/main/resources/META-INF/plugin.xml` `<name>`, `<description>`, vendor, settings `displayName`, action `text`, tool-window icon reference. |
| Branding (docs) | `README.md` already titled "Aegis Debug"; sweep for stray "GhostDebugger" references in text. |
| Branding (UI code) | `GhostDebuggerConfigurable.createComponent()` UI labels; `GhostDebuggerToolWindowFactory.createToolWindowContent()` sets tool-window `title = "Aegis Debug"`; `NeuroMapPanel` error labels. |
| Branding (webview) | `webview/index.html` `<title>`; `webview/src/components/layout/StatusBar.tsx` brand label `"GhostDebugger"` → `"Aegis Debug"`; `webview/src/App.tsx` `EmptyState` heading `"GhostDebugger"` → `"Aegis Debug"`; `webview/package.json` `"description"` string. |
| Branding (assets) | Add `src/main/resources/icons/aegis.svg` (13×13 viewport, cream `#FDFBF7` shield-with-node mark on transparent background). Keep `ghost.svg` file in place (unreferenced) for safety; remove only from `plugin.xml`. |
| Settings | Replace `GhostDebuggerSettings.kt` body with source-spec §5 implementation (adapted to retain `@Storage("ghostdebugger.xml")` and keep legacy fields that still appear in consumers: `openAiModel`, `maxFilesToAnalyze`, `autoAnalyzeOnOpen`, `showInfoIssues`, `cacheEnabled`, `cacheTtlSeconds`). |
| Settings UI | Extend `GhostDebuggerConfigurable` with an `AIProvider` `JComboBox` (`NONE` / `OPENAI` / `OLLAMA`), an `ollamaEndpoint` `JTextField`, an `ollamaModel` `JTextField`, and an `allowCloudUpload` `JCheckBox`. Persist via `GhostDebuggerSettings.update { }`. |
| Credentials | `ApiKeyManager` unchanged in behavior; rename the **display** attribute title only (service name literal `"GhostDebugger"` is **retained** as a binding decision — see §5). |
| Analyzer contract | Extend `Analyzer` interface with `ruleId`, `defaultSeverity`, `description`. Update all five existing analyzer classes to implement the new members. No change to matching logic. |
| Test harness | New `src/test/kotlin/com/ghostdebugger/...` mirroring `src/main/kotlin/`. JUnit 5 via `useJUnitPlatform()` (already in `build.gradle.kts`). MockK for `Project` doubles. |
| Tests | 1 positive + 1 negative test per V1 analyzer (10 tests total), plus `GhostDebuggerSettingsTest`, `AIProviderDefaultTest`, `AnalyzerContractTest`. |
| Gradle | No dependency changes. Confirm existing test dependencies (`kotlin-test-junit5`, `junit-jupiter`, `mockk`, `kotlinx-coroutines-test`) are usable. |

### 2.2 Out of Scope (strict)

- Reorder of `AnalysisEngine` execution to static-first (Phase 2).
- Implementation of `maxFilesToAnalyze` enforcement inside `AnalysisEngine` (Phase 2).
- `IssueSource`, `EngineProvider`, `confidence`, `sources`, `providers`, `ruleId` fields on `Issue` model (Phase 2).
- Fingerprint-based issue merge (Phase 2).
- PSI fixers, diff preview, fix-apply pipeline (Phase 3).
- `EngineStatus`, engine status payload, status bar engine/provider surface, IBM Plex font bundling, Dark Navy + Cream UI refresh (Phase 4).
- `OllamaService`, Ollama/OpenAI integration changes, AI missed-issue pass (Phase 5).
- Performance tuning, large-repo prioritization strategy (Phase 6).

---

## 3. Non-Goals

The following MUST NOT be touched by Phase 1:

1. Do **not** rename Kotlin package `com.ghostdebugger` → `com.aegisdebug`. Deferred; would break persisted plugin state and credential store key.
2. Do **not** change plugin id `com.ghostdebugger` in `plugin.xml` or `build.gradle.kts`.
3. Do **not** change `@Storage("ghostdebugger.xml")` file name.
4. Do **not** change `ApiKeyManager.SERVICE_NAME = "GhostDebugger"` or `KEY_NAME = "OPENAI_API_KEY"` literals.
5. Do **not** change tool-window internal id `"GhostDebugger"` (consumed by `AnalyzeProjectAction`, `ExplainSystemAction`).
6. Do **not** change notification group id `"GhostDebugger"`.
7. Do **not** modify `AnalysisEngine.analyze()` control flow.
8. Do **not** introduce new analyzers beyond the existing five.
9. Do **not** add new sealed-class members to `UIEvent`, new bridge methods, or new webview message types.
10. Do **not** introduce `kotlinx-serialization` on `ProjectMetrics`/`AnalysisResult` (not currently serialized) — unrelated to Phase 1.
11. Do **not** rename `GhostDebuggerService`, `GhostDebuggerAnnotator`, or `GhostDebuggerToolWindowFactory` class names.
12. Do **not** remove files (`ghost.svg` stays on disk; just no longer referenced).

---

## 4. Implementation Decisions

Derived from the repository state and binding for Phase 1:

| Decision | Value | Source |
|---|---|---|
| Primary language | Kotlin 2.0.21 | `build.gradle.kts:3` |
| JVM toolchain | Java 21 | `build.gradle.kts:46` |
| IntelliJ platform | `intellijIdeaCommunity("2024.3.2")` / platformType `IC` | `build.gradle.kts:22`, `gradle.properties:2` |
| Plugin SDK | `org.jetbrains.intellij.platform` 2.2.1 | `build.gradle.kts:5` |
| Since/Until build | `243` / `251.*` (unchanged) | `build.gradle.kts:67-69` |
| Test framework | JUnit 5 via `useJUnitPlatform()` | `build.gradle.kts:81` |
| Mocking | MockK 1.13.15 | `build.gradle.kts:41` |
| Coroutines test | `kotlinx-coroutines-test:1.9.0` | `build.gradle.kts:42` |
| Serialization | `kotlinx-serialization-json:1.7.3` | `build.gradle.kts:32` |
| HTTP client | OkHttp 4.12.0 | `build.gradle.kts:35` |
| Kotlin package root | `com.ghostdebugger` (retained) | existing |
| Settings storage file | `ghostdebugger.xml` (retained) | `GhostDebuggerSettings.kt:9` |
| Secure secret store | IntelliJ `PasswordSafe` via `CredentialAttributes` | `ApiKeyManager.kt:5-26` |
| Tool-window id | `GhostDebugger` (retained) | `plugin.xml:26` |
| Plugin id | `com.ghostdebugger` (retained) | `plugin.xml:2`, `build.gradle.kts:51` |
| Webview build output | `src/main/resources/web/` (unchanged) | `webview/vite.config.ts:17` |
| Test source set | `src/test/kotlin/` (Gradle convention; directory does not exist yet, create it) | Gradle default |
| Test package mirror | `com.ghostdebugger.*` under `src/test/kotlin/` | convention |
| Public vendor email | `team@ghostdebugger.dev` (retained for email continuity) | `plugin.xml:5` |
| Display vendor name | `Aegis Debug` (changed) | source spec §13 |
| Accent branding colors | cream `#FDFBF7`, navy `#0A1128` | source spec §12 |

---

## 5. Implementation Decisions Made From Ambiguity

The source spec leaves several points unresolved. The following decisions are **binding** for Phase 1. Any deviation requires a new spec revision.

1. **Kotlin package name and plugin id stay `com.ghostdebugger`.** The source spec §13 mandates display-name rebrand but does not mandate package/id rename. Renaming would invalidate `ghostdebugger.xml` persisted state, break the PasswordSafe credential entry (stored under `generateServiceName("GhostDebugger", "OPENAI_API_KEY")`), and break existing installations during beta upgrade. Rebrand is strictly user-visible.

2. **PasswordSafe service name literal remains `"GhostDebugger"`.** Changing the literal would orphan users' existing API keys. Binding: retain the string. User-facing docs describe the secret as "Aegis Debug API key." Rename deferred to a post-V1 migration task.

3. **Notification group id remains `"GhostDebugger"`.** Same rationale as the credential store: id is a stable programmatic anchor; rebrand is display-only.

4. **Tool-window internal id remains `"GhostDebugger"`.** `AnalyzeProjectAction.kt:13` and `ExplainSystemAction.kt:12` look up the tool window by that id. Change the **displayed title** programmatically in the factory using `toolWindow.setTitle("Aegis Debug")`; leave the id.

5. **Provider dropdown defaults to `NONE`.** Source spec §14 specifies "default provider = NONE; default behavior = static-only until user enables AI." Binding.

6. **Ollama endpoint and model go in `ghostdebugger.xml`, not PasswordSafe.** They are not secrets (HTTP URL and model name). Source spec §5 shows them inside `@State`. Binding.

7. **Ollama has no API key in V1.** Typical Ollama deployments are unauthenticated on localhost. If the user points to an authenticated reverse proxy, that's deferred to post-V1. `AIProvider.OLLAMA` selection must therefore **not** prompt for a key in Phase 1.

8. **`ApiKeyManager` remains single-provider (OpenAI) for Phase 1.** The source spec says "API keys must not be stored in the plugin XML settings file" — `ApiKeyManager` already satisfies this for OpenAI. No new provider keys in Phase 1.

9. **`Issue` model is not modified in Phase 1.** Source spec §7 introduces new fields (`sources`, `providers`, `confidence`, `ruleId`) — those are Phase 2 deliverables (per source spec §20 Phase 2 item 7: "Add issue fingerprint merge logic with provenance preservation"). The Phase 1 Analyzer contract exposes `ruleId` on the **analyzer**, not yet on the issue instance. Phase 2 will plumb it through.

10. **Plugin display name set in `build.gradle.kts` is authoritative** over the `<name>` element in `plugin.xml` under the IntelliJ Platform Gradle plugin 2.x. Both are still updated for consistency and so the Marketplace listing and descriptor match.

11. **Tool-window displayed title override** happens in `GhostDebuggerToolWindowFactory.createToolWindowContent` via `toolWindow.setTitle("Aegis Debug")` after the content is added. Binding because the `<toolWindow>` XML element's `id` attribute doubles as the default title text, and we are retaining the id.

12. **`aegis.svg` icon dimensions: 13×13 viewBox.** IntelliJ tool-window icons are standardized at 13×13 (see `ghost.svg` in repo). Binding.

13. **Logo geometry for `aegis.svg`:** cream `#FDFBF7` stroke, 1.5px, on transparent background; a pentagonal shield outline enclosing two circular nodes connected by a single line. No fills; strokes only. Follows source spec §13 ("abstract geometric mark, shield + node/graph hybrid").

14. **Test source layout** follows Gradle convention: `src/test/kotlin/com/ghostdebugger/<subpackage>/<ClassName>Test.kt`. Binding because `build.gradle.kts` defines `useJUnitPlatform()` without overriding `sourceSets`.

15. **Test fixtures for analyzers** are inline multi-line Kotlin `String` literals inside the test class, not external files. Keeps fixtures co-located with expectations and avoids file I/O. Binding.

16. **Test doubles for IntelliJ `Project`** use MockK `mockk<Project>(relaxed = true)`. Analyzers under test do not call `Project` methods in the five current implementations (they iterate `AnalysisContext.parsedFiles` or `context.graph`), so a relaxed mock is sufficient. Binding.

17. **`AnalysisContext`** already takes `project: Project`, `graph: InMemoryGraph`, `parsedFiles: List<ParsedFile>` — tests construct it directly with an empty `InMemoryGraph()` and a list of `ParsedFile` built from fixture strings. Binding.

18. **`ParsedFile.virtualFile`** is a `com.intellij.openapi.vfs.VirtualFile` (non-null in the data class). Tests use `mockk<VirtualFile>(relaxed = true)`. Binding.

19. **No new Gradle dependencies** in Phase 1. The existing set covers everything. Binding.

20. **`AnalyzerContractTest`** asserts that every analyzer registered in `AnalysisEngine.analyzers` declares a non-blank `ruleId`, a non-blank `description`, and a `defaultSeverity` in `{ERROR, WARNING, INFO}`. Binding.

21. **README**: already titled "Aegis Debug" (line 1). Leave content unchanged except verify no stray "GhostDebugger" appears outside the explicit branding-note paragraph (lines 73–75 of `README.md`). Binding: scan and fix non-branding-note occurrences only.

22. **`webview/package.json` name field `@ghostdebugger/webview`** is retained (npm scope; changing would require republish if ever public). Only the `description` field is updated. Binding.

23. **`webview` CSS color tokens are NOT updated to the Aegis palette in Phase 1.** The Dark Navy + Cream refresh is source spec §12 / Phase 4 work. Phase 1 only touches user-visible brand *text strings* and the `<title>` element. Binding.

24. **IBM Plex fonts are NOT bundled in Phase 1.** Phase 4. Binding.

25. **`GhostDebuggerSettings.getInstance()` call sites remain unchanged.** The method signature stays `fun getInstance(): GhostDebuggerSettings`. New accessors (`snapshot()`, `update{}`) are **additive**. Binding.

26. **Legacy property accessors retained.** `GhostDebuggerSettings.openAiModel`, `maxFilesToAnalyze`, `autoAnalyzeOnOpen` currently expose `var` getters/setters consumed by `GhostDebuggerConfigurable.kt:57,67,94,104,113`. The refactor keeps these accessors as thin delegates over the `State`. Binding: no call-site changes in unrelated files.

---

## 6. Files to Create or Modify

### 6.1 Create

| Path | Purpose |
|---|---|
| `src/main/resources/icons/aegis.svg` | New 13×13 cream/navy shield+node icon (§5 decision 13). |
| `src/test/kotlin/com/ghostdebugger/settings/GhostDebuggerSettingsTest.kt` | Unit tests for State defaults, `validate()`, `snapshot()`, `update{}`, `AIProvider` enum. |
| `src/test/kotlin/com/ghostdebugger/analysis/AnalyzerContractTest.kt` | Asserts each registered analyzer exposes non-blank `ruleId`, `description`, and a valid `defaultSeverity`. |
| `src/test/kotlin/com/ghostdebugger/analysis/analyzers/NullSafetyAnalyzerTest.kt` | 1 positive (flags issue) + 1 negative (does not flag) case. |
| `src/test/kotlin/com/ghostdebugger/analysis/analyzers/StateInitAnalyzerTest.kt` | 1 positive + 1 negative. |
| `src/test/kotlin/com/ghostdebugger/analysis/analyzers/AsyncFlowAnalyzerTest.kt` | 1 positive + 1 negative. |
| `src/test/kotlin/com/ghostdebugger/analysis/analyzers/CircularDependencyAnalyzerTest.kt` | 1 positive + 1 negative. |
| `src/test/kotlin/com/ghostdebugger/analysis/analyzers/ComplexityAnalyzerTest.kt` | 1 positive + 1 negative. |
| `src/test/kotlin/com/ghostdebugger/testutil/FixtureFactory.kt` | Helper: `parsedFile(path: String, ext: String, content: String)`, `context(files: List<ParsedFile>, graph: InMemoryGraph = InMemoryGraph())`. |

### 6.2 Modify

| Path | Target lines | Change summary |
|---|---|---|
| `build.gradle.kts` | 52 | `name = "Aegis Debug"` (was `"GhostDebugger"`). |
| `build.gradle.kts` | 54-64 | Replace description block text with Aegis positioning. See §7.6. |
| `build.gradle.kts` | 72 | `vendor { name = "Aegis Debug" }` (was `"GhostDebugger Team"`). |
| `src/main/resources/META-INF/plugin.xml` | 4 | `<name>Aegis Debug</name>`. |
| `src/main/resources/META-INF/plugin.xml` | 5 | `<vendor email="team@ghostdebugger.dev">Aegis Debug</vendor>`. |
| `src/main/resources/META-INF/plugin.xml` | 7-20 | Replace `<description>` CDATA with Aegis copy. See §7.7. |
| `src/main/resources/META-INF/plugin.xml` | 30 | `icon="/icons/aegis.svg"`. |
| `src/main/resources/META-INF/plugin.xml` | 44 | `displayName="Aegis Debug"`. |
| `src/main/resources/META-INF/plugin.xml` | 57 | `text="Aegis Debug"` for the `GhostDebugger.Menu` group. |
| `src/main/resources/META-INF/plugin.xml` | 58 | group `icon="/icons/aegis.svg"`. |
| `src/main/resources/META-INF/plugin.xml` | 65 | Action `description="Analyze entire project with Aegis Debug"`. |
| `src/main/resources/META-INF/plugin.xml` | 66 | Action `icon="/icons/aegis.svg"` (was `analyze.svg`)? **NO**: keep `/icons/analyze.svg` for the Analyze action; only the menu group and tool-window icons become `aegis.svg`. |
| `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt` | full file | Replace with §7.1 verbatim. |
| `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt` | 19 | `getDisplayName() = "Aegis Debug"`. |
| `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt` | 43-48 | Dialog titles `"GhostDebugger"` → `"Aegis Debug"` (both occurrences). |
| `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt` | 77 | HTML label `"👻 GhostDebugger Configuration"` → `"Aegis Debug Configuration"` (drop emoji). |
| `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt` | full panel | Add provider row, Ollama endpoint row, Ollama model row, allowCloudUpload row. Wire `apply()` / `reset()` / `isModified()` to the new fields via `GhostDebuggerSettings.update { }`. See §7.2. |
| `src/main/kotlin/com/ghostdebugger/toolwindow/GhostDebuggerToolWindowFactory.kt` | 16 | After `contentManager.addContent(content)`, call `toolWindow.setTitle("Aegis Debug")`. |
| `src/main/kotlin/com/ghostdebugger/toolwindow/NeuroMapPanel.kt` | 76-79 | Error label "Failed to load NeuroMap" unchanged (no brand reference). **No change**. |
| `src/main/kotlin/com/ghostdebugger/analysis/Analyzer.kt` | full file | Replace with extended interface from §7.3. |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/NullSafetyAnalyzer.kt` | 7-8 | Implement `ruleId`, `defaultSeverity`, `description`. See §7.4. |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/StateInitAnalyzer.kt` | 7-8 | Same. |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/AsyncFlowAnalyzer.kt` | 7-8 | Same. |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/CircularDependencyAnalyzer.kt` | 7-8 | Same. |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/ComplexityAnalyzer.kt` | 7-8 | Same. |
| `src/main/kotlin/com/ghostdebugger/analysis/analyzers/AIAnalyzer.kt` | unchanged | `AIAnalyzer` does **not** implement `Analyzer` (it's a standalone class). Leave untouched in Phase 1. |
| `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` | 443,558 | Replace user-facing `"GhostDebugger"` strings with `"Aegis Debug"` in two error/help messages. See §7.5. |
| `src/main/kotlin/com/ghostdebugger/actions/ConfigureApiKeyAction.kt` | 10 | `text="Configure OpenAI API Key"` stays; action class unchanged. **No change**. |
| `README.md` | 45 | "Aegis Debug branding rollout replacing the previous GhostDebugger identity" — keep. |
| `README.md` | 73-75 | Branding-note paragraph — keep. All other `"GhostDebugger"` mentions outside this paragraph — none found. **No change**. |
| `webview/index.html` | 6 | `<title>Aegis Debug NeuroMap</title>`. |
| `webview/src/App.tsx` | 369 | `GhostDebugger` → `Aegis Debug` in `<EmptyState>`. |
| `webview/src/components/layout/StatusBar.tsx` | 39-41 | Brand text `GhostDebugger` → `Aegis Debug`; remove emoji `👻` (line 38). |
| `webview/package.json` | 5 | `"description": "Aegis Debug NeuroMap — React Flow visualization embedded in JCEF"`. |

### 6.3 Leave untouched

- Every file under `src/main/kotlin/com/ghostdebugger/ai/**` (Phase 5 surface; rebrand only if user-facing strings exist — verified none do).
- Every file under `src/main/kotlin/com/ghostdebugger/graph/**`, `parser/**`, `model/**`, `bridge/**`, `annotator/**`.
- `src/main/kotlin/com/ghostdebugger/ReportGenerator.kt` (Phase 6 polish).
- `src/main/resources/icons/analyze.svg` and `ghost.svg` (files stay on disk).
- Every webview file **except** those listed in §6.2.

---

## 7. Data Contracts / Interfaces / Schemas

### 7.1 `GhostDebuggerSettings.kt` — complete target contents

Replace the entire file with:

```kotlin
package com.ghostdebugger.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class AIProvider {
    NONE,
    OPENAI,
    OLLAMA
}

@Service
@State(name = "GhostDebuggerSettings", storages = [Storage("ghostdebugger.xml")])
class GhostDebuggerSettings : PersistentStateComponent<GhostDebuggerSettings.State> {

    data class State(
        var aiProvider: AIProvider = AIProvider.NONE,
        var openAiModel: String = "gpt-4o",
        var ollamaEndpoint: String = "http://localhost:11434",
        var ollamaModel: String = "llama3",
        var maxFilesToAnalyze: Int = 500,
        var maxAiFiles: Int = 100,
        var autoAnalyzeOnOpen: Boolean = false,
        var showInfoIssues: Boolean = true,
        var cacheEnabled: Boolean = true,
        var cacheTtlSeconds: Long = 3600,
        var aiTimeoutMs: Long = 30_000,
        var allowCloudUpload: Boolean = false,
        var analyzeOnlyChangedFiles: Boolean = false
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state.validate()
    }

    fun snapshot(): State = myState.copy()

    fun update(transform: State.() -> Unit) {
        myState = myState.copy().apply(transform).validate()
    }

    private fun State.validate(): State {
        if (maxFilesToAnalyze <= 0) maxFilesToAnalyze = 500
        if (maxAiFiles < 0) maxAiFiles = 0
        if (cacheTtlSeconds < 0) cacheTtlSeconds = 0
        if (aiTimeoutMs <= 0) aiTimeoutMs = 30_000
        if (ollamaEndpoint.isBlank()) ollamaEndpoint = "http://localhost:11434"
        if (ollamaModel.isBlank()) ollamaModel = "llama3"
        if (openAiModel.isBlank()) openAiModel = "gpt-4o"
        return this
    }

    // Legacy accessors retained for existing call sites (Phase 1 does not rewrite consumers).
    var openAiModel: String
        get() = myState.openAiModel
        set(value) { update { openAiModel = value } }

    var maxFilesToAnalyze: Int
        get() = myState.maxFilesToAnalyze
        set(value) { update { maxFilesToAnalyze = value } }

    var autoAnalyzeOnOpen: Boolean
        get() = myState.autoAnalyzeOnOpen
        set(value) { update { autoAnalyzeOnOpen = value } }

    companion object {
        fun getInstance(): GhostDebuggerSettings =
            ApplicationManager.getApplication().getService(GhostDebuggerSettings::class.java)
    }
}
```

**Binding invariants:**
- `AIProvider` lives in the same file; no separate module.
- `snapshot()` returns a deep-enough copy for safe reads during analysis (all fields are primitives or enum — `.copy()` is sufficient).
- `validate()` is applied on both `loadState()` and `update{}`; never on `snapshot()` (snapshot is already valid).
- Legacy property accessors delegate through `update {}` so validation always runs on writes.
- No `fallback` singleton creation in `getInstance()` — rely on `ApplicationManager`. Any environment where the service is unavailable is a test environment that must inject its own instance.

### 7.2 `GhostDebuggerConfigurable.kt` — delta

Target settings panel (Swing). Replace the form construction block starting at line 21 with the following (keep imports; add `JCheckBox`, `JTextField`; remove the `👻` emoji label).

```kotlin
override fun createComponent(): JComponent {
    val settings = GhostDebuggerSettings.getInstance().snapshot()
    val mainPanel = JPanel(BorderLayout(10, 10))
    mainPanel.border = EmptyBorder(10, 10, 10, 10)

    val formPanel = JPanel()
    formPanel.layout = BoxLayout(formPanel, BoxLayout.Y_AXIS)

    // Provider dropdown
    val providerCombo = JComboBox(AIProvider.values().map { it.name }.toTypedArray()).apply {
        selectedItem = settings.aiProvider.name
    }
    this.providerCombo = providerCombo
    val providerPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(JLabel("AI Provider:"))
        add(providerCombo)
    }

    // API Key section
    val field = JPasswordField(ApiKeyManager.getApiKey() ?: "", 40).apply {
        preferredSize = Dimension(400, 28)
    }
    apiKeyField = field
    val apiKeyPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(JLabel("OpenAI API Key:"))
        add(field)
        val testButton = JButton("Test Connection").apply {
            addActionListener {
                val key = String(field.password)
                if (key.isBlank()) {
                    Messages.showWarningDialog("Please enter an API key first.", "Aegis Debug")
                } else {
                    Messages.showInfoMessage(
                        "API key saved. Connection will be tested on first analysis.",
                        "Aegis Debug"
                    )
                }
            }
        }
        add(testButton)
    }

    // Model
    val combo = JComboBox(arrayOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo")).apply {
        selectedItem = settings.openAiModel
    }
    modelCombo = combo
    val modelPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(JLabel("OpenAI Model:"))
        add(combo)
    }

    // Ollama endpoint
    val ollamaEndpointField = JTextField(settings.ollamaEndpoint, 30)
    this.ollamaEndpointField = ollamaEndpointField
    val ollamaEndpointPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(JLabel("Ollama Endpoint:"))
        add(ollamaEndpointField)
    }

    // Ollama model
    val ollamaModelField = JTextField(settings.ollamaModel, 20)
    this.ollamaModelField = ollamaModelField
    val ollamaModelPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(JLabel("Ollama Model:"))
        add(ollamaModelField)
    }

    // Max files
    val spinner = JSpinner(SpinnerNumberModel(settings.maxFilesToAnalyze, 10, 2000, 50)).apply {
        preferredSize = Dimension(80, 28)
    }
    maxFilesSpinner = spinner
    val maxFilesPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(JLabel("Max files to analyze:"))
        add(spinner)
    }

    // Allow cloud upload
    val allowCloudBox = JCheckBox("Allow cloud upload (OpenAI)", settings.allowCloudUpload)
    this.allowCloudUploadBox = allowCloudBox

    formPanel.add(Box.createVerticalStrut(10))
    formPanel.add(JLabel("<html><b>Aegis Debug Configuration</b></html>"))
    formPanel.add(Box.createVerticalStrut(15))
    formPanel.add(JLabel("<html>Static-first analysis. AI is optional and off by default.</html>"))
    formPanel.add(Box.createVerticalStrut(10))
    formPanel.add(providerPanel)
    formPanel.add(apiKeyPanel)
    formPanel.add(modelPanel)
    formPanel.add(ollamaEndpointPanel)
    formPanel.add(ollamaModelPanel)
    formPanel.add(maxFilesPanel)
    formPanel.add(allowCloudBox)

    mainPanel.add(formPanel, BorderLayout.NORTH)
    panel = mainPanel
    return mainPanel
}

override fun isModified(): Boolean {
    val s = GhostDebuggerSettings.getInstance().snapshot()
    val currentKey = ApiKeyManager.getApiKey() ?: ""
    val enteredKey = String(apiKeyField?.password ?: charArrayOf())
    return currentKey != enteredKey
        || s.aiProvider.name != providerCombo?.selectedItem
        || s.openAiModel != modelCombo?.selectedItem
        || s.ollamaEndpoint != ollamaEndpointField?.text
        || s.ollamaModel != ollamaModelField?.text
        || s.maxFilesToAnalyze != maxFilesSpinner?.value
        || s.allowCloudUpload != allowCloudUploadBox?.isSelected
}

override fun apply() {
    val key = String(apiKeyField?.password ?: charArrayOf())
    if (key.isNotBlank()) ApiKeyManager.setApiKey(key)

    GhostDebuggerSettings.getInstance().update {
        aiProvider = AIProvider.valueOf(providerCombo?.selectedItem as? String ?: "NONE")
        (modelCombo?.selectedItem as? String)?.let { openAiModel = it }
        (ollamaEndpointField?.text)?.let { ollamaEndpoint = it }
        (ollamaModelField?.text)?.let { ollamaModel = it }
        (maxFilesSpinner?.value as? Int)?.let { maxFilesToAnalyze = it }
        allowCloudUpload = allowCloudUploadBox?.isSelected ?: false
    }
}

override fun reset() {
    val s = GhostDebuggerSettings.getInstance().snapshot()
    apiKeyField?.text = ApiKeyManager.getApiKey() ?: ""
    providerCombo?.selectedItem = s.aiProvider.name
    modelCombo?.selectedItem = s.openAiModel
    ollamaEndpointField?.text = s.ollamaEndpoint
    ollamaModelField?.text = s.ollamaModel
    maxFilesSpinner?.value = s.maxFilesToAnalyze
    allowCloudUploadBox?.isSelected = s.allowCloudUpload
}
```

Declare the new fields at the class top:
```kotlin
private var providerCombo: JComboBox<String>? = null
private var ollamaEndpointField: JTextField? = null
private var ollamaModelField: JTextField? = null
private var allowCloudUploadBox: JCheckBox? = null
```

### 7.3 `Analyzer.kt` — extended contract

Replace file contents with:

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity

interface Analyzer {
    /** Stable human-friendly name. Used in logs. */
    val name: String

    /** Stable rule identifier. MUST be non-blank, stable across releases, and unique per analyzer. Format: `AEG-<CATEGORY>-<NNN>`. */
    val ruleId: String

    /** Default severity assigned to issues produced by this analyzer unless the analyzer overrides per-issue. */
    val defaultSeverity: IssueSeverity

    /** One-sentence description of what this rule detects. Shown in future UI surfaces and used by tests. */
    val description: String

    fun analyze(context: AnalysisContext): List<Issue>
}
```

### 7.4 Analyzer metadata — bindings

| Class | `ruleId` | `defaultSeverity` | `description` |
|---|---|---|---|
| `NullSafetyAnalyzer` | `AEG-NULL-001` | `IssueSeverity.ERROR` | `"Detects property access on variables initialized as null/undefined without a guarding null check."` |
| `StateInitAnalyzer` | `AEG-STATE-001` | `IssueSeverity.ERROR` | `"Detects React useState hooks called without an initial value whose values are later used via .map/.filter/.forEach/.reduce/.find/.some/.every/.length/.slice/.join."` |
| `AsyncFlowAnalyzer` | `AEG-ASYNC-001` | `IssueSeverity.ERROR` | `"Detects unhandled promise rejections, fetch calls without status check or try/catch, and setInterval/setTimeout in useEffect without a cleanup return."` |
| `CircularDependencyAnalyzer` | `AEG-CYCLE-001` | `IssueSeverity.WARNING` | `"Detects import cycles between modules in the resolved dependency graph."` |
| `ComplexityAnalyzer` | `AEG-CPX-001` | `IssueSeverity.WARNING` | `"Flags nodes whose estimated cyclomatic complexity exceeds the configured threshold (default 10)."` |

Each analyzer file adds the three `override val` declarations immediately after `override val name`:

```kotlin
override val name = "NullSafetyAnalyzer"
override val ruleId = "AEG-NULL-001"
override val defaultSeverity = IssueSeverity.ERROR
override val description =
    "Detects property access on variables initialized as null/undefined without a guarding null check."
```

(Repeat pattern for the other four with the table values.)

### 7.5 `GhostDebuggerService.kt` — user-visible string swaps

| Line | Before | After |
|---|---|---|
| 443 | `"OpenAI API key not configured. Go to Settings → Tools → GhostDebugger"` | `"OpenAI API key not configured. Go to Settings → Tools → Aegis Debug"` |
| 558 | `"Configura tu API key de OpenAI en Settings → Tools → GhostDebugger para obtener análisis detallados con IA."` | `"Configura tu API key de OpenAI en Settings → Tools → Aegis Debug para obtener análisis detallados con IA."` |

No other strings in this file mention the brand.

### 7.6 `build.gradle.kts` description block

```kotlin
description = """
    Aegis Debug — privacy-first code debugging for IntelliJ.
    Static analysis first. Deterministic PSI-based fixes where possible.
    Optional local (Ollama) or cloud (OpenAI) AI reasoning, off by default.

    Features:
    - Static-first project analysis with visual NeuroMap
    - Deterministic issue detection (null safety, state init, async flow, cycles, complexity)
    - Optional AI-assisted explanation and missed-issue discovery
    - Clear provenance: engine-verified vs AI-suggested
    - Local-first; cloud upload requires explicit opt-in
""".trimIndent()
```

### 7.7 `plugin.xml` `<description>` CDATA

```xml
<description><![CDATA[
    <h2>Aegis Debug — privacy-first code debugging for IntelliJ</h2>
    <p>
        Static analysis first. Deterministic fixes where possible.
        Optional local or cloud AI for deeper reasoning — off by default.
    </p>
    <ul>
        <li><strong>NeuroMap</strong> — visual project graph with issue overlay</li>
        <li><strong>Static-first analysis</strong> — null safety, state init, async flow, cycles, complexity</li>
        <li><strong>Optional AI</strong> — Ollama (local) or OpenAI (cloud)</li>
        <li><strong>Trust signals</strong> — engine-verified vs AI-suggested, always clearly labeled</li>
        <li><strong>Privacy</strong> — local-first; cloud requires explicit opt-in</li>
    </ul>
]]></description>
```

### 7.8 `aegis.svg` contents

```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 13 13" width="13" height="13">
    <path d="M6.5 1 L11 3 V6.5 C11 9 8.75 11.3 6.5 12 C4.25 11.3 2 9 2 6.5 V3 Z"
          fill="none" stroke="#FDFBF7" stroke-width="1.1" stroke-linejoin="round"/>
    <circle cx="5.2" cy="6.2" r="0.9" fill="none" stroke="#FDFBF7" stroke-width="0.9"/>
    <circle cx="7.8" cy="7.6" r="0.9" fill="none" stroke="#FDFBF7" stroke-width="0.9"/>
    <line x1="5.9" y1="6.7" x2="7.1" y2="7.2" stroke="#FDFBF7" stroke-width="0.9" stroke-linecap="round"/>
</svg>
```

### 7.9 `FixtureFactory.kt`

```kotlin
package com.ghostdebugger.testutil

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.ParsedFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.mockk

object FixtureFactory {

    fun parsedFile(path: String, ext: String, content: String): ParsedFile =
        ParsedFile(
            virtualFile = mockk<VirtualFile>(relaxed = true),
            path = path,
            extension = ext,
            content = content
        )

    fun context(
        files: List<ParsedFile>,
        graph: InMemoryGraph = InMemoryGraph(),
        project: Project = mockk(relaxed = true)
    ): AnalysisContext = AnalysisContext(graph = graph, project = project, parsedFiles = files)
}
```

### 7.10 Test templates — one positive + one negative per analyzer

See §12 for the exact fixtures and assertions.

---

## 8. Ordered Implementation Steps

Execute in order. Each step must leave the project in a compilable, test-green state before moving on. Use `./gradlew build` as the gate.

### Step 0 — Baseline sanity
1. `./gradlew --version` → confirm wrapper works on Windows.
2. `./gradlew build` → current state compiles. Capture output.

### Step 1 — Settings refactor (behavioral core)
1. Open `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt`.
2. Replace file contents with §7.1 verbatim.
3. `./gradlew compileKotlin` — must pass. `GhostDebuggerConfigurable.kt` still references `openAiModel`, `maxFilesToAnalyze`, `autoAnalyzeOnOpen`; legacy delegates in §7.1 preserve those call sites.

### Step 2 — Settings UI changes
1. Open `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt`.
2. Apply the replacement from §7.2 (add the four new fields at the class top; replace `createComponent()`, `isModified()`, `apply()`, `reset()` bodies).
3. Update `getDisplayName()` return value to `"Aegis Debug"`.
4. Replace both `Messages.showWarningDialog` / `showInfoMessage` title args `"GhostDebugger"` with `"Aegis Debug"`.
5. `./gradlew compileKotlin`.

### Step 3 — Analyzer contract extension
1. Open `src/main/kotlin/com/ghostdebugger/analysis/Analyzer.kt`.
2. Replace with §7.3 verbatim.
3. For each of the five analyzer classes in `src/main/kotlin/com/ghostdebugger/analysis/analyzers/` *except* `AIAnalyzer.kt`, add the three `override val` declarations using the bindings in §7.4.
4. `./gradlew compileKotlin` — must pass. `AIAnalyzer` compiles unchanged because it does not implement `Analyzer`.

### Step 4 — Branding: build.gradle.kts
1. `build.gradle.kts:52` → `name = "Aegis Debug"`.
2. `build.gradle.kts:54-64` → replace description with §7.6.
3. `build.gradle.kts:72` → `vendor { name = "Aegis Debug" }`.

### Step 5 — Branding: plugin.xml
1. Line 4: `<name>Aegis Debug</name>`.
2. Line 5: `<vendor email="team@ghostdebugger.dev">Aegis Debug</vendor>`.
3. Lines 7–20: replace `<description>` CDATA with §7.7.
4. Line 30: `icon="/icons/aegis.svg"` on the `<toolWindow>` element.
5. Line 44: `displayName="Aegis Debug"` on `<applicationConfigurable>`.
6. Line 57: `<group id="GhostDebugger.Menu" text="Aegis Debug" popup="true" icon="/icons/aegis.svg">`.
7. Line 65: `<action ... description="Analyze entire project with Aegis Debug" ...>`.

### Step 6 — Branding asset
1. Create `src/main/resources/icons/aegis.svg` with the contents from §7.8.
2. Do **not** delete `ghost.svg`.

### Step 7 — Branding: Kotlin UI runtime strings
1. `GhostDebuggerService.kt:443` and `:558` per §7.5.
2. `GhostDebuggerToolWindowFactory.kt`: after `contentManager.addContent(content)` add `toolWindow.setTitle("Aegis Debug")`.

### Step 8 — Branding: webview
1. `webview/index.html:6` `<title>` → `Aegis Debug NeuroMap`.
2. `webview/src/App.tsx:369` text "GhostDebugger" → "Aegis Debug".
3. `webview/src/components/layout/StatusBar.tsx:38` remove the emoji span; change "GhostDebugger" to "Aegis Debug" on line 41.
4. `webview/package.json:5` description field updated.

### Step 9 — Build the webview
1. `cd webview && npm install && npm run build` → produces `src/main/resources/web/index.html` with the new title.
2. `cd ..`

### Step 10 — Test harness scaffold
1. Create directory `src/test/kotlin/com/ghostdebugger/testutil/`.
2. Write `src/test/kotlin/com/ghostdebugger/testutil/FixtureFactory.kt` per §7.9.

### Step 11 — Settings tests
1. Create `src/test/kotlin/com/ghostdebugger/settings/GhostDebuggerSettingsTest.kt`:

```kotlin
package com.ghostdebugger.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class GhostDebuggerSettingsTest {

    @Test
    fun `default state has provider NONE and safe defaults`() {
        val s = GhostDebuggerSettings.State()
        assertEquals(AIProvider.NONE, s.aiProvider)
        assertEquals("gpt-4o", s.openAiModel)
        assertEquals("http://localhost:11434", s.ollamaEndpoint)
        assertEquals("llama3", s.ollamaModel)
        assertEquals(500, s.maxFilesToAnalyze)
        assertEquals(100, s.maxAiFiles)
        assertEquals(false, s.autoAnalyzeOnOpen)
        assertEquals(true, s.showInfoIssues)
        assertEquals(true, s.cacheEnabled)
        assertEquals(3600L, s.cacheTtlSeconds)
        assertEquals(30_000L, s.aiTimeoutMs)
        assertEquals(false, s.allowCloudUpload)
        assertEquals(false, s.analyzeOnlyChangedFiles)
    }

    @Test
    fun `loadState normalizes invalid values via validate`() {
        val target = GhostDebuggerSettings()
        val bad = GhostDebuggerSettings.State(
            maxFilesToAnalyze = -5,
            maxAiFiles = -1,
            cacheTtlSeconds = -99,
            aiTimeoutMs = 0,
            ollamaEndpoint = "",
            ollamaModel = "",
            openAiModel = ""
        )
        target.loadState(bad)
        val after = target.state
        assertEquals(500, after.maxFilesToAnalyze)
        assertEquals(0, after.maxAiFiles)
        assertEquals(0L, after.cacheTtlSeconds)
        assertEquals(30_000L, after.aiTimeoutMs)
        assertEquals("http://localhost:11434", after.ollamaEndpoint)
        assertEquals("llama3", after.ollamaModel)
        assertEquals("gpt-4o", after.openAiModel)
    }

    @Test
    fun `snapshot returns detached copy`() {
        val target = GhostDebuggerSettings()
        val snap = target.snapshot()
        target.update { maxFilesToAnalyze = 123 }
        assertNotSame(snap, target.snapshot())
        assertEquals(500, snap.maxFilesToAnalyze)
        assertEquals(123, target.snapshot().maxFilesToAnalyze)
    }

    @Test
    fun `update mutator runs validate on writes`() {
        val target = GhostDebuggerSettings()
        target.update { maxFilesToAnalyze = -99 }
        assertEquals(500, target.snapshot().maxFilesToAnalyze)
    }

    @Test
    fun `legacy setters route through update and validate`() {
        val target = GhostDebuggerSettings()
        target.maxFilesToAnalyze = -1
        assertEquals(500, target.maxFilesToAnalyze)
        target.openAiModel = "gpt-4o-mini"
        assertEquals("gpt-4o-mini", target.openAiModel)
        target.autoAnalyzeOnOpen = true
        assertTrue(target.autoAnalyzeOnOpen)
    }

    @Test
    fun `AIProvider enum contains exactly NONE OPENAI OLLAMA`() {
        assertEquals(listOf("NONE", "OPENAI", "OLLAMA"), AIProvider.values().map { it.name })
    }
}
```

2. Add `src/test/kotlin/com/ghostdebugger/analysis/AnalyzerContractTest.kt`:

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.analysis.analyzers.AsyncFlowAnalyzer
import com.ghostdebugger.analysis.analyzers.CircularDependencyAnalyzer
import com.ghostdebugger.analysis.analyzers.ComplexityAnalyzer
import com.ghostdebugger.analysis.analyzers.NullSafetyAnalyzer
import com.ghostdebugger.analysis.analyzers.StateInitAnalyzer
import com.ghostdebugger.model.IssueSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class AnalyzerContractTest {

    private val analyzers: List<Analyzer> = listOf(
        NullSafetyAnalyzer(),
        StateInitAnalyzer(),
        AsyncFlowAnalyzer(),
        CircularDependencyAnalyzer(),
        ComplexityAnalyzer()
    )

    @Test
    fun `every V1 analyzer declares non-blank metadata`() {
        for (a in analyzers) {
            assertTrue(a.name.isNotBlank(), "name blank for $a")
            assertTrue(a.ruleId.isNotBlank(), "ruleId blank for ${a.name}")
            assertTrue(a.description.isNotBlank(), "description blank for ${a.name}")
            assertTrue(
                a.defaultSeverity in listOf(IssueSeverity.ERROR, IssueSeverity.WARNING, IssueSeverity.INFO),
                "invalid defaultSeverity for ${a.name}"
            )
        }
    }

    @Test
    fun `ruleIds are unique and match AEG pattern`() {
        val ids = analyzers.map { it.ruleId }
        assertTrue(ids.distinct().size == ids.size, "duplicate ruleId in $ids")
        val pattern = Regex("""^AEG-[A-Z]+-\d{3}$""")
        for (id in ids) {
            assertTrue(pattern.matches(id), "ruleId '$id' does not match AEG-<CATEGORY>-<NNN>")
        }
    }
}
```

### Step 12 — Analyzer fixture tests
Create one file per analyzer. Each file has exactly two tests: `flags_positive_case`, `does_not_flag_negative_case`. Exact test bodies:

#### `NullSafetyAnalyzerTest.kt`
```kotlin
package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.IssueType
import com.ghostdebugger.testutil.FixtureFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class NullSafetyAnalyzerTest {
    private val analyzer = NullSafetyAnalyzer()

    @Test
    fun `flags direct property access on variable initialized as null`() {
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              return <div>{user.name}</div>;
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/A.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.NULL_SAFETY }, "expected NULL_SAFETY issue in $issues")
    }

    @Test
    fun `does not flag access guarded by null check`() {
        val code = """
            function render() {
              const [user, setUser] = useState(null);
              if (user) {
                return <div>{user.name}</div>;
              }
              return null;
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/B.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.NULL_SAFETY }, "did not expect NULL_SAFETY, got $issues")
    }
}
```

#### `StateInitAnalyzerTest.kt`
```kotlin
package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.IssueType
import com.ghostdebugger.testutil.FixtureFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class StateInitAnalyzerTest {
    private val analyzer = StateInitAnalyzer()

    @Test
    fun `flags map on useState called without initial value`() {
        val code = """
            function List() {
              const [items, setItems] = useState();
              return items.map(i => <li key={i}>{i}</li>);
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/List.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.STATE_BEFORE_INIT }, issues.toString())
    }

    @Test
    fun `does not flag map on useState with empty array`() {
        val code = """
            function List() {
              const [items, setItems] = useState([]);
              return items.map(i => <li key={i}>{i}</li>);
            }
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/List.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.STATE_BEFORE_INIT }, issues.toString())
    }
}
```

#### `AsyncFlowAnalyzerTest.kt`
```kotlin
package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.IssueType
import com.ghostdebugger.testutil.FixtureFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class AsyncFlowAnalyzerTest {
    private val analyzer = AsyncFlowAnalyzer()

    @Test
    fun `flags setInterval inside useEffect without cleanup`() {
        val code = """
            useEffect(() => {
              setInterval(() => doTick(), 1000);
            }, []);
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/Timer.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.MEMORY_LEAK }, issues.toString())
    }

    @Test
    fun `does not flag setInterval with clearInterval cleanup`() {
        val code = """
            useEffect(() => {
              const id = setInterval(() => doTick(), 1000);
              return () => clearInterval(id);
            }, []);
        """.trimIndent()
        val ctx = FixtureFactory.context(
            listOf(FixtureFactory.parsedFile("/src/Timer.tsx", "tsx", code))
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.MEMORY_LEAK }, issues.toString())
    }
}
```

#### `CircularDependencyAnalyzerTest.kt`
```kotlin
package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.EdgeType
import com.ghostdebugger.model.GraphEdge
import com.ghostdebugger.model.GraphNode
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.NodeType
import com.ghostdebugger.testutil.FixtureFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class CircularDependencyAnalyzerTest {
    private val analyzer = CircularDependencyAnalyzer()

    @Test
    fun `flags two-node cycle`() {
        val graph = InMemoryGraph().apply {
            addNode(GraphNode(id = "A", type = NodeType.FILE, name = "A.kt", filePath = "/src/A.kt"))
            addNode(GraphNode(id = "B", type = NodeType.FILE, name = "B.kt", filePath = "/src/B.kt"))
            addEdge(GraphEdge(id = "A->B", source = "A", target = "B", type = EdgeType.IMPORT))
            addEdge(GraphEdge(id = "B->A", source = "B", target = "A", type = EdgeType.IMPORT))
        }
        val ctx = FixtureFactory.context(files = emptyList(), graph = graph)
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.CIRCULAR_DEPENDENCY }, issues.toString())
    }

    @Test
    fun `does not flag a linear chain`() {
        val graph = InMemoryGraph().apply {
            addNode(GraphNode(id = "A", type = NodeType.FILE, name = "A.kt", filePath = "/src/A.kt"))
            addNode(GraphNode(id = "B", type = NodeType.FILE, name = "B.kt", filePath = "/src/B.kt"))
            addNode(GraphNode(id = "C", type = NodeType.FILE, name = "C.kt", filePath = "/src/C.kt"))
            addEdge(GraphEdge(id = "A->B", source = "A", target = "B", type = EdgeType.IMPORT))
            addEdge(GraphEdge(id = "B->C", source = "B", target = "C", type = EdgeType.IMPORT))
        }
        val ctx = FixtureFactory.context(files = emptyList(), graph = graph)
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.CIRCULAR_DEPENDENCY }, issues.toString())
    }
}
```

#### `ComplexityAnalyzerTest.kt`
```kotlin
package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.GraphNode
import com.ghostdebugger.model.IssueType
import com.ghostdebugger.model.NodeType
import com.ghostdebugger.testutil.FixtureFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class ComplexityAnalyzerTest {
    private val analyzer = ComplexityAnalyzer()

    @Test
    fun `flags node with complexity above threshold`() {
        val graph = InMemoryGraph().apply {
            addNode(GraphNode(
                id = "/src/Big.kt",
                type = NodeType.FILE,
                name = "Big.kt",
                filePath = "/src/Big.kt",
                complexity = 15
            ))
        }
        val ctx = FixtureFactory.context(
            files = listOf(FixtureFactory.parsedFile("/src/Big.kt", "kt", "// stub\n")),
            graph = graph
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.any { it.type == IssueType.HIGH_COMPLEXITY }, issues.toString())
    }

    @Test
    fun `does not flag node at or below threshold`() {
        val graph = InMemoryGraph().apply {
            addNode(GraphNode(
                id = "/src/Small.kt",
                type = NodeType.FILE,
                name = "Small.kt",
                filePath = "/src/Small.kt",
                complexity = 10
            ))
        }
        val ctx = FixtureFactory.context(
            files = listOf(FixtureFactory.parsedFile("/src/Small.kt", "kt", "// stub\n")),
            graph = graph
        )
        val issues = analyzer.analyze(ctx)
        assertTrue(issues.none { it.type == IssueType.HIGH_COMPLEXITY }, issues.toString())
    }
}
```

### Step 13 — Run tests
1. `./gradlew test`
2. All 13 new test methods must pass (5 analyzer positive + 5 analyzer negative + 6 settings + 2 contract = 18). If any fail, fix the analyzer metadata or the fixture, not the test assertion.

### Step 14 — Full build
1. `./gradlew build` → green.
2. `./gradlew buildPlugin` → produces `build/distributions/Aegis Debug-0.1.0.zip` (name derives from `pluginConfiguration.name`).
3. Inspect the zip: plugin.xml inside `META-INF/` has `<name>Aegis Debug</name>`; icons folder contains both `aegis.svg` and `ghost.svg`.

### Step 15 — Smoke in sandbox
1. `./gradlew runIde`
2. Open a sample project; confirm:
   - Tool window titled **Aegis Debug** on the right anchor.
   - Menu `Tools → Aegis Debug` present.
   - `Settings → Tools → Aegis Debug` shows: AI Provider dropdown (default `NONE`), OpenAI API Key, OpenAI Model, Ollama Endpoint, Ollama Model, Max files, Allow cloud upload checkbox.
   - Status bar in the webview shows "Aegis Debug" (no emoji).
   - Empty state in the webview shows "Aegis Debug".

---

## 9. Business Rules and Edge Cases

### 9.1 Settings defaults
- Default `aiProvider` is `NONE`. Fresh install behaves as static-only.
- `maxFilesToAnalyze <= 0` coerces to `500`.
- `maxAiFiles < 0` coerces to `0` (means "do not invoke AI even if provider is configured").
- `cacheTtlSeconds < 0` coerces to `0` (cache disabled effectively).
- `aiTimeoutMs <= 0` coerces to `30_000`.
- Blank `ollamaEndpoint` coerces to `http://localhost:11434`.
- Blank `ollamaModel` coerces to `llama3`.
- Blank `openAiModel` coerces to `gpt-4o`.
- `allowCloudUpload` default is `false`.

### 9.2 Settings persistence
- The `@Storage("ghostdebugger.xml")` file name is retained. Users upgrading from pre-Phase-1 builds keep their `openAiModel`, `maxFilesToAnalyze`, `autoAnalyzeOnOpen`, `showInfoIssues`, `cacheEnabled`, `cacheTtlSeconds` values. New fields apply defaults.
- `loadState()` always runs `validate()` before adopting the state.

### 9.3 Settings UI
- `apply()` writes all fields through `update {}` so `validate()` runs on each write.
- `reset()` reads via `snapshot()` to avoid racing with `update {}`.
- Selecting `OLLAMA` from the provider dropdown does not hide the OpenAI API Key field in Phase 1 (simpler UI, no runtime provider check yet).

### 9.4 Analyzer contract
- Every class implementing `Analyzer` must provide a non-blank `ruleId`, `description`, and a valid `defaultSeverity`. Enforced by `AnalyzerContractTest`.
- `AIAnalyzer` does not implement `Analyzer` and is therefore unaffected.
- `ruleId` format `AEG-<CATEGORY>-<NNN>` is asserted by test; new analyzers added in later phases must comply.

### 9.5 Branding edge cases
- The credential store entry, persisted-state file name, and tool-window id remain on the legacy strings (`GhostDebugger`). This is intentional — changing them would orphan user data.
- `webview/package.json:name` stays `@ghostdebugger/webview`. Only `description` is updated. This package is never published (`"private": true`), so the scope is irrelevant to users.
- `ghost.svg` remains on disk but is no longer referenced from `plugin.xml`. Do not delete it in Phase 1 to avoid breaking any out-of-tree references during rebrand validation.

### 9.6 Settings service lookup
- `GhostDebuggerSettings.getInstance()` now calls `ApplicationManager.getApplication().getService(...)` without the pre-existing null-safe fallback that returned a fresh instance. Unit tests must therefore **not** call `getInstance()` — they instantiate `GhostDebuggerSettings()` directly (as `GhostDebuggerSettingsTest` does). This is a binding rule.

### 9.7 Tool-window title override
- `ToolWindow.setTitle("Aegis Debug")` is safe to call in `createToolWindowContent` because the tool window is already registered when the factory runs. Call it after `contentManager.addContent(content)`.

### 9.8 Webview rebuild
- Changes under `webview/src/**` require `npm run build` to regenerate `src/main/resources/web/index.html`. The Gradle `processResources` task depends on `buildWebview` (`build.gradle.kts:103-105`), so `./gradlew build` does this automatically; a manual run is only required when iterating without Gradle.

### 9.9 Icon resolution
- IntelliJ resolves `/icons/aegis.svg` against the classpath root for plugin resources (`src/main/resources/icons/aegis.svg`). Path is absolute from the resource root and is case-sensitive.

### 9.10 Error paths
- If `aegis.svg` is missing at runtime, the tool window appears without an icon — not a crash. The smoke test in Step 15 catches this.
- If the settings panel fails to open due to a Kotlin runtime error (e.g., unresolved reference), the `ExceptionInInitializerError` shows in the IDE's internal log. Run `./gradlew compileKotlin` to catch it before smoke.
- If `GhostDebuggerSettings.getInstance()` is called before the application is ready (e.g., during some early tool-window warmup), the call will throw. This matches stock IntelliJ plugin behavior. No mitigation in Phase 1.

---

## 10. UI/UX Requirements

### 10.1 Text strings (must match exactly)

| Surface | Text |
|---|---|
| Plugin display name (Marketplace, plugin list) | `Aegis Debug` |
| Tool window title (right anchor) | `Aegis Debug` |
| Settings page heading | `Aegis Debug` |
| Settings panel title label | `Aegis Debug Configuration` |
| Menu group text (Tools menu) | `Aegis Debug` |
| Messages dialog title (test-connection flow) | `Aegis Debug` |
| Webview `<title>` | `Aegis Debug NeuroMap` |
| Webview status-bar brand | `Aegis Debug` |
| Webview empty-state heading | `Aegis Debug` |
| Webview empty-state emoji | none (removed) |
| Error toast string "...Settings → Tools → GhostDebugger" | "...Settings → Tools → Aegis Debug" |

### 10.2 Icons

| Usage | Icon |
|---|---|
| Tool-window icon | `/icons/aegis.svg` |
| Menu group icon | `/icons/aegis.svg` |
| Analyze action icon | `/icons/analyze.svg` (unchanged) |

### 10.3 Settings panel layout (top-to-bottom)

1. Header label: `Aegis Debug Configuration`
2. Subheader: `Static-first analysis. AI is optional and off by default.`
3. Row: `AI Provider:` + dropdown (`NONE`, `OPENAI`, `OLLAMA`)
4. Row: `OpenAI API Key:` + password field + `Test Connection` button
5. Row: `OpenAI Model:` + combo (`gpt-4o`, `gpt-4o-mini`, `gpt-4-turbo`, `gpt-3.5-turbo`)
6. Row: `Ollama Endpoint:` + text field
7. Row: `Ollama Model:` + text field
8. Row: `Max files to analyze:` + spinner (min 10, max 2000, step 50)
9. Row: `Allow cloud upload (OpenAI)` checkbox

### 10.4 Accessibility
- All new widgets are standard Swing and inherit IntelliJ's accessibility behavior. No custom focus work in Phase 1.
- No color changes in Phase 1 (Dark Navy + Cream refresh is Phase 4).

### 10.5 Excluded UX work
- No provider-mode-aware enable/disable of OpenAI vs Ollama fields.
- No engine status indicator in the webview status bar (Phase 4).
- No AIProvider badge on issue cards.
- No "Static Mode" / "Ollama (Local)" / "OpenAI" pill in the status bar.

---

## 11. API / Backend Requirements

### 11.1 Persistent state service
- Class: `com.ghostdebugger.settings.GhostDebuggerSettings`
- Service scope: `@Service` (application-level)
- `@State(name = "GhostDebuggerSettings", storages = [Storage("ghostdebugger.xml")])`
- Public contract:
  - `fun getState(): State`
  - `fun loadState(state: State)` — runs `validate()`
  - `fun snapshot(): State` — returns immutable `.copy()`
  - `fun update(transform: State.() -> Unit)` — mutates with validation
  - Legacy `var` accessors: `openAiModel`, `maxFilesToAnalyze`, `autoAnalyzeOnOpen`
  - `companion object { fun getInstance(): GhostDebuggerSettings }`

### 11.2 Credential store
- Unchanged: `ApiKeyManager.getApiKey()` / `setApiKey()` / `hasApiKey()`.
- Service name literal `"GhostDebugger"` retained (§5 decision 2).

### 11.3 Analyzer SPI
- Interface `com.ghostdebugger.analysis.Analyzer` with: `name`, `ruleId`, `defaultSeverity`, `description`, `analyze(context)`.
- Enforced by `AnalyzerContractTest`.

### 11.4 No new bridge messages, no new UI events, no new JSON schemas in Phase 1.

### 11.5 Upgrade / state migration
- No migration required. New fields use defaults; old fields load from existing `ghostdebugger.xml`.
- The renamed `State` field set is a superset of the previous one. `PersistentStateComponent` tolerates missing fields (they retain defaults).

### 11.6 Removed code
- None. All existing public APIs remain call-compatible.

---

## 12. Testing Requirements

### 12.1 Frameworks
- JUnit 5 (`org.junit.jupiter:junit-jupiter:5.11.4`)
- `kotlin-test-junit5`
- MockK 1.13.15
- `kotlinx-coroutines-test:1.9.0` (reserved; not required by Phase 1 tests)

### 12.2 Source set
- `src/test/kotlin/com/ghostdebugger/**`
- Gradle detects automatically via `useJUnitPlatform()` (`build.gradle.kts:81`).

### 12.3 Required tests (all must pass)

| Test file | Methods | Purpose |
|---|---|---|
| `settings/GhostDebuggerSettingsTest.kt` | `default state has provider NONE and safe defaults`, `loadState normalizes invalid values via validate`, `snapshot returns detached copy`, `update mutator runs validate on writes`, `legacy setters route through update and validate`, `AIProvider enum contains exactly NONE OPENAI OLLAMA` | Settings contract. |
| `analysis/AnalyzerContractTest.kt` | `every V1 analyzer declares non-blank metadata`, `ruleIds are unique and match AEG pattern` | Analyzer SPI contract. |
| `analysis/analyzers/NullSafetyAnalyzerTest.kt` | `flags direct property access on variable initialized as null`, `does not flag access guarded by null check` | Fixture for §7.4 ruleId `AEG-NULL-001`. |
| `analysis/analyzers/StateInitAnalyzerTest.kt` | `flags map on useState called without initial value`, `does not flag map on useState with empty array` | `AEG-STATE-001`. |
| `analysis/analyzers/AsyncFlowAnalyzerTest.kt` | `flags setInterval inside useEffect without cleanup`, `does not flag setInterval with clearInterval cleanup` | `AEG-ASYNC-001`. |
| `analysis/analyzers/CircularDependencyAnalyzerTest.kt` | `flags two-node cycle`, `does not flag a linear chain` | `AEG-CYCLE-001`. |
| `analysis/analyzers/ComplexityAnalyzerTest.kt` | `flags node with complexity above threshold`, `does not flag node at or below threshold` | `AEG-CPX-001`. |

**Total: 18 test methods.**

### 12.4 Gating
- `./gradlew test` exit code `0` is the gate for Phase 1 completion.
- Test failures are blockers, not warnings.

### 12.5 Not required in Phase 1
- Integration tests against a live `IdeaTestFixture` (deferred to Phase 3/4 when PSI fixes and bridge events land).
- Webview unit tests (no Jest/Vitest infra; defer).
- Kotlin coverage measurement.
- Performance/benchmark tests (Phase 6).

---

## 13. Acceptance Criteria

Phase 1 is complete when **all** of the following hold:

1. **Rebrand coverage**
   1. `./gradlew buildPlugin` produces a zip whose `META-INF/plugin.xml` has `<name>Aegis Debug</name>` and `<vendor>Aegis Debug</vendor>`.
   2. Installing the built plugin into a fresh IntelliJ Community 2024.3.2 sandbox shows the plugin as **Aegis Debug** in the plugin list.
   3. The tool window on the right anchor displays the title **Aegis Debug**.
   4. `Tools → Aegis Debug → Analyze Project` appears in the main menu.
   5. `Settings → Tools → Aegis Debug` opens a settings page with exactly the layout of §10.3.
   6. The webview (`src/main/resources/web/index.html` post-build) contains the string `Aegis Debug` in the `<title>`, status bar, and empty state.
   7. No user-visible surface mentions `GhostDebugger` or the ghost emoji `👻` outside the retained `README.md:73-75` branding-note paragraph.

2. **Settings refactor**
   1. `AIProvider { NONE, OPENAI, OLLAMA }` exists in `com.ghostdebugger.settings`.
   2. `GhostDebuggerSettings.snapshot()` returns a detached `.copy()`.
   3. `GhostDebuggerSettings.update { ... }` applies `validate()` on each write.
   4. Default `aiProvider == AIProvider.NONE` on a fresh install.
   5. Invalid inputs (negative `maxFilesToAnalyze`, blank `ollamaEndpoint`, etc.) are normalized on load.
   6. `OpenAI API Key` is still persisted via `PasswordSafe` — not in the settings XML.
   7. Ollama endpoint and model are persisted in `ghostdebugger.xml`.

3. **Analyzer contract**
   1. `Analyzer` interface requires `ruleId`, `defaultSeverity`, `description`.
   2. Every class in `src/main/kotlin/com/ghostdebugger/analysis/analyzers/` that implements `Analyzer` declares non-blank metadata matching the bindings in §7.4.
   3. `AnalyzerContractTest` passes.

4. **Test baseline**
   1. `src/test/kotlin/com/ghostdebugger/...` exists with the 7 files listed in §12.3.
   2. `./gradlew test` passes with all 18 methods green.

5. **Build hygiene**
   1. `./gradlew clean build` runs green.
   2. `./gradlew runIde` opens a sandbox IDE without errors in `idea.log`.

6. **Data safety**
   1. A user who previously used the "GhostDebugger" beta still sees their OpenAI API key after upgrade (PasswordSafe entry unchanged).
   2. A user who previously used the beta still sees their `openAiModel`, `maxFilesToAnalyze`, and `autoAnalyzeOnOpen` values after upgrade (`ghostdebugger.xml` unchanged).

7. **Non-regression**
   1. `AnalysisEngine.analyze(ctx)` still produces output when invoked with the existing sample fixtures used in the webview smoke test.
   2. No public method signature in the surviving Kotlin files changed except for the additive `Analyzer` members and the additive settings accessors.

---

## 14. Risks / Caveats

### 14.1 Rebrand without full rename
Keeping the Kotlin package `com.ghostdebugger`, the storage file name, and the credential service literal means search/grep for "ghostdebugger" will still return hits in code. This is intentional. Future Phase will migrate these with an explicit state-migration routine. The rebrand audit in §13 is strictly user-visible-surface coverage.

### 14.2 Settings refactor is source-compatible but binary-incompatible on `State`
Adding fields to the `State` data class changes its constructor signature. Any external code calling `State(...)` positionally would break. Verified: the only internal usage is via `State()` zero-arg (this file itself, `GhostDebuggerSettings.kt`). No other module instantiates `State`. Safe.

### 14.3 Swing UI growth
Adding rows to `GhostDebuggerConfigurable` pushes the settings dialog taller. With the current widget count (≈9 rows) the dialog fits a 600-px-tall window. No scroll pane needed in Phase 1.

### 14.4 Webview rebuild on every branding text change
Any edit to `webview/src/**` requires rebuilding the bundle via `npm run build` (or `./gradlew build` which triggers `buildWebview`). CI must run `./gradlew build`, not `compileKotlin` alone, to pick up webview text changes.

### 14.5 `AIAnalyzer` is not an `Analyzer`
`AIAnalyzer` does **not** implement the `Analyzer` interface today. Phase 1 intentionally leaves this. Phase 5 will decide whether to fold AI into the `Analyzer` SPI or keep it separate. Do not try to retrofit `AIAnalyzer` in Phase 1.

### 14.6 Tool-window title override dependency
`toolWindow.setTitle(...)` is available in IntelliJ Platform 2024.3.x. If compiling against an older API (we target `sinceBuild 243`), verify the method resolves; if not, fall back to setting the content tab's display name via `content.displayName = "Aegis Debug"` in the factory. The default path (`setTitle`) is primary.

### 14.7 Plugin icon caching
IntelliJ caches plugin icons between runs. A stale `ghost.svg` may appear until `File → Invalidate Caches` or a fresh sandbox. Use `./gradlew clean runIde` for a clean validation of §13 item 1.3.

### 14.8 README stray branding
Existing `README.md` line 5 references "GhostDebugger" inside the branding-note paragraph; line 74 repeats. Both are intentional history preservation. No other occurrences exist outside these two lines (confirmed at spec time). If future edits introduce other occurrences, the acceptance-criteria audit in §13 item 1.7 catches them.

### 14.9 MockK + relaxed Project
Tests rely on `mockk<Project>(relaxed = true)`. If any analyzer gains a `Project` method call in a later phase, relaxed defaults (`null`/empty) will silently let tests pass with no real data. Acceptable in Phase 1 because no analyzer touches `Project`; revisit the relaxation setting in Phase 2/5.

### 14.10 `NullSafetyAnalyzer` fixture sensitivity
`NullSafetyAnalyzer` uses regex pattern matching. The positive-fixture code block in §12 is tuned to produce a match given the analyzer's current regex set (`useState(null)` on one line, direct `.name` access on another, no preceding `if (user)`). Do not reformat the fixture whitespace without re-running the test.

### 14.11 Complexity threshold
`ComplexityAnalyzer` currently hardcodes `complexityThreshold = 10`. Our negative fixture uses `complexity = 10` (at threshold, not above). The analyzer's condition is `node.complexity > complexityThreshold`, so `10` is correctly not flagged. Do not change the threshold in Phase 1.

### 14.12 New settings panel field wiring
`JComboBox<String>` with enum `.name` strings is used to avoid `JComboBox<AIProvider>` generic wrangling in Swing. `apply()` parses the name back via `AIProvider.valueOf(...)`. Edge case: if the selection is somehow `null`, the fallback is `"NONE"` (safe).

### 14.13 Windows path separators in fixture tests
Fixture paths like `/src/A.tsx` are forward-slash only. Analyzers do not depend on the OS file system; they iterate `ParsedFile.content`. Safe on Windows CI.

---

## 15. Definition of Done

Phase 1 is **done** when a reviewer can check every box:

- [ ] `./gradlew clean build` exits `0` on a fresh checkout.
- [ ] `./gradlew test` exits `0`; all 18 test methods green.
- [ ] `./gradlew buildPlugin` produces `build/distributions/Aegis Debug-0.1.0.zip`.
- [ ] `./gradlew runIde` opens the sandbox IDE; `idea.log` has no `ERROR` or stack trace attributable to plugin startup.
- [ ] Plugin list in the sandbox shows **Aegis Debug** with the new SVG icon.
- [ ] Tool window titled **Aegis Debug** appears on the right anchor.
- [ ] Menu path `Tools → Aegis Debug` is present and populated.
- [ ] Settings → Tools → **Aegis Debug** shows the §10.3 form with `AI Provider` default = `NONE`.
- [ ] Typing, saving, closing, and reopening the settings dialog preserves all field values.
- [ ] Typing a negative number into `Max files to analyze`, clicking `Apply`, then reopening shows `500` (validation applied).
- [ ] Webview `<title>` is `Aegis Debug NeuroMap`.
- [ ] Webview status bar shows `Aegis Debug` (no emoji).
- [ ] `grep -ri "GhostDebugger" src/main/kotlin` matches only the retained internal identifiers enumerated in §3 and §5 (class names, package names, storage file name, service name, tool-window id, notification group id).
- [ ] `grep -ri "👻" src/main/kotlin` returns no matches **except** inside `plugin.xml` if any legacy emoji survived — there must be **zero** matches in Kotlin or the webview source tree.
- [ ] `grep -ri "GhostDebugger" webview/src` returns no matches.
- [ ] Source file `src/main/resources/icons/aegis.svg` exists and renders as a cream shield with two nodes at 13×13.
- [ ] `src/test/kotlin/com/ghostdebugger/settings/GhostDebuggerSettingsTest.kt` contains the six methods named in §12.3.
- [ ] `Analyzer.kt` declares `ruleId`, `defaultSeverity`, `description`.
- [ ] Every class in `src/main/kotlin/com/ghostdebugger/analysis/analyzers/` implementing `Analyzer` declares those three members with the exact values in §7.4.
- [ ] `AnalyzerContractTest` passes both methods.
- [ ] `ApiKeyManager.getApiKey()` on a beta upgrade path returns the user's previously stored OpenAI key.
- [ ] `ghostdebugger.xml` on a beta upgrade path retains the user's previously stored `openAiModel`, `maxFilesToAnalyze`, and `autoAnalyzeOnOpen` values.
- [ ] This spec document is committed alongside the implementation in the same PR.

When every box is checked, Phase 1 ships. Phase 2 (static-first engine, file-cap enforcement, issue fingerprint merge, provider fallback status events) can then begin without rework.
