# 📝 Aegis Debug — True V1 Product Specification

**Version:** V1 Canonical Build Spec  
**Status:** Ready for implementation planning  
**Product Name:** **Aegis Debug**  
**Previous Working Name:** GhostDebugger  

---

## 0. Executive Summary

**Aegis Debug** is a privacy-first IntelliJ plugin for visual code debugging and issue remediation. Its V1 architecture is based on a simple trust hierarchy:

1. **Static analysis is the foundation** for deterministic findings.
2. **Deterministic code fixes** are generated through IntelliJ PSI/AST tooling where possible.
3. **Optional AI providers** (local Ollama or cloud OpenAI) act as a secondary reasoning layer for contextual analysis, missed-issue discovery, and human-readable explanation.
4. **Trust and provenance are surfaced in the UI** so the user always knows what came from the engine vs AI.

This document upgrades the earlier V2 direction spec into a **true V1 build specification** by adding:
- hard scope boundaries
- acceptance criteria
- supported platform/language boundaries
- performance targets
- privacy and security rules
- fix validation rules
- observability requirements
- test strategy
- release criteria

> **Core product principle:** **Static analysis is the foundation. AI is the amplifier. Trust is the product.**

---

## 1. Product Goal

### Primary Goal
Help developers detect and fix high-confidence bugs and architectural issues inside IntelliJ-based IDEs with a workflow that is:
- fast
- privacy-conscious
- visually understandable
- explicit about confidence and provenance

### User Promise
A developer should be able to:
1. run a scan on a project or relevant file subset,
2. see deterministic issues first,
3. optionally enrich results with local or cloud AI,
4. inspect trust badges and engine status,
5. preview fixes before applying them,
6. safely apply deterministic fixes when supported.

### Non-Goals for V1
The following are **explicitly out of scope** for V1:
- freeform conversational chat inside the plugin
- autonomous large-scale code rewrites
- multi-step agentic refactoring workflows
- support for every IntelliJ language/framework from day one
- claiming complete program correctness or full safety verification

---

## 2. V1 Scope Boundary

This section defines what **must** ship in V1 and what is deferred.

### 2.1 Must-Have V1 Capabilities

#### A. Core Analysis
- Static-first analysis pipeline
- Explicit provider modes:
  - `NONE`
  - `OLLAMA`
  - `OPENAI`
- Graceful fallback to static mode when AI is unavailable
- File-limit enforcement before analysis begins
- Result deduplication/merging with provenance preservation

#### B. Findings and Trust
- Issue list/cards with severity and explanation
- Provenance metadata:
  - source
  - provider
  - confidence (optional)
- Engine status indicator in UI
- Human-readable distinction between:
  - deterministic engine findings/fixes
  - AI-generated suggestions

#### C. Fix Workflow
- Deterministic PSI-based fixes for a limited set of issue classes
- Diff preview before applying a fix
- Undo/rollback using IDE-native mechanisms
- Re-analysis after fix application

#### D. UI
- Enterprise visual theme (Dark Navy + Cream)
- Solid surfaces, high contrast, clear status indicators
- Local font bundling for IBM Plex family
- Accessible selected/focused states

#### E. Privacy Controls
- Static-only mode must work with no AI provider configured
- Cloud processing must require explicit provider selection/configuration
- Secret storage must use secure credential mechanisms

### 2.2 Deferred / Post-V1
- WhatIfChat / conversational graph chat
- large-scale architectural refactor generation
- autonomous multi-file code editing
- advanced repo-wide background continuous monitoring
- broad multi-language parity beyond the initial supported scope
- advanced scoring models beyond heuristic health score
- telemetry dashboards and external team analytics

---

## 3. Supported Scope for V1

### 3.1 Supported IDE Platform
V1 targets **IntelliJ Platform IDEs** through a plugin implementation.

### 3.2 Supported Languages
V1 should have **one clearly prioritized language target** and one optional secondary target.

#### Recommended V1 Language Strategy
- **Primary supported language:** Kotlin
- **Secondary supported language:** Java (only where analyzers and PSI fixers are explicitly validated)

If broader support is desired later, it must be treated as a post-V1 expansion unless there is full analyzer + PSI + QA coverage.

### 3.3 Supported Repo Size Guidance
V1 is designed for:
- small repos
- medium repos
- selected file subsets within large repos

For very large repos, V1 should prioritize:
- changed files
- currently open files
- recently touched files
- hotspot files

### 3.4 Explicitly Unsupported in V1 Unless Individually Implemented
- framework-specific deep analysis without dedicated rules
- languages without validated PSI fixers
- autonomous cross-repo migrations
- guaranteed correctness for runtime-only or environment-dependent issues

---

## 4. V1 User Experience Definition

### Primary User Flow
1. User opens the plugin in an IntelliJ-based IDE.
2. User chooses analysis mode or uses default settings.
3. Static analysis starts first.
4. If AI is enabled and available, optional deep analysis runs next.
5. User sees findings with source/provider badges.
6. User opens an issue card or graph node.
7. User inspects explanation, severity, provenance, and suggested fix.
8. If fix is deterministic and supported, user opens diff preview.
9. User applies fix.
10. Plugin re-runs targeted validation/re-analysis.

### Required Trust Signals
The UI must always communicate:
- active engine/provider
- engine status
- whether a finding came from static analysis or AI
- whether a fix is deterministic or advisory
- when fallback to static mode occurred

### Required Badges
- **Engine Verified**
- **AI Suggestion — Review Required**
- Optional provider badges:
  - `Static`
  - `Ollama · <model>`
  - `OpenAI · <model>`

---

## 5. Configuration & Settings Specification

### Objective
Support safe provider configuration while preserving offline-first behavior.

### V1 Configuration Requirements
- `AIProvider.NONE` must exist and be functional
- Settings must be persisted via IntelliJ persistent state
- Settings reads for analysis must use a **safe snapshot**
- Invalid settings values must be normalized/validated
- API keys must **not** be stored in the plugin XML settings file

### File
`src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt`

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
        var autoAnalyzeOnOpen: Boolean = false,
        var showInfoIssues: Boolean = true,
        var cacheEnabled: Boolean = true,
        var cacheTtlSeconds: Long = 3600
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
        if (cacheTtlSeconds < 0) cacheTtlSeconds = 0
        if (ollamaEndpoint.isBlank()) ollamaEndpoint = "http://localhost:11434"
        return this
    }

    companion object {
        fun getInstance(): GhostDebuggerSettings =
            ApplicationManager.getApplication().getService(GhostDebuggerSettings::class.java)
    }
}
```

### Recommended Additional V1 Flags
- `aiTimeoutMs`
- `allowCloudUpload`
- `analyzeOnlyChangedFiles`
- `maxAiFiles`

---

## 6. Analysis Engine Specification

### Core Rule
The analysis pipeline must always run in this order:
1. static pass
2. optional AI pass
3. issue merge
4. metrics/hotspots/risk derivation

### V1 Requirements
- Static analysis must always complete even if AI fails.
- AI invocation must be conditional.
- File cap must be applied before expensive work starts.
- Analyzer failure must not crash the whole scan.
- Fallback behavior must emit an engine status event.

### File
`src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt`

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.ai.AIServiceFactory
import com.ghostdebugger.analysis.analyzers.*
import com.ghostdebugger.model.*
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.openapi.diagnostic.logger

class AnalysisEngine {
    private val log = logger<AnalysisEngine>()

    private val analyzers: List<Analyzer> = listOf(
        NullSafetyAnalyzer(),
        StateInitAnalyzer(),
        AsyncFlowAnalyzer(),
        CircularDependencyAnalyzer(),
        ComplexityAnalyzer()
    )

    suspend fun analyze(context: AnalysisContext): AnalysisResult {
        val settings = GhostDebuggerSettings.getInstance().snapshot()
        val limitedContext = context.limitTo(settings.maxFilesToAnalyze)
        val allIssues = mutableListOf<Issue>()

        // 1) Static pass first
        log.info("Running static analysis engine...")
        for (analyzer in analyzers) {
            try {
                allIssues += analyzer.analyze(limitedContext)
            } catch (e: Exception) {
                log.warn("Analyzer ${analyzer.name} failed", e)
            }
        }

        // 2) Optional AI pass
        val aiIssues = when (settings.aiProvider) {
            AIProvider.NONE -> {
                log.info("AI disabled. Running in Static Mode.")
                emptyList()
            }
            else -> runCatching {
                val aiService = AIServiceFactory.create(settings)
                log.info("Running deep analysis with provider: ${settings.aiProvider}")
                aiService.detectMissedIssues(limitedContext, allIssues)
            }.onFailure { e ->
                log.warn("AI analysis unavailable. Falling back to Static Mode.", e)
            }.getOrDefault(emptyList())
        }

        allIssues += aiIssues
        val mergedIssues = mergeIssues(allIssues)

        val metrics = ProjectMetrics(
            totalFiles = limitedContext.parsedFiles.size,
            totalIssues = mergedIssues.size,
            errorCount = mergedIssues.count { it.severity == IssueSeverity.ERROR },
            warningCount = mergedIssues.count { it.severity == IssueSeverity.WARNING },
            infoCount = mergedIssues.count { it.severity == IssueSeverity.INFO },
            healthScore = calculateHealthScore(limitedContext.parsedFiles.size, mergedIssues),
            avgComplexity = limitedContext.graph.getAllNodes()
                .map { it.complexity.toDouble() }
                .average()
                .takeIf { !it.isNaN() } ?: 0.0
        )

        val hotspots = mergedIssues
            .groupBy { it.filePath }
            .filter { (_, issues) -> issues.size >= 2 }
            .keys
            .toList()

        val risks = mergedIssues
            .filter { it.severity == IssueSeverity.ERROR }
            .map { RiskItem(nodeId = it.filePath, riskLevel = "HIGH", reason = it.title) }

        return AnalysisResult(
            issues = mergedIssues,
            metrics = metrics,
            hotspots = hotspots,
            risks = risks
        )
    }

    private fun mergeIssues(issues: List<Issue>): List<Issue> {
        return issues
            .groupBy { it.fingerprint() }
            .map { (_, group) ->
                val first = group.first()
                first.copy(
                    sources = group.flatMap { it.sources }.distinct(),
                    providers = group.flatMap { it.providers }.distinct(),
                    confidence = group.mapNotNull { it.confidence }.maxOrNull()
                )
            }
    }

    private fun calculateHealthScore(totalFiles: Int, issues: List<Issue>): Double {
        if (totalFiles == 0) return 100.0

        val density = issues.size.toDouble() / totalFiles.coerceAtLeast(1)
        val errorPenalty = issues.count { it.severity == IssueSeverity.ERROR } * 12.0
        val warningPenalty = issues.count { it.severity == IssueSeverity.WARNING } * 4.0
        val densityPenalty = density * 3.0

        return (100.0 - errorPenalty - warningPenalty - densityPenalty).coerceIn(0.0, 100.0)
    }
}
```

### Mandatory V1 Analyzer Set
V1 must ship with a **limited but reliable** analyzer set. Recommended baseline:
- `NullSafetyAnalyzer`
- `StateInitAnalyzer`
- `AsyncFlowAnalyzer`
- `CircularDependencyAnalyzer`
- `ComplexityAnalyzer`

### Mandatory V1 Scope Rule
No analyzer ships in V1 unless it has:
- a documented rule definition
- at least one positive test case
- at least one negative test case
- a severity mapping

---

## 7. Issue Model, Provenance, and Deduplication

### Objective
Ensure every finding communicates where it came from and how it should be interpreted.

### Requirements
- Provenance must be strongly typed.
- Deduplication must preserve evidence from multiple sources.
- Fingerprints must be stable across repeated scans where possible.

### File
`src/main/kotlin/com/ghostdebugger/model/*`

```kotlin
package com.ghostdebugger.model

enum class IssueSource {
    STATIC,
    AI_LOCAL,
    AI_CLOUD
}

enum class EngineProvider {
    STATIC,
    OLLAMA,
    OPENAI
}

data class Issue(
    val id: String,
    val type: String,
    val title: String,
    val description: String,
    val filePath: String,
    val line: Int,
    val severity: IssueSeverity,
    val sources: List<IssueSource> = listOf(IssueSource.STATIC),
    val providers: List<EngineProvider> = listOf(EngineProvider.STATIC),
    val confidence: Double? = null,
    val ruleId: String? = null
) {
    fun fingerprint(): String = listOf(ruleId ?: type, filePath, line.toString()).joinToString(":")
}

data class CodeFix(
    val id: String,
    val issueId: String,
    val title: String,
    val diffPreview: String,
    val provider: EngineProvider,
    val deterministic: Boolean,
    val confidence: Double? = null,
    val explanation: String? = null
)
```

### Badge Language Rules
- **Engine Verified** = deterministic PSI-generated fix
- **AI Suggestion — Review Required** = advisory or AI-generated guidance
- Avoid **“100% confident”** unless hard validation exists

---

## 8. Deterministic Fix Workflow Specification

### Core Rule
Aegis Debug must never apply a fix without a preview path and undo path.

### V1 Fix Types
V1 should support only a small set of deterministic fixes that are highly reliable.

#### Recommended V1 Deterministic Fix Classes
- add missing null-check
- safe initialization/default assignment where rule logic is explicit
- obvious guard clause insertion
- small API misuse corrections where PSI transformation is deterministic

### Fix Pipeline
1. Engine identifies a deterministic issue.
2. PSI-based fixer generates a candidate modification.
3. Plugin shows a diff preview.
4. User explicitly accepts.
5. Plugin applies fix through proper write action / IDE-safe mechanism.
6. Plugin re-runs targeted analysis/validation.
7. User can undo via native IDE undo stack.

### Required Safety Checks for V1
Before a deterministic fix is shown as **Engine Verified**, the system must at minimum ensure:
- transformation completed successfully
- PSI tree remained valid
- resulting code is syntactically valid in the file context

### Recommended Additional Validation
If available, optionally run:
- IntelliJ inspections on affected file/scope
- local compilation/checking where practical
- targeted test execution in future versions

### Important Trust Rule
The LLM may explain or contextualize a fix, but it must **not** be treated as the authority that declares a change safe.

---

## 9. AI Usage Specification

### AI Role in V1
AI is a **secondary reasoning layer** only.

### Allowed AI Responsibilities
- detect potential missed issues after static analysis
- explain engine-generated fixes in natural language
- summarize architectural concerns
- suggest extra review areas

### Disallowed AI Responsibilities in V1
- silently editing files without deterministic engine support
- replacing deterministic fix generation for supported rules
- claiming verified safety on its own
- autonomous repo-wide rewrite decisions

### Provider Modes
- `NONE` → static-only mode
- `OLLAMA` → local model mode
- `OPENAI` → cloud mode

### Provider Fallback Rule
If the selected AI provider is unavailable, V1 must:
- continue in static mode
- emit status to the UI
- avoid blocking or crashing the analysis workflow

---

## 10. Bridge and Engine Status Specification

### Objective
Expose backend status clearly to the frontend.

### Required Status States
- `ONLINE`
- `OFFLINE`
- `DEGRADED`
- `FALLBACK_TO_STATIC`
- `DISABLED`

### Kotlin Bridge Contract
`src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt`

```kotlin
package com.ghostdebugger.bridge

enum class EngineStatus {
    ONLINE,
    OFFLINE,
    DEGRADED,
    FALLBACK_TO_STATIC,
    DISABLED
}

data class EngineStatusPayload(
    val provider: String,
    val status: EngineStatus,
    val message: String? = null,
    val latencyMs: Long? = null
)

fun sendEngineStatus(payload: EngineStatusPayload) {
    // serialize and emit to frontend
}
```

### TypeScript Bridge Contract
`src/webview/pluginBridge.ts`

```ts
export type EngineStatus =
  | 'ONLINE'
  | 'OFFLINE'
  | 'DEGRADED'
  | 'FALLBACK_TO_STATIC'
  | 'DISABLED';

export interface EngineStatusPayload {
  provider: 'STATIC' | 'OLLAMA' | 'OPENAI';
  status: EngineStatus;
  message?: string;
  latencyMs?: number;
}

export interface PluginBridge {
  onEngineStatusUpdate: (data: EngineStatusPayload) => void;
}
```

### UI Requirements
The UI status bar must show:
- active engine
- current status
- fallback state if applicable
- optional latency or warning message

Example states:
- `● Static Mode`
- `● Ollama (Local) — Online`
- `● OpenAI — Offline, falling back to Static`

---

## 11. IntelliJ Platform Constraints and Engineering Rules

Because this is an IntelliJ plugin, V1 must comply with platform-safe execution patterns.

### Mandatory Rules
- analysis must run off the UI thread
- PSI reads/writes must use correct IntelliJ read/write action patterns
- long scans must support cancellation
- scan progress must be visible to the user
- fix application must integrate with IDE undo/redo behavior
- plugin must avoid hard failure during indexing or unsupported IDE states

### Recommended Rules
- avoid JCEF over-rendering on large result sets
- degrade gracefully when graph visualization becomes too expensive
- separate analysis engine lifecycle from UI panel lifecycle

---

## 12. UI and Design System Specification

### Design Direction
Adopt a disciplined enterprise aesthetic inspired by Carbon-style principles while using a custom palette.

### Visual Principles
- no glassmorphism
- no glow effects
- solid backgrounds
- strong contrast
- sharp geometry
- visible focus treatment
- compact, trustworthy information hierarchy

### Color Tokens
```css
:root {
  --bg-primary: #0A1128;
  --bg-secondary: #14213D;
  --bg-tertiary: #1E315B;
  --bg-overlay: #101A32;

  --accent-primary: #FDFBF7;
  --accent-secondary: #E5E0D0;

  --status-error: #D62828;
  --status-warning: #F77F00;
  --status-success: #248232;
  --status-info: #4EA8DE;

  --text-primary: #FDFBF7;
  --text-secondary: #B0BACC;
  --text-muted: #6B7A99;

  --border-default: #22365F;
  --border-strong: #FDFBF7;
  --focus-ring: #FDFBF7;

  --radius-sm: 0px;
  --radius-md: 2px;
  --radius-lg: 4px;

  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-5: 24px;
  --space-6: 32px;
}
```

### Typography Rule
IBM Plex fonts must be **bundled locally** with the plugin. No runtime fetches from Google Fonts or other remote CDNs.

### Component Rules
#### NeuroMap Nodes
- solid `--bg-secondary`
- 4px left status rail
- selected state via cream border
- no pulse or glow animations

#### Buttons
- cream fill
- deep navy text
- no movement on hover
- sharp radius

#### Issue Cards
- opaque surfaces only
- badges for source/provider/fix trust
- clear severity and explanation structure

### Accessibility Requirements
- keyboard navigation support where relevant
- visible focus ring
- sufficient contrast between text/background/status colors
- selected state must not rely only on color if additional affordance is practical

---

## 13. Identity and Branding Specification

### Branding Decision
Proceed with the identity overhaul in V1.

### Official V1 Name
# **Aegis Debug**

### Identity Goals
Communicate:
- privacy
- control
- speed
- architectural intelligence
- enterprise credibility

### Logo Direction
- abstract geometric mark
- shield + node/graph hybrid
- cream mark on deep navy background
- IBM Plex Sans SemiBold wordmark
- works at small toolbar/plugin marketplace sizes

### Core Messaging
- **Primary tagline:** `Enterprise-grade debugging. Zero privacy compromises.`
- **Secondary tagline:** `The hybrid AI debugger that runs on your terms.`
- **Short pitch:** `Static-first debugging with local-first AI.`

### 5-Second Pitch
> **Aegis Debug is a privacy-first debugging engine that combines deterministic static analysis with optional local or cloud AI to surface logic flaws visually and help fix them safely.**

---

## 14. Privacy, Security, and Data Handling Specification

This section is mandatory for V1 because privacy is a core product claim.

### V1 Privacy Rules
- Static Mode must function without cloud access.
- Cloud AI use must require explicit user selection/configuration.
- Secrets must be stored in secure credential mechanisms, not plain plugin state XML.
- The product must clearly indicate when cloud AI is active.
- Fallback to static mode must be visible when cloud/local AI is unavailable.

### Data Handling Rules
For V1, define and implement the following explicitly:
- what code/context is sent to AI providers
- whether uploads are file-level, snippet-level, or issue-context-only
- whether prompts/responses are cached
- whether cached data is stored on disk
- how users disable cache
- whether any telemetry exists

### Recommended V1 Default Policy
- default provider = `NONE`
- default behavior = static-only until user enables AI
- no telemetry by default unless explicitly introduced and documented
- prompt/response caching only if user-enabled and clearly scoped

### Mandatory User Transparency
The UI/settings must make it clear:
- when code may be sent externally
- which provider is active
- whether results came from a local or cloud engine

---

## 15. Performance and Scalability Targets

This section defines V1 engineering expectations.

### Performance Goals
These are recommended implementation targets rather than hard SLA guarantees:
- Static scan of a small project subset should feel near-immediate.
- Medium project scans should remain responsive and cancellable.
- AI augmentation must never freeze the IDE UI.
- Large projects must degrade via file prioritization and caps.

### Required V1 Behaviors
- `maxFilesToAnalyze` must be honored
- progress must be shown for long-running analysis
- user can cancel analysis safely
- AI latency must not block delivery of static findings

### Large Repo Strategy
When file count exceeds limits, prioritize in this order:
1. current file
2. changed files
3. recently opened files
4. hotspot files
5. remaining files up to cap

### Graph Performance Rule
If graph rendering becomes too expensive for a result set, the plugin may fall back to a list/card-first presentation while preserving core functionality.

---

## 16. Observability and Error Handling Specification

### Objective
Make failures diagnosable without breaking user trust.

### Error Categories
V1 should categorize failures at minimum into:
- analyzer failure
- parsing failure
- AI provider unavailable
- AI timeout
- bridge serialization/event failure
- fix generation failure
- fix application failure
- validation/re-analysis failure

### Logging Rules
- internal logs should be detailed enough for debugging
- user-facing messages should be concise and actionable
- AI/provider failures should not appear as static-engine failures

### User-Facing Error Principles
- explain fallback when it occurs
- do not expose raw stack traces in normal UI
- preserve partial results whenever possible

---

## 17. Test Strategy

This section is mandatory for V1.

### 17.1 Unit Testing
Must include:
- analyzer rule tests
- issue merge/fingerprint tests
- settings validation tests
- provider selection/fallback tests

### 17.2 PSI / Fix Testing
Must include:
- deterministic fix generation tests
- syntax-validity verification after fix generation
- apply/undo behavior tests where feasible
- regression fixtures for each supported fix class

### 17.3 Integration Testing
Must include:
- end-to-end static scan flow
- AI unavailable → fallback to static flow
- engine status bridge event propagation
- diff preview and fix apply flow

### 17.4 UI / Frontend Testing
Must include:
- issue badge rendering
- engine status rendering
- fallback message rendering
- keyboard/focus behavior for critical screens

### 17.5 Fixture Repositories
Maintain a small set of representative fixture repos or code fixtures for:
- positive analyzer cases
- false-positive prevention cases
- supported fix cases
- large-ish project behavior tests

---

## 18. Acceptance Criteria

A capability is not considered V1-complete until its acceptance criteria pass.

### 18.1 Core Analysis Acceptance Criteria
- Static analysis runs successfully with `AIProvider.NONE`.
- AI provider failure does not block static results.
- Analyzer exceptions do not crash the full scan.
- File caps are enforced before analysis expands beyond the configured threshold.

### 18.2 Provenance Acceptance Criteria
- Every issue visible in the UI includes source/provider metadata.
- Merged issues preserve evidence from multiple engines/providers.
- Fallback states are visible in the status UI.

### 18.3 Fix Acceptance Criteria
- Supported deterministic fixes always show a diff preview before apply.
- Fix application is undoable.
- After applying a deterministic fix, targeted re-analysis occurs.
- Engine Verified labeling is only used when deterministic validation passes.

### 18.4 Privacy Acceptance Criteria
- Static mode works without internet/cloud configuration.
- Cloud mode requires explicit user enablement.
- API keys are not persisted to plain settings XML.
- The UI clearly indicates when a cloud provider is active.

### 18.5 UX Acceptance Criteria
- The plugin remains responsive during scans.
- Long-running scans can be canceled.
- Issue list/cards remain usable even if graph view is degraded or unavailable.
- Focus/selected states are visible in the UI.

### 18.6 Branding Acceptance Criteria
- Plugin name, iconography, and UI language consistently use **Aegis Debug**.
- The legacy ghost motif is removed from primary UI surfaces and store assets.

---

## 19. Release Strategy

### Release Stages
#### Internal Alpha
- core analyzers validated
- deterministic fixes validated for supported classes
- static-only mode stable

#### Private Beta
- Ollama integration enabled
- OpenAI integration optionally enabled
- provenance badges and engine status stable
- early-user feedback on trust and fix workflow collected

#### V1 General Availability Criteria
V1 is eligible for general release when:
- acceptance criteria pass
- no critical crashers remain in normal scan/fix workflows
- deterministic fixes are stable for the supported issue classes
- static-only mode is reliable
- fallback behavior is proven under provider failure scenarios

### First User Profile
Ideal early users:
- developers in IntelliJ-based environments
- privacy-sensitive solo developers or small teams
- users willing to begin with Kotlin/Java-centric workflows

---

## 20. Recommended Implementation Plan

### Phase 1 — Foundations
1. Finalize naming and branding assets
2. Implement settings refactor and secure secret handling
3. Add `AIProvider.NONE`
4. Establish analyzer contracts and baseline test fixtures

### Phase 2 — Core Engine
5. Implement static-first pipeline
6. Implement file limiting and prioritization
7. Add issue fingerprint merge logic with provenance preservation
8. Add structured provider fallback handling

### Phase 3 — Fix Trust Model
9. Implement first deterministic PSI fixes
10. Add diff preview + undo-safe apply flow
11. Add targeted post-fix validation/re-analysis
12. Add trust badges and confidence labeling rules

### Phase 4 — UI + Bridge
13. Implement engine status bridge
14. Update status bar and issue card trust surfaces
15. Implement enterprise design system in JCEF UI
16. Bundle IBM Plex fonts locally

### Phase 5 — AI Augmentation
17. Add Ollama support
18. Add OpenAI support
19. Add AI explanation layer for supported deterministic fixes
20. Add AI missed-issue pass with graceful fallback

### Phase 6 — Hardening
21. Performance testing on small/medium/large fixtures
22. Fix false positives / false negatives on analyzers
23. Polish fallback states and user messaging
24. Freeze V1 scope and prepare release assets

---

## 21. Final V1 Decision Rules

If a feature threatens the trust model, it does **not** belong in V1.

### V1 Priority Order
1. correctness of deterministic analysis
2. safety and clarity of fix workflow
3. privacy and transparent provider behavior
4. responsive UX
5. AI augmentation quality
6. visual polish beyond core trust surfaces

### Final Rule
When in doubt, prefer:
- fewer analyzers with higher precision
- fewer fix classes with higher safety
- clearer trust signals over more automation
- graceful degradation over brittle intelligence

---

## 22. Canonical V1 Statement

**Aegis Debug V1 is a privacy-first IntelliJ plugin that provides static-first code analysis, a limited set of deterministic PSI-based fixes, optional local/cloud AI augmentation, explicit provenance badges, visible engine status, and an enterprise-grade visual workflow built around trust and clarity.**
