```markdown
# 📝 Proposed Architecture and Design Overhaul — GhostDebugger V2

**Objective:** Transition GhostDebugger from a purely cloud-dependent (OpenAI) platform to a robust Hybrid Analysis Engine (Static + Local/Cloud AI). Additionally, overhaul the UI from its current neon/glassmorphism aesthetic to an enterprise-grade design inspired by IBM's Carbon Design System, utilizing a Dark Navy and Cream color palette.

---

## 1. Configuration & Settings Overhaul

**Current State:** The plugin settings only store the `openAiModel` and basic caching rules.
**Proposed Change:** Introduce a unified AI Provider configuration to support self-hosted, local models (like Ollama) alongside OpenAI.

**File to Update:** `src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt`

```kotlin
package com.ghostdebugger.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class AIProvider { OPENAI, OLLAMA }

@Service
@State(name = "GhostDebuggerSettings", storages = [Storage("ghostdebugger.xml")])
class GhostDebuggerSettings : PersistentStateComponent<GhostDebuggerSettings.State> {

    data class State(
        var aiProvider: AIProvider = AIProvider.OPENAI,
        var openAiModel: String = "gpt-4o",
        var ollamaEndpoint: String = "http://localhost:11434",
        var ollamaModel: String = "llama3",
        var maxFilesToAnalyze: Int = 500,
        var autoAnalyzeOnOpen: Boolean = false,
        var showInfoIssues: Boolean = true,
        var cacheEnabled: Boolean = true,
        var cacheTtlSeconds: Long = 3600
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var aiProvider: AIProvider
        get() = state.aiProvider
        set(value) { state.aiProvider = value }

    var openAiModel: String
        get() = state.openAiModel
        set(value) { state.openAiModel = value }
        
    var ollamaEndpoint: String
        get() = state.ollamaEndpoint
        set(value) { state.ollamaEndpoint = value }
        
    var ollamaModel: String
        get() = state.ollamaModel
        set(value) { state.ollamaModel = value }

    var maxFilesToAnalyze: Int
        get() = state.maxFilesToAnalyze
        set(value) { state.maxFilesToAnalyze = value }

    var autoAnalyzeOnOpen: Boolean
        get() = state.autoAnalyzeOnOpen
        set(value) { state.autoAnalyzeOnOpen = value }

    companion object {
        fun getInstance(): GhostDebuggerSettings {
            return com.intellij.openapi.application.ApplicationManager.getApplication()
                ?.getService(GhostDebuggerSettings::class.java)
                ?: GhostDebuggerSettings()
        }
    }
}
```

---

## 2. Hybrid Analysis Pipeline

**Current State:** `AnalysisEngine.kt` attempts to use the AI Analyzer first and only falls back to the static analyzers.
**Proposed Change:** Invert the pipeline. The static engine must run *first* to catch all deterministic errors (fast and free). The AI (Local or Cloud) should then be used as a secondary pass to detect complex logic flaws and generate human-readable summaries.

**File to Update:** `src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt`

```kotlin
package com.ghostdebugger.analysis

import com.ghostdebugger.analysis.analyzers.*
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.ghostdebugger.ai.AIServiceFactory
import com.ghostdebugger.model.*
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
        val allIssues = mutableListOf<Issue>()
        
        // 1. FAST PASS: Always run static heuristic analyzers first
        log.info("Running static analysis engine...")
        for (analyzer in analyzers) {
            try {
                val issues = analyzer.analyze(context)
                allIssues.addAll(issues)
            } catch (e: Exception) {
                log.warn("Analyzer ${analyzer.name} failed", e)
            }
        }

        // 2. DEEP PASS: Delegate to AI (OpenAI or Ollama) for context/missed issues
        val settings = GhostDebuggerSettings.getInstance().state
        val aiService = AIServiceFactory.create(settings) 
        
        log.info("Running Deep AI scan with provider: ${settings.aiProvider}...")
        try {
            // AI analyzes the graph, aware of the issues already found by the static engine
            val deepIssues = aiService.detectMissedIssues(context, allIssues) 
            allIssues.addAll(deepIssues)
        } catch (e: Exception) {
            log.error("Deep AI Analysis failed", e)
        }

        // 3. Deduplicate and metric calculation
        val deduped = allIssues.distinctBy { Triple(it.filePath, it.line, it.type) }

        val metrics = ProjectMetrics(
            totalFiles = context.parsedFiles.size,
            totalIssues = deduped.size,
            errorCount = deduped.count { it.severity == IssueSeverity.ERROR },
            warningCount = deduped.count { it.severity == IssueSeverity.WARNING },
            infoCount = deduped.count { it.severity == IssueSeverity.INFO },
            healthScore = calculateHealthScore(context.parsedFiles.size, deduped),
            avgComplexity = context.graph.getAllNodes().map { it.complexity.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0
        )

        val hotspots = deduped
            .groupBy { it.filePath }
            .filter { (_, issues) -> issues.size >= 2 }
            .keys
            .toList()

        val risks = deduped
            .filter { it.severity == IssueSeverity.ERROR }
            .map { RiskItem(nodeId = it.filePath, riskLevel = "HIGH", reason = it.title) }

        return AnalysisResult(
            issues = deduped,
            metrics = metrics,
            hotspots = hotspots,
            risks = risks
        )
    }

    private fun calculateHealthScore(totalFiles: Int, issues: List<Issue>): Double {
        if (totalFiles == 0) return 100.0
        val errorPenalty = issues.count { it.severity == IssueSeverity.ERROR } * 15.0
        val warningPenalty = issues.count { it.severity == IssueSeverity.WARNING } * 5.0
        return (100.0 - errorPenalty - warningPenalty).coerceIn(0.0, 100.0)
    }
}
```

---

## 3. Hybrid Code Suggestion Logic

**Proposed Change:** Introduce a two-tier fix generator.
1. **Structural Fixes:** If the issue is deterministic (e.g., a simple missing null-check), use IntelliJ's native PSI to generate the exact AST-based structural fix.
2. **Contextual Explanation:** Send the Engine's proposed structural code change to the active LLM solely to generate the human-readable `EXPLANATION` and verify its safety in the broader architecture.

---

## 4. UI Overhaul (Dark Navy & Cream Enterprise Theme)

**Current State:** The `DESIGN_SYSTEM.md` defines a dark neon theme with glassmorphism and glowing box shadows. 
**Proposed Change:** Migrate to an enterprise-grade logic (inspired by IBM Carbon) but utilizing a customized Dark Navy and Cream palette. Use strict grids, solid opaque backgrounds, sharp edges, high contrast, and IBM Plex typography.

**File to Update:** `docs/DESIGN_SYSTEM.md` (and corresponding CSS files)

```css
@import url('[https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;600&family=IBM+Plex+Mono&display=swap](https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;600&family=IBM+Plex+Mono&display=swap)');

:root {
  /* -- Backgrounds (Dark Blue Theme) -- */
  --bg-primary: #0A1128;         /* Deepest Navy (Main Background) */
  --bg-secondary: #14213D;       /* Rich Dark Blue (Panels/Cards) */
  --bg-tertiary: #1E315B;        /* Muted Blue (Hover states/Elevated) */
  
  /* NO Glassmorphism */
  --bg-glass: transparent;       

  /* -- Accent / Brand (Cream) -- */
  --accent-primary: #FDFBF7;     /* Bright Soft Cream */
  --accent-secondary: #E5E0D0;   /* Muted Cream / Warm Beige */

  /* -- Status Colors (Solid & Flat) -- */
  --status-error: #D62828;       /* Deep Red */
  --status-warning: #F77F00;     /* Warm Orange/Yellow */
  --status-healthy: #248232;     /* Forest Green */
  --status-info: #4EA8DE;        /* Soft Blue */

  /* -- Text -- */
  --text-primary: #FDFBF7;       /* Cream (High Contrast) */
  --text-secondary: #B0BACC;     /* Cool Gray/Blue (Medium Contrast) */
  --text-muted: #6B7A99;         /* Disabled/Low Contrast */

  /* -- Borders & Shapes -- */
  --border-default: #1E315B;
  --border-active: #FDFBF7;
  
  /* Sharp geometry for an enterprise feel */
  --radius-sm: 0px;
  --radius-md: 2px;
  --radius-lg: 4px;
}

body {
  font-family: 'IBM Plex Sans', sans-serif;
  background-color: var(--bg-primary);
  color: var(--text-primary);
}

code, .node-code {
  font-family: 'IBM Plex Mono', monospace;
}
```

### Visual Component Rules Update:
* **NeuroMap Nodes:** Backgrounds solid `--bg-secondary` (`#14213D`). Remove all `@keyframes pulse-error` and `box-shadow` glows. Replace them with flat, solid 4px left-border indicators to denote status (e.g., `#D62828` for Error nodes). Selected nodes change their border to the cream accent (`#FDFBF7`).
* **Buttons ("Fix it"):** Solid flat `#FDFBF7` background, `0px` border radius. Text color should be deep navy (`#0A1128`). Hover effect darkens the background slightly to `#E5E0D0` without moving the button (`transform: translateY(0)`).

---

## 5. Data Model Updates: Track the "Source"

**Proposed Change:** Add a `source` or `provider` field to your data models.
* **In Kotlin (`com.ghostdebugger.model`):** Update the `Issue` and `CodeFix` data classes to include a field like `val provider: String` (e.g., "Static Engine", "OpenAI GPT-4o", "Ollama Llama3").
* **In Frontend UI:** Display a small badge on the Issue Card and Fix Suggestion. If it's a deterministic fix, the badge says "⚡ Engine (100% Confident)". If it's an AI suggestion, it says "🧠 AI Suggestion (Review Required)".

---

## 6. Bridge Updates: Engine Status & Graceful Fallbacks

**Proposed Change:** Ensure the UI is aware of the backend engine status.
* **Update `JcefBridge.kt`:** Add a new method `sendEngineStatus(provider: String, status: String)` (e.g., status: "ONLINE", "OFFLINE", "FALLBACK_TO_STATIC").
* **Update `pluginBridge.ts`:** Add `onEngineStatusUpdate: (data: { provider: string, status: string }) => void`.
* **UI Implementation:** Add an indicator in your `StatusBar.tsx` that shows the current active engine (e.g., a green dot next to "Ollama (Local)" or "Static Mode (Offline)").

---

## 7. MVP Scope & Hackathon Pitch Updates

**Proposed Change:** Focus on the primary value proposition of privacy and speed.
* **Pitch Update:** "An Enterprise-Grade, Privacy-First Debugging Engine. GhostDebugger uses blazing-fast static heuristics for deterministic fixes, and seamlessly hands off to local (Ollama) or cloud (OpenAI) LLMs for deep architectural context—ensuring your proprietary code never leaves your machine unless you want it to."
* **Scope Reduction (Disable "What-If" Chat):** Cut the WhatIfChat feature for the V1 release. Building a reliable chat interface that maintains context with the graph state inside a JCEF panel is highly error-prone and distracts from the core magic: Auto-detecting and fixing bugs visually.
```

## 8. Marketing & Branding Overhaul

**Current State:** The name "GhostDebugger" and the literal ghost icon lean towards a casual vibe, which clashes with the new enterprise-grade architecture.
**Proposed Change:** Elevate the brand to match the new Dark Navy & Cream UI. The branding must communicate speed, privacy, and deep architectural intelligence.

### A. Name Evolution Candidates
* **Specter Engine:** Retains the "ghost" heritage but sounds like robust, underlying infrastructure.
* **Aegis Debug:** Emphasizes the "privacy-first" and local-security aspects of the hybrid model (Aegis = shield).
* **Nexus Flow:** Highlights the connection between static/AI engines and the visual node-graph (flow) nature of the tool.
* **Phantom Code:** A sleek, subtle nod to the original name with a more professional cadence.

### B. Logo & Iconography Redesign
* **Primary Logo:** Move away from the literal cartoon ghost. Transition to an abstract, geometric mark. For example, a stylized, sharp-edged node or graph connection that subtly forms a shield (representing local execution and security).
* **Color Application:** The primary logo mark should be the Bright Soft Cream (`#FDFBF7`) set against the Deepest Navy (`#0A1128`) background, creating an immediate premium feel.
* **Typography:** The wordmark should use **IBM Plex Sans** (SemiBold) to perfectly align with the UI's typography system.

### C. Core Messaging & Taglines
* **Primary Tagline:** "Enterprise-grade debugging. Zero privacy compromises."
* **Secondary Tagline:** "The hybrid AI debugger that runs on your terms."
* **The 5-Second Pitch:** "A privacy-first debugging engine combining blazing-fast static heuristics with local AI. Fix complex logic flaws visually, without your proprietary code ever leaving your machine."
