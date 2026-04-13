# 📝 Proposed Architecture, Product, and Identity Overhaul — V2

**Objective:** Transition the debugger from a purely cloud-dependent (OpenAI-first) platform into a robust **Hybrid Analysis Engine** built around **static analysis first**, with **optional local or cloud AI** as a secondary layer for contextual reasoning and summarization. In parallel, overhaul the UI from a neon/glassmorphism style into an **enterprise-grade experience** inspired by Carbon-style design principles and a **Dark Navy + Cream** palette. Finally, align the product identity with the new architecture through a full **brand evolution**.

> **Working recommendation:** proceed with the identity overhaul now. Since the project has minimal market awareness, there is little downside in renaming before broader distribution.

---

## 1. Configuration & Settings Overhaul

**Current State:** Settings are centered around a single cloud model choice and basic cache controls.

**Proposed Change:** Introduce a unified provider configuration layer that supports:
- **Static-only mode** (no AI)
- **Local AI** (Ollama)
- **Cloud AI** (OpenAI)

This makes the plugin privacy-first by default and avoids assuming that AI is always available.

### Design Principles
- Add an explicit **`NONE`** provider for fully offline / static-only analysis.
- Do **not** manually instantiate IntelliJ services as fallbacks.
- Expose a **safe settings snapshot** instead of reading mutable internal state directly.
- Validate all configuration inputs before use.
- Store secrets (e.g. API keys) in the IDE credential store, **not** in `ghostdebugger.xml`.

### File to Update
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
        val next = myState.copy().apply(transform).validate()
        myState = next
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

### Additional Settings Notes
- **OpenAI API keys** should be read from secure storage or the IDE credential store.
- If `aiProvider == NONE`, the plugin must run entirely in **Static Mode**.
- Consider later adding:
  - `aiTimeoutMs`
  - `maxAiFiles`
  - `allowCloudUpload`
  - `analyzeOnlyChangedFiles`

---

## 2. Hybrid Analysis Pipeline

**Current State:** The AI analyzer is attempted first, and static analysis is treated as fallback.

**Proposed Change:** Invert the pipeline:
1. **Static pass first** for deterministic findings.
2. **AI pass second** only when enabled and available.
3. AI should focus on:
   - missed issues
   - multi-file reasoning
   - architectural context
   - human-readable summaries

This is the correct trust model for a debugger plugin: deterministic analysis is the baseline; AI is optional augmentation.

### Key Corrections to the V2 Design
- AI execution must be **conditional**, not automatic.
- `maxFilesToAnalyze` must be enforced **before** analysis begins.
- AI fallback behavior must be explicit and visible in the UI.
- Deduplication should preserve **provenance**, not simply discard overlaps.
- Health score should remain labeled as a **heuristic score**.

### File to Update
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

        // 3) Merge issues by fingerprint while preserving source/provider data
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

### Implementation Notes
- `limitTo(...)` should prioritize files by relevance (open file, changed file, recent file, hotspot) if the project exceeds the cap.
- `AIServiceFactory.create(settings)` should throw a clear typed exception if the provider is unavailable; the engine should catch it and continue in static mode.
- Static analyzers may later be parallelized, but correctness and determinism matter more than concurrency in V1.

---

## 3. Hybrid Code Suggestion Logic

**Proposed Change:** Introduce a two-tier fix generator.

### Tier 1 — Structural Fixes (Deterministic)
If the issue is deterministic (e.g. a missing null-check, obvious initialization bug, or straightforward API misuse), generate the code change through IntelliJ’s native PSI/AST APIs.

### Tier 2 — Contextual Explanation (AI Optional)
Send the engine’s proposed structural fix to the active LLM only to:
- explain the change in plain English
- describe architectural implications
- warn about possible side effects
- suggest additional review areas

### Important Correction
The LLM should **not** be treated as the final authority for safety. The trust order should be:
1. **PSI / AST correctness**
2. **Compiler / inspections / test feedback**
3. **LLM explanation and architectural commentary**

### Product Positioning
- Deterministic changes: **Engine Verified**
- AI-authored or AI-expanded suggestions: **AI Suggestion — Review Required**

This keeps trust high and avoids overselling AI certainty.

---

## 4. UI Overhaul (Dark Navy & Cream Enterprise Theme)

**Current State:** The current design language is neon-heavy with glassmorphism and visual glow effects.

**Proposed Change:** Move to a disciplined enterprise style built around:
- solid surfaces
- high contrast
- strict spacing and grid logic
- sharp geometry with limited radius
- strong focus states
- dark navy + cream color palette
- IBM Plex typography

### Important Correction
Do **not** load fonts from Google Fonts at runtime inside the plugin. Bundle the required fonts locally with the plugin to preserve privacy, offline support, and consistent rendering.

### File to Update
`docs/DESIGN_SYSTEM.md` and corresponding CSS files.

```css
/* Fonts must be bundled locally with the plugin, not imported from remote CDNs. */

:root {
  /* Backgrounds */
  --bg-primary: #0A1128;
  --bg-secondary: #14213D;
  --bg-tertiary: #1E315B;
  --bg-overlay: #101A32;

  /* Brand / Accent */
  --accent-primary: #FDFBF7;
  --accent-secondary: #E5E0D0;

  /* Status */
  --status-error: #D62828;
  --status-warning: #F77F00;
  --status-success: #248232;
  --status-info: #4EA8DE;

  /* Text */
  --text-primary: #FDFBF7;
  --text-secondary: #B0BACC;
  --text-muted: #6B7A99;

  /* Borders */
  --border-default: #22365F;
  --border-strong: #FDFBF7;
  --focus-ring: #FDFBF7;

  /* Shape */
  --radius-sm: 0px;
  --radius-md: 2px;
  --radius-lg: 4px;

  /* Spacing */
  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-5: 24px;
  --space-6: 32px;
}

body {
  font-family: 'IBM Plex Sans', sans-serif;
  background-color: var(--bg-primary);
  color: var(--text-primary);
}

code,
.node-code {
  font-family: 'IBM Plex Mono', monospace;
}
```

### Visual Component Rules Update
- **NeuroMap Nodes**
  - Use solid `--bg-secondary` backgrounds.
  - Remove all glow, blur, and pulse animations.
  - Use a **4px left status rail** to indicate severity.
  - Use a cream border for selected nodes.
  - Keep keyboard focus highly visible.

- **Buttons (“Fix it”)**
  - Solid cream fill (`#FDFBF7`)
  - Deep navy text (`#0A1128`)
  - Radius `0px`
  - Hover darkens to `#E5E0D0`
  - No vertical movement animation

- **Issue Cards**
  - Opaque surfaces only
  - Clear heading hierarchy
  - Compact badges for source/provider
  - Visible distinction between Engine fixes and AI suggestions

### UX Constraint
If parts of the plugin use native IntelliJ UI and other parts use JCEF, keep spacing, controls, and interaction patterns consistent enough that the product feels integrated rather than visually detached.

---

## 5. Data Model Updates: Track Source, Provider, and Confidence

**Proposed Change:** Add strongly typed provenance fields to the data model.

### Why This Matters
Users need to know:
- whether a finding came from static analysis or AI
- which provider generated it
- how trustworthy the result is
- whether overlapping findings were corroborated by multiple engines

### Recommended Model Changes
Use explicit enums and structured metadata instead of raw strings.

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

### Frontend Badge Rules
- **Engine Verified** → deterministic PSI-based fix
- **AI Suggestion — Review Required** → model-authored or model-expanded guidance
- Optional secondary badge examples:
  - `Static`
  - `Ollama · llama3`
  - `OpenAI · gpt-4o`

### Important Correction
Avoid labels like **“100% Confident”** unless you have hard validation behind them (e.g. successful PSI transformation, syntax validity, optional test/lint pass). Prefer **Engine Verified** or **Deterministic Fix**.

---

## 6. Bridge Updates: Engine Status & Graceful Fallbacks

**Proposed Change:** The UI must be aware of the backend engine state at all times.

This is necessary because hybrid AI systems can degrade in ways users need to understand:
- provider disabled
- local model offline
- cloud credential missing
- timeout
- fallback to static mode

### Design Change
Use **typed status payloads**, not arbitrary strings.

### Kotlin Bridge API
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
    // serialize and emit to the frontend
}
```

### TypeScript Bridge API
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

### UI Implementation
Update `StatusBar.tsx` to show:
- active engine
- current status
- optional latency / degraded state
- static fallback when AI is unavailable

Examples:
- `● Static Mode`
- `● Ollama (Local) — Online`
- `● OpenAI — Offline, falling back to Static`

---

## 7. MVP Scope & Pitch Updates

**Proposed Change:** Focus the V1 around privacy, trust, and execution speed.

### Core Product Positioning
> An enterprise-grade, privacy-first debugging engine that uses static analysis for deterministic fixes and optional local/cloud AI for deep architectural context.

### MVP Priorities
- Static-first issue detection
- Deterministic code fixes via PSI where possible
- Optional Ollama / OpenAI deep analysis
- Provenance badges on findings and fixes
- Engine status + graceful fallback UX
- Visual graph-based debugging workflow

### Scope Reduction
Cut **WhatIfChat** from V1.

Rationale:
- high implementation complexity inside JCEF
- higher risk of context drift and UI instability
- weakens focus on the core differentiator
- harder to ship reliably under hackathon / MVP constraints

### Updated Pitch
> **A privacy-first debugging engine combining blazing-fast static heuristics with optional local or cloud AI. Detect deterministic bugs fast, surface architectural logic flaws visually, and keep proprietary code local unless a cloud provider is explicitly enabled.**

### Messaging Guardrail
Avoid making unconditional claims like “your code never leaves your machine” unless the product technically enforces that default and clearly indicates when cloud AI is enabled. Prefer wording such as:

> **By default, analysis can run in Static Mode or with local models, and code is only sent to a cloud provider when the user explicitly enables one.**

---

## 8. Identity, Branding, and Naming Overhaul

**Current State:** The existing name and literal ghost icon create a more casual, indie impression than the new architecture and UI now communicate.

**Decision:** Proceed with the identity overhaul now.

Because project awareness is still minimal, now is the ideal time to align the brand with the real product direction: enterprise-grade, privacy-first, and architecture-aware.

### Brand Strategy
The new identity should communicate:
- security
- speed
- control
- architectural intelligence
- local-first trust

### Recommended Name
## **Aegis Debug**

This is the strongest match for the new positioning because:
- **Aegis** implies defense, protection, and privacy
- **Debug** keeps the product category immediately legible
- it sounds credible in enterprise and developer tooling contexts
- it pairs naturally with the shield-like visual direction

### Alternate Candidates
- **Specter Engine** — strong infrastructure tone, keeps some continuity with the original concept
- **Nexus Flow** — emphasizes connected architecture and graph intelligence
- **Phantom Code** — sleek and technical, but slightly less privacy-forward than Aegis Debug

### Logo & Iconography Redesign
- Replace the cartoon ghost with an abstract geometric mark.
- Ideal motif: **shield + node graph hybrid**.
- Primary mark in cream (`#FDFBF7`) on deep navy (`#0A1128`).
- Wordmark in **IBM Plex Sans SemiBold**.
- Icon should remain recognizable at plugin marketplace / IDE toolbar sizes.

### Brand System
- **Primary Background:** `#0A1128`
- **Primary Mark / Text Accent:** `#FDFBF7`
- **Secondary Accent:** `#E5E0D0`
- **Status colors:** same as the product UI

### Taglines
- **Primary:** `Enterprise-grade debugging. Zero privacy compromises.`
- **Secondary:** `The hybrid AI debugger that runs on your terms.`
- **Developer-facing pitch:** `Static-first debugging with local-first AI.`

### 5-Second Pitch
> **Aegis Debug is a privacy-first debugging engine that combines deterministic static analysis with optional local or cloud AI to surface logic flaws visually and help fix them safely.**

---

## 9. Recommended Implementation Priorities

### Phase 1 — Core Trust Model
1. Add `AIProvider.NONE`
2. Refactor settings service access
3. Implement static-first pipeline
4. Add conditional AI execution + fallback handling
5. Enforce `maxFilesToAnalyze`

### Phase 2 — Provenance & UI Trust
6. Add `IssueSource`, `EngineProvider`, confidence metadata
7. Merge issues by fingerprint while preserving provenance
8. Add engine status events through the bridge
9. Update issue cards, fix badges, and status bar

### Phase 3 — Design & Identity
10. Replace neon/glass UI with enterprise theme
11. Bundle IBM Plex fonts locally
12. Remove glow/pulse effects and adopt solid surfaces
13. Rename product to **Aegis Debug**
14. Replace ghost icon with shield/node mark

### Phase 4 — Polish
15. Improve health score methodology
16. Prioritize AI on hotspots / changed files for large repos
17. Add optional validation pipeline for deterministic fixes (lint/tests where available)

---

## 10. Final Product Principle

**Static analysis is the foundation. AI is the amplifier. Trust is the product.**

## 11. Commercial V1 Upgrades (Beyond MVP)

**Objective:** With the removal of hackathon time constraints, the focus shifts from visual approximations to engineering a genuinely robust, production-ready developer tool. The following architectural upgrades elevate Aegis Debug from a proof-of-concept to a commercially viable V1.

### A. Context-Aware "What-If" Engine (Local RAG)
Instead of a simple generic chat interface, implement a specialized Retrieval-Augmented Generation (RAG) system tailored for code architecture.
* **Mechanism:** When a user asks an architectural question (e.g., "What happens if I replace this Auth hook?"), the engine queries the `InMemoryGraph` to retrieve the target node and its entire dependency tree.
* **Execution:** This exact structural context is injected into the LLM prompt. This ensures the AI's answer is strictly grounded in the user's actual codebase, drastically reducing hallucinations.

### B. Scalable PSI/AST Fix Engine
Move away from hardcoded string replacements and build a robust syntax-tree manipulator.
* **Mechanism:** Create a `StructuralFixProvider` interface. When a static analyzer flags a deterministic issue (like a missing null-check), the provider uses IntelliJ's native PSI (Program Structure Interface) to modify the AST safely.
* **V1 Scope:** Select **one** primary ecosystem (e.g., TypeScript/React) to perfectly map out first. Guarantee that Tier 1 fixes respect the user's formatting, imports, and scope boundaries before expanding to other languages.

### C. Local Persistence & Trend Analysis
Enable the tool to track project health across multiple sessions.
* **Mechanism:** Introduce a lightweight embedded database (like SQLite) or utilize IntelliJ's advanced `PersistentStateComponent` to store graph snapshots and analysis runs.
* **UI Impact:** The Dashboard can now display "Delta Metrics" (e.g., "Health Score improved by +15% this week" or "3 new high-risk nodes introduced today"), transforming Aegis from a one-off scanner into a continuous monitoring tool.

### D. Type-Safe Bi-Directional Event Bus
Replace the brittle, raw-string JSON execution over the JCEF bridge with a robust communication layer.
* **Mechanism:** Implement a structured message queue between Kotlin and TypeScript. 
* **Benefits:** If the UI requests an action while the static engine is heavily processing, or if a payload fails to parse, the system handles the state gracefully (queuing or error-catching) rather than failing silently or hanging the UI.

### E. Foundational Test Coverage
The accuracy of the hybrid model completely depends on the reliability of the static base layer. If the static engine feeds incorrect data to the AI, the AI will hallucinate.
* **Mechanism:** Build a comprehensive unit testing suite specifically for the `AnalysisEngine` and individual `Analyzers` (e.g., `NullSafetyAnalyzer`, `CircularDependencyAnalyzer`). 
* **Standard:** Ensure 100% deterministic accuracy on known mock-projects before relying on the LLM to explain the findings.

That principle should guide all implementation decisions across architecture, UX, messaging, and branding.
