# Phase 4 Implementation Spec: Aegis Debug UI Refresh (Dark Navy + Cream Design System, Engine Status Pill, Provenance Badges, Apply Fix CTA)

**Version:** 1.0 (binding)
**Status:** Ready for immediate implementation
**Source of Truth:** `aegis_debug_true_v1_spec.md`
**Target Phase from Source Spec:** Section 20 — Phase 4 (UI Refresh), items 13–18
**Prior Phase:** `aegis_debug_phase_3_fix_pipeline_spec.md` — must be merged before Phase 4 starts

---

## 1. Objective

Deliver the visual and data-surface refresh that makes Aegis Debug feel like a first-class IDE tool instead of a GitHub-dark clone. Six concrete deliverables:

1. **Dark Navy + Cream design-token system.** Replace every hardcoded `#0d1117`/`#161b22`/… hex string across the webview with CSS custom properties defined in a single `:root {}` block. All colors derive from two brand anchors: **Dark Navy `#0A1128`** and **Cream `#FDFBF7`**.

2. **IBM Plex font bundle.** Add `@fontsource/ibm-plex-sans` and `@fontsource/ibm-plex-mono` as webview npm dependencies. Import the 400 and 600 weights in `main.tsx`. Replace the JetBrains Mono UI font with IBM Plex Sans; replace all `fontFamily: 'monospace'` code spans with IBM Plex Mono.

3. **Engine status pill.** Wire `JcefBridge.sendEngineStatus(payload)` on the Kotlin side and add an `onEngineStatus` handler on the TypeScript side so the `StatusBar` can display a live "OpenAI · Online" / "Static Mode" / "Offline" pill sourced from the `EngineStatusPayload` emitted after every analysis run.

4. **Issue provenance badges.** Surface `Issue.sources[]` (Phase 2 data, not yet displayed) as coloured "STATIC" / "AI" pills inside each `IssueRow` in `DetailPanel`. No changes to the data layer; this is display-only.

5. **Fix trust badge.** Surface `CodeFix.isDeterministic` (Phase 3 data) as a "Deterministic" / "AI-Generated" pill in the fix-preview card inside `SolutionsContent`.

6. **Apply Fix CTA.** Add a primary "Apply Fix" button inside `SolutionsContent`, visible whenever a `CodeFix` is available. The button sends `APPLY_FIX` over the bridge via a new `bridge.applyFix(issueId, fixId)` method, shows a loading state while the backend applies the change, and clears when `onFixApplied` is received.

Phase 4 ships **no** new static analyzers, **no** new Kotlin fix logic, **no** changes to `FixApplicator` or any fixer class, and **no** changes to the IntelliJ settings panel.

---

## 2. Scope

### 2.1 In Scope

| Area | Work |
|---|---|
| `webview/src/index.css` | Add `:root {}` CSS custom-property token block; update `body` font-family and background |
| `webview/src/main.tsx` | Add four `@fontsource` imports (IBM Plex Sans 400/600, IBM Plex Mono 400/600) |
| `webview/src/types/index.ts` | Add `IssueSource`, `EngineProvider`, `EngineStatus`, `EngineStatusPayload`; extend `Issue` and `CodeFix` with optional Phase 2/3 fields |
| `webview/src/bridge/pluginBridge.ts` | Add `onEngineStatus` + `onFixApplied` to `AegisAPI`; add handler arrays + subscription methods + `applyFix()` to `PluginBridge` |
| `webview/src/stores/appStore.ts` | Add `engineStatus` + `applyingFix` fields; add `SET_ENGINE_STATUS`, `SET_APPLYING_FIX`, `FIX_APPLIED` actions + reducer cases |
| `webview/src/App.tsx` | Subscribe `onEngineStatus` → `SET_ENGINE_STATUS`; subscribe `onFixApplied` → `FIX_APPLIED`; replace two hardcoded inline color/font strings with CSS var references |
| `webview/src/components/layout/StatusBar.tsx` | Replace all hardcoded hex colors with CSS vars; add `engineStatus` prop + `EngineStatusPill` sub-component |
| `webview/src/components/detail-panel/DetailPanel.tsx` | Replace `C` object with CSS var values; add `ProvenanceBadge` sub-component on `IssueRow`; add `TrustBadge` + Apply Fix button in `SolutionsContent` |
| `src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt` | Add `sendEngineStatus(payload: EngineStatusPayload)` method |
| `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` | Call `bridge.sendEngineStatus(result.engineStatus)` after each `analyzeProject()` completes |
| `webview/package.json` | Add `@fontsource/ibm-plex-sans` and `@fontsource/ibm-plex-mono` |
| Tests | 2 new test files; all existing tests continue to pass |

### 2.2 Out of Scope (strict)

- `PixelCity` view — no color changes there until a dedicated pixel-art palette pass is specced.
- `NeuroMap` node colors — graph node status colors (`HEALTHY`/`WARNING`/`ERROR`) are functional, not brand; untouched.
- Any new Kotlin analyzer, fixer, or IntelliJ settings UI.
- Multi-fix / bulk-apply UX.
- Dark-mode toggle or theme switching (single fixed palette).
- Framer Motion animation timing changes.
- Accessibility / WCAG audit (deferred post-V1).

---

## 3. Non-Goals

The following MUST NOT be touched by Phase 4:

1. Do **not** change `AnalysisEngine.kt`, any `Analyzer` implementation, or any `Fixer` implementation.
2. Do **not** change `FixApplicator.kt`, `FixerRegistry.kt`, or any Phase 3 fix logic.
3. Do **not** change `AnalysisModels.kt`, `EngineStatus.kt`, `UIEvent.kt`, or `UIEventParser`.
4. Do **not** change any Phase 1, 2, or 3 test class.
5. Do **not** rename, remove, or reorder existing `AppState` fields or `AppAction` union members. Phase 4 additions are purely additive.
6. Do **not** add new Kotlin/Gradle dependencies beyond what is already present.
7. Do **not** change `NeuroMap.tsx`, `CustomNode.tsx`, `PixelCity.tsx`, or `PixelBuilding.tsx`.
8. Do **not** change `GhostDebuggerService.analyzeProject()` control flow beyond inserting the single `bridge.sendEngineStatus` call.

---

## 4. Implementation Decisions

| Decision | Value | Source |
|---|---|---|
| Dark Navy anchor | `#0A1128` | Phase 1 spec §4 — explicitly deferred to Phase 4 |
| Cream anchor | `#FDFBF7` | Phase 1 spec §4 — explicitly deferred to Phase 4 |
| CSS custom property host | `:root` in `webview/src/index.css` | single source of truth for all tokens |
| UI font | IBM Plex Sans 400/600 | Phase 1 spec §4 — explicitly deferred to Phase 4 |
| Code font | IBM Plex Mono 400/600 | Phase 1 spec §4 — explicitly deferred to Phase 4 |
| Font delivery | `@fontsource` npm packages (self-hosted, offline-safe) | binding §5.1 |
| Engine pill placement | Left section of StatusBar, after "Aegis Debug" brand pill | binding §5.2 |
| Engine status label format | See §5.3 table | binding §5.3 |
| Provenance badge placement | Trailing inline in `IssueRow` header row, after `L{line}` chip | binding §5.4 |
| Trust badge placement | Below `fix.description` card, before `CodeDiff` | binding §5.5 |
| Apply Fix button placement | Below `CodeDiff`, as full-width primary button above the "Copy prompt" / "Regenerate" row | binding §5.6 |
| `applyingFix` state field | `string \| null` — issueId being applied; clears on `FIX_APPLIED` | binding §5.7 |
| `sendEngineStatus` Kotlin call site | Immediately after `bridge.sendAnalysisComplete(...)` in `GhostDebuggerService` | binding §5.8 |
| Webview test framework | Vitest (already configured in webview) | `webview/package.json` |

---

## 5. Binding Decisions

### 5.1 Font npm packages

Two new dev/runtime dependencies added to `webview/package.json`:

```
@fontsource/ibm-plex-sans   ^5.x
@fontsource/ibm-plex-mono   ^5.x
```

Imports in `webview/src/main.tsx` (add before the existing CSS imports):

```typescript
import '@fontsource/ibm-plex-sans/400.css'
import '@fontsource/ibm-plex-sans/600.css'
import '@fontsource/ibm-plex-mono/400.css'
import '@fontsource/ibm-plex-mono/600.css'
```

### 5.2 Engine status pill position

The `EngineStatusPill` is rendered as the second segment in `StatusBar`, immediately after the "Aegis Debug" brand block and before the project-name block:

```
[Aegis Debug] [OpenAI · Online] [project-name] … [node count] [health]
```

### 5.3 Engine status pill label + dot color

| `EngineStatus` value | Dot color | Label format |
|---|---|---|
| `ONLINE` | `var(--ok-text)` | `"${providerLabel} · Online"` (e.g. `"OpenAI · Online"`) |
| `FALLBACK_TO_STATIC` | `var(--warn-text)` | `"Static Mode"` |
| `DISABLED` | `var(--fg-muted)` | `"Static Only"` |
| `DEGRADED` | `var(--warn-text)` | `"Degraded"` |
| `OFFLINE` | `var(--error-text)` | `"Offline"` |

Provider label mapping (used only when `status === 'ONLINE'`):

| `EngineProvider` | Label |
|---|---|
| `OPENAI` | `"OpenAI"` |
| `OLLAMA` | `"Ollama"` |
| `STATIC` | `"Static"` |

When `engineStatus` is `null` (initial state before first analysis), the pill is not rendered at all.

### 5.4 Provenance badge visual spec

Shown inside `IssueRow`, inline-right of the `L{n}` line chip:

| Source value | Text | Text color | Background |
|---|---|---|---|
| `STATIC` | `STATIC` | `var(--badge-static-text)` | `var(--badge-static-bg)` |
| `AI_CLOUD` | `AI` | `var(--badge-ai-text)` | `var(--badge-ai-bg)` |

When `issue.sources` is absent or empty, render nothing. When both sources are present, render both badges side by side.

### 5.5 Trust badge visual spec

Shown inside `SolutionsContent`, directly below the `fix.description` card (if present) and above the `CodeDiff`:

| `fix.isDeterministic` | Text | Text color | Background |
|---|---|---|---|
| `true` | `Deterministic` | `var(--ok-text)` | `var(--ok-bg)` |
| `false` or absent | `AI-Generated` | `var(--badge-ai-text)` | `var(--badge-ai-bg)` |

### 5.6 Apply Fix button spec

Rendered below `CodeDiff` as a **full-width primary button** with:
- Background: `var(--accent)`
- Text color: `var(--fg-primary)`
- Label (idle): `"Apply Fix"` with a `Check` (lucide) icon
- Label (loading, `applyingFix === selectedIssue.id`): `"Applying…"` with a spinning `Loader2` icon; button is disabled
- The existing "Copy prompt for AI" / "↻ Regenerate" row remains below it unchanged

On click:
1. `dispatch({ type: 'SET_APPLYING_FIX', payload: selectedIssue.id })`
2. `bridge.applyFix(selectedIssue.id, fix.id)`

### 5.7 `FIX_APPLIED` reducer behaviour

```
FIX_APPLIED (payload: issueId):
  pendingFixes: omit payload from pendingFixes
  applyingFix:  null
```

The fix is removed from the preview so the panel returns to the "Generate Fix Suggestion" state for that issue.

### 5.8 Kotlin call site for sendEngineStatus

In `GhostDebuggerService`, locate the block that calls `bridge.sendAnalysisComplete(result.errorCount, result.warningCount, result.healthScore)` and add immediately after it:

```kotlin
bridge.sendEngineStatus(result.engineStatus)
```

No other changes to `analyzeProject()` control flow.

---

## 6. index.css — CSS Design Token Block

Add the following `:root {}` block at the **top** of `webview/src/index.css`, before all existing rules. Replace the existing `body { background: ... }` and `body { font-family: ... }` declarations with the CSS-var equivalents shown below.

```css
:root {
  /* ── Backgrounds ───────────────────────────────────── */
  --bg-base:     #0A1128;   /* Dark Navy — main panel background */
  --bg-surface:  #0F1A38;   /* card/panel surfaces */
  --bg-elevated: #162040;   /* elevated cards, hover fills */
  --border:      #1E2E50;   /* default borders */
  --border-fg:   #283D62;   /* focused / hovered borders */

  /* ── Foregrounds ───────────────────────────────────── */
  --fg-primary:   #FDFBF7;  /* Cream — primary text */
  --fg-secondary: #C4BDB5;  /* secondary text */
  --fg-muted:     #7A7570;  /* placeholder / muted text */

  /* ── Accent ────────────────────────────────────────── */
  --accent: #4A8CFF;

  /* ── Semantic: error ───────────────────────────────── */
  --error-text:   #E05555;
  --error-bg:     rgba(224, 85,  85,  0.10);
  --error-border: rgba(224, 85,  85,  0.28);

  /* ── Semantic: warning ─────────────────────────────── */
  --warn-text:   #D4943A;
  --warn-bg:     rgba(212, 148, 58,  0.10);
  --warn-border: rgba(212, 148, 58,  0.28);

  /* ── Semantic: ok / success ────────────────────────── */
  --ok-text:   #3DB566;
  --ok-bg:     rgba(61,  181, 102, 0.10);
  --ok-border: rgba(61,  181, 102, 0.28);

  /* ── Semantic: info / blue ─────────────────────────── */
  --blue-text:   #79BFFF;
  --blue-bg:     rgba(121, 191, 255, 0.10);
  --blue-border: rgba(121, 191, 255, 0.22);

  /* ── Provenance badges ─────────────────────────────── */
  --badge-static-text: #79BFFF;
  --badge-static-bg:   rgba(121, 191, 255, 0.15);
  --badge-ai-text:     #C084FC;
  --badge-ai-bg:       rgba(192, 132, 252, 0.15);

  /* ── Typography ────────────────────────────────────── */
  --font-ui:   'IBM Plex Sans',  -apple-system, sans-serif;
  --font-code: 'IBM Plex Mono',  'Fira Code',   monospace;
}

body {
  background: var(--bg-base);
  color:       var(--fg-primary);
  font-family: var(--font-ui);
  margin: 0;
  padding: 0;
}
```

---

## 7. types/index.ts — Type Additions

Append the following declarations at the end of `webview/src/types/index.ts`. Do **not** modify existing type definitions; these are purely additive.

```typescript
// ── Phase 2 provenance types ──────────────────────────────────────────────────
export type IssueSource    = 'STATIC' | 'AI_CLOUD'
export type EngineProvider = 'STATIC' | 'OPENAI' | 'OLLAMA'
export type EngineStatus   = 'ONLINE' | 'OFFLINE' | 'DEGRADED' | 'FALLBACK_TO_STATIC' | 'DISABLED'

export interface EngineStatusPayload {
  provider:  EngineProvider
  status:    EngineStatus
  message?:  string
  latencyMs?: number
}
```

Extend the existing `Issue` interface by adding four **optional** fields. The existing required fields are unchanged:

```typescript
// Add inside the existing Issue interface:
  ruleId?:     string
  sources?:    IssueSource[]
  providers?:  EngineProvider[]
  confidence?: number
```

Extend the existing `CodeFix` interface by adding two **optional** fields:

```typescript
// Add inside the existing CodeFix interface:
  isDeterministic?: boolean
  confidence?:      number
```

---

## 8. pluginBridge.ts — Bridge Additions

### 8.1 AegisAPI interface

Add two entries to the `AegisAPI` interface (after `onAutoRefreshStart`):

```typescript
  onEngineStatus: (data: EngineStatusPayload) => void
  onFixApplied:   (data: { issueId: string }) => void
```

Add the import at the top of the file:

```typescript
import type { ProjectGraph, CodeFix, AnalysisMetrics, ImpactAnalysis, NodeStatus, DebugFrame, EngineStatusPayload } from '../types'
```

### 8.2 PluginBridge class additions

Add two handler arrays alongside the existing ones:

```typescript
  private engineStatusHandlers: EventHandler<EngineStatusPayload>[] = []
  private fixAppliedHandlers:   EventHandler<{ issueId: string }>[] = []
```

In `setupAPI()`, wire them into `window.__aegis_debug__`:

```typescript
      onEngineStatus: (data) => this.engineStatusHandlers.forEach(h => h(data)),
      onFixApplied:   (data) => this.fixAppliedHandlers.forEach(h => h(data)),
```

Add two subscription methods (alongside `onAutoRefreshStart`):

```typescript
  onEngineStatus(handler: EventHandler<EngineStatusPayload>) {
    this.engineStatusHandlers.push(handler)
    return () => { this.engineStatusHandlers = this.engineStatusHandlers.filter(h => h !== handler) }
  }

  onFixApplied(handler: EventHandler<{ issueId: string }>) {
    this.fixAppliedHandlers.push(handler)
    return () => { this.fixAppliedHandlers = this.fixAppliedHandlers.filter(h => h !== handler) }
  }
```

Add a send method (alongside `debugPause`):

```typescript
  applyFix(issueId: string, fixId: string) {
    this.sendEvent('APPLY_FIX', { issueId, fixId })
  }
```

---

## 9. appStore.ts — Store Additions

### 9.1 AppState

Add two fields to the `AppState` interface (additive):

```typescript
  engineStatus: EngineStatusPayload | null
  applyingFix:  string | null   // issueId currently being applied; null when idle
```

### 9.2 AppAction

Add three action types to the `AppAction` union (additive):

```typescript
  | { type: 'SET_ENGINE_STATUS'; payload: EngineStatusPayload }
  | { type: 'SET_APPLYING_FIX';  payload: string | null }
  | { type: 'FIX_APPLIED';       payload: string }          // payload is issueId
```

### 9.3 initialState

Add to `initialState`:

```typescript
  engineStatus: null,
  applyingFix:  null,
```

### 9.4 Reducer cases

Add three `case` blocks in `appReducer` (before the `default` case):

```typescript
    case 'SET_ENGINE_STATUS':
      return { ...state, engineStatus: action.payload }

    case 'SET_APPLYING_FIX':
      return { ...state, applyingFix: action.payload }

    case 'FIX_APPLIED': {
      const { [action.payload]: _removed, ...remainingFixes } = state.pendingFixes
      return { ...state, pendingFixes: remainingFixes, applyingFix: null }
    }
```

Add the import for `EngineStatusPayload` at the top:

```typescript
import type { ProjectGraph, GraphNode, Issue, CodeFix, AnalysisMetrics, NodeStatus, DebugFrame, ViewMode, EngineStatusPayload } from '../types'
```

---

## 10. App.tsx — Subscription Wiring + Color Migration

### 10.1 Two new subscriptions in the `useEffect`

Add inside the existing `useEffect` cleanup block alongside the other `const unsub` declarations:

```typescript
    const unsubEngineStatus = bridge.onEngineStatus(payload =>
      dispatch({ type: 'SET_ENGINE_STATUS', payload })
    )

    const unsubFixApplied = bridge.onFixApplied(({ issueId }) =>
      dispatch({ type: 'FIX_APPLIED', payload: issueId })
    )
```

Add both to the return cleanup:

```typescript
      unsubEngineStatus(); unsubFixApplied();
```

### 10.2 Root div style migration

Replace the two hardcoded values in the root `<div>` style (App.tsx line 69–71):

```typescript
// Before:
background: '#0d1117', color: '#e6edf3',
fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace",

// After:
background: 'var(--bg-base)', color: 'var(--fg-primary)',
fontFamily: 'var(--font-ui)',
```

### 10.3 StatusBar prop addition

Pass `engineStatus` to `StatusBar`:

```typescript
<StatusBar
  isAnalyzing={state.isAnalyzing}
  metrics={state.metrics}
  projectName={state.graph?.metadata.projectName}
  totalNodes={state.graph?.nodes.length}
  isAutoRefreshing={state.isAutoRefreshing}
  engineStatus={state.engineStatus}
/>
```

---

## 11. StatusBar.tsx — Engine Status Pill + Token Migration

### 11.1 New prop

Extend `StatusBarProps` with:

```typescript
  engineStatus?: EngineStatusPayload | null
```

Add import:

```typescript
import type { AnalysisMetrics, EngineStatusPayload } from '../../types'
```

### 11.2 Color migration

Replace every hardcoded hex in `StatusBar.tsx` with the corresponding CSS var:

| Old hex | CSS var |
|---|---|
| `#161b22` | `var(--bg-surface)` |
| `#21262d` | `var(--bg-elevated)` |
| `#30363d` | `var(--border)` |
| `#8b949e` | `var(--fg-secondary)` |
| `#6e7681` | `var(--fg-muted)` |
| `#79c0ff` | `var(--blue-text)` |
| `#388bfd` | `var(--accent)` |
| `#3fb950` | `var(--ok-text)` |
| `#d29922` | `var(--warn-text)` |
| `#f85149` | `var(--error-text)` |

Replace `fontFamily: "'JetBrains Mono', 'Fira Code', monospace"` with `fontFamily: 'var(--font-code)'`.

### 11.3 EngineStatusPill sub-component

Add the following pure sub-component at the bottom of `StatusBar.tsx`:

```typescript
function providerLabel(provider: string): string {
  if (provider === 'OPENAI') return 'OpenAI'
  if (provider === 'OLLAMA') return 'Ollama'
  return 'Static'
}

function EngineStatusPill({ payload }: { payload: EngineStatusPayload }) {
  let dotColor: string
  let label: string

  switch (payload.status) {
    case 'ONLINE':
      dotColor = 'var(--ok-text)'
      label    = `${providerLabel(payload.provider)} · Online`
      break
    case 'FALLBACK_TO_STATIC':
      dotColor = 'var(--warn-text)'
      label    = 'Static Mode'
      break
    case 'DEGRADED':
      dotColor = 'var(--warn-text)'
      label    = 'Degraded'
      break
    case 'OFFLINE':
      dotColor = 'var(--error-text)'
      label    = 'Offline'
      break
    case 'DISABLED':
    default:
      dotColor = 'var(--fg-muted)'
      label    = 'Static Only'
  }

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 5,
      padding: '0 10px', height: '100%',
      borderRight: '1px solid var(--border)',
      flexShrink: 0,
    }}>
      <div style={{
        width: 5, height: 5, borderRadius: '50%',
        background: dotColor, flexShrink: 0,
      }} />
      <span style={{ color: 'var(--fg-secondary)', fontSize: 9 }}>{label}</span>
    </div>
  )
}
```

### 11.4 Pill placement in JSX

Render `EngineStatusPill` immediately after the "Aegis Debug" brand block, before the project-name block:

```typescript
      {/* Engine status pill */}
      {engineStatus && <EngineStatusPill payload={engineStatus} />}

      {/* Project name */}
      {projectName && ( ... existing block ... )}
```

---

## 12. DetailPanel.tsx — Token Migration + Badges + Apply Fix CTA

### 12.1 C object migration

Replace every value in the `C` constant object (DetailPanel.tsx lines 12–34) with the corresponding CSS variable:

```typescript
const C = {
  bg:        'var(--bg-base)',
  surface:   'var(--bg-surface)',
  elevated:  'var(--bg-elevated)',
  border:    'var(--border)',
  borderFg:  'var(--border-fg)',
  text1:     'var(--fg-primary)',
  text2:     'var(--fg-secondary)',
  text3:     'var(--fg-muted)',
  errorText: 'var(--error-text)',
  errorBg:   'var(--error-bg)',
  errorBdr:  'var(--error-border)',
  warnText:  'var(--warn-text)',
  warnBg:    'var(--warn-bg)',
  warnBdr:   'var(--warn-border)',
  okText:    'var(--ok-text)',
  okBg:      'var(--ok-bg)',
  okBdr:     'var(--ok-border)',
  blueText:  'var(--blue-text)',
  blueBg:    'var(--blue-bg)',
  blueBdr:   'var(--blue-border)',
  accent:    'var(--accent)',
}
```

No other lines in `DetailPanel.tsx` change; all `C.xxx` references automatically resolve to the new CSS vars.

Also replace every occurrence of `fontFamily: 'monospace'` in `DetailPanel.tsx` with `fontFamily: 'var(--font-code)'`.

### 12.2 ProvenanceBadge sub-component

Add after the `SeverityIcon` helper near the bottom of `DetailPanel.tsx`:

```typescript
function ProvenanceBadge({ source }: { source: 'STATIC' | 'AI_CLOUD' }) {
  const isStatic = source === 'STATIC'
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center',
      color:      isStatic ? 'var(--badge-static-text)' : 'var(--badge-ai-text)',
      background: isStatic ? 'var(--badge-static-bg)'   : 'var(--badge-ai-bg)',
      fontSize: 8, fontWeight: 700,
      padding: '1px 5px', borderRadius: 3,
      letterSpacing: '0.06em',
      flexShrink: 0,
    }}>
      {isStatic ? 'STATIC' : 'AI'}
    </span>
  )
}
```

### 12.3 Provenance badges in IssueRow

Inside `IssueRow`, locate the `L{issue.line}` chip block (around line 396–406). Immediately after the closing `</span>` of the `L{n}` chip, add:

```typescript
          {issue.sources && issue.sources.length > 0 && (
            <span style={{ display: 'inline-flex', gap: 3, marginLeft: 4, flexShrink: 0 }}>
              {issue.sources.map(src => (
                <ProvenanceBadge key={src} source={src} />
              ))}
            </span>
          )}
```

The `sources` field is optional on `Issue`; when absent the block renders nothing.

### 12.4 TrustBadge sub-component

Add alongside `ProvenanceBadge`:

```typescript
function TrustBadge({ isDeterministic }: { isDeterministic?: boolean }) {
  const det = isDeterministic === true
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      color:      det ? 'var(--ok-text)'       : 'var(--badge-ai-text)',
      background: det ? 'var(--ok-bg)'         : 'var(--badge-ai-bg)',
      border: `1px solid ${det ? 'var(--ok-border)' : 'rgba(192,132,252,0.28)'}`,
      fontSize: 8, fontWeight: 700,
      padding: '2px 7px', borderRadius: 4,
      letterSpacing: '0.05em',
    }}>
      {det ? 'Deterministic' : 'AI-Generated'}
    </span>
  )
}
```

### 12.5 Trust badge placement in SolutionsContent

Inside the `{fix && ( ... )}` block in `SolutionsContent`, add `<TrustBadge>` between the `fix.description` card and `<CodeDiff>`:

```typescript
          {/* Trust badge */}
          <div style={{ marginBottom: 10 }}>
            <TrustBadge isDeterministic={fix.isDeterministic} />
          </div>

          {/* Code diff */}
          <CodeDiff original={fix.originalCode} fixed={fix.fixedCode} filePath={fix.filePath} />
```

### 12.6 Apply Fix button in SolutionsContent

Update `SolutionsContent` signature to consume `applyingFix` from `state`:

```typescript
function SolutionsContent({
  selectedNode,
  selectedIssue,
  fixes,
  loadingFix,
  applyingFix,
  dispatch,
}: {
  selectedNode:  GraphNode
  selectedIssue: Issue | null
  fixes:         Record<string, CodeFix>
  loadingFix:    string | null
  applyingFix:   string | null
  dispatch:      React.Dispatch<any>
})
```

In the call site (DetailPanel, `<SolutionsContent>` props), add `applyingFix={state.applyingFix}`.

Inside `SolutionsContent`, add handler and button. After the existing `handleCopyForAI` function, add:

```typescript
  const handleApplyFix = () => {
    if (!fix || !selectedIssue) return
    dispatch({ type: 'SET_APPLYING_FIX', payload: selectedIssue.id })
    bridge.applyFix(selectedIssue.id, fix.id)
  }
```

In the actions row (current `{fix && (...)}` block), insert the Apply Fix button **above** the existing "Copy prompt / Regenerate" row:

```typescript
          {/* Apply Fix */}
          <button
            onClick={handleApplyFix}
            disabled={applyingFix === selectedIssue.id}
            style={{
              width: '100%',
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
              background: applyingFix === selectedIssue.id ? 'var(--bg-elevated)' : 'var(--accent)',
              color: applyingFix === selectedIssue.id ? 'var(--fg-muted)' : 'var(--fg-primary)',
              fontSize: 10, fontWeight: 700,
              padding: '8px 0', borderRadius: 6,
              cursor: applyingFix === selectedIssue.id ? 'not-allowed' : 'pointer',
              border: 'none',
              letterSpacing: '0.04em',
              marginBottom: 6,
              transition: 'opacity 0.15s',
            }}
          >
            {applyingFix === selectedIssue.id
              ? <><Loader2 size={11} className="animate-spin" /> Applying…</>
              : <><Check size={11} /> Apply Fix</>
            }
          </button>

          {/* Copy prompt for AI + Regenerate (existing row unchanged) */}
          <div style={{ display: 'flex', gap: 6 }}>
            ...existing buttons...
          </div>
```

---

## 13. JcefBridge.kt — sendEngineStatus

Add the following method to `JcefBridge.kt` after `sendAutoRefreshStart()`:

```kotlin
fun sendEngineStatus(payload: EngineStatusPayload) {
    val payloadJson = json.encodeToString(payload)
    executeJS("window.__aegis_debug__ && window.__aegis_debug__.onEngineStatus($payloadJson)")
}
```

Add the import at the top of the file:

```kotlin
import com.ghostdebugger.model.EngineStatusPayload
```

---

## 14. GhostDebuggerService.kt — Emission After Analysis

In `GhostDebuggerService.kt`, find the call to `bridge.sendAnalysisComplete(result.errorCount, result.warningCount, result.healthScore)` inside `analyzeProject()`. Add a single line immediately after it:

```kotlin
bridge.sendEngineStatus(result.engineStatus)
```

No other changes. The call is unconditional; `result.engineStatus` is always present (`EngineStatusPayload` is non-nullable in `AnalysisResult`).

---

## 15. Tests

### 15.1 Required new test files

| File | What it tests |
|---|---|
| `src/test/kotlin/com/ghostdebugger/bridge/EngineStatusBridgeTest.kt` | `JcefBridge.sendEngineStatus` produces the correct JavaScript string for each `EngineStatus` value |
| `webview/src/__tests__/engineStatusPill.test.tsx` | `EngineStatusPill` renders correct label and dot color for each of the 5 `EngineStatus` values; renders nothing when `engineStatus` is null |

### 15.2 EngineStatusBridgeTest (Kotlin, JUnit 5)

```kotlin
package com.ghostdebugger.bridge

import com.ghostdebugger.model.EngineStatus
import com.ghostdebugger.model.EngineStatusPayload
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class EngineStatusBridgeTest {
    // We cannot instantiate JcefBridge without a live IDE platform,
    // so this test verifies the serialization shape directly.

    @Test fun `EngineStatusPayload serializes ONLINE correctly`() {
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json
        val json = Json { encodeDefaults = true }
        val payload = EngineStatusPayload(provider = com.ghostdebugger.model.EngineProvider.OPENAI, status = EngineStatus.ONLINE)
        val encoded = json.encodeToString(payload)
        assertTrue(encoded.contains("\"status\":\"ONLINE\""))
        assertTrue(encoded.contains("\"provider\":\"OPENAI\""))
    }

    @Test fun `EngineStatusPayload serializes FALLBACK_TO_STATIC`() {
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json
        val json = Json { encodeDefaults = true }
        val payload = EngineStatusPayload(provider = com.ghostdebugger.model.EngineProvider.STATIC, status = EngineStatus.FALLBACK_TO_STATIC)
        val encoded = json.encodeToString(payload)
        assertTrue(encoded.contains("FALLBACK_TO_STATIC"))
    }
}
```

**Note:** The import statements inside `@Test` bodies above are illustrative; move them to the top of the file in the real implementation.

### 15.3 engineStatusPill.test.tsx (Vitest + React Testing Library)

```typescript
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
// EngineStatusPill must be exported from StatusBar.tsx for testing
import { EngineStatusPill } from '../components/layout/StatusBar'
import type { EngineStatusPayload } from '../types'

const make = (status: string, provider = 'OPENAI'): EngineStatusPayload =>
  ({ provider, status, message: undefined, latencyMs: undefined } as unknown as EngineStatusPayload)

describe('EngineStatusPill', () => {
  it('shows provider name when ONLINE', () => {
    render(<EngineStatusPill payload={make('ONLINE')} />)
    expect(screen.getByText('OpenAI · Online')).toBeTruthy()
  })

  it('shows Static Mode when FALLBACK_TO_STATIC', () => {
    render(<EngineStatusPill payload={make('FALLBACK_TO_STATIC', 'STATIC')} />)
    expect(screen.getByText('Static Mode')).toBeTruthy()
  })

  it('shows Static Only when DISABLED', () => {
    render(<EngineStatusPill payload={make('DISABLED', 'STATIC')} />)
    expect(screen.getByText('Static Only')).toBeTruthy()
  })

  it('shows Offline when OFFLINE', () => {
    render(<EngineStatusPill payload={make('OFFLINE', 'STATIC')} />)
    expect(screen.getByText('Offline')).toBeTruthy()
  })

  it('shows Degraded when DEGRADED', () => {
    render(<EngineStatusPill payload={make('DEGRADED')} />)
    expect(screen.getByText('Degraded')).toBeTruthy()
  })
})
```

`EngineStatusPill` must be exported (named export) from `StatusBar.tsx` to be importable in tests.

---

## 16. File Change Summary

| File | Change type |
|---|---|
| `webview/package.json` | add 2 dependencies |
| `webview/src/index.css` | add `:root {}` token block; update `body` |
| `webview/src/main.tsx` | add 4 fontsource imports |
| `webview/src/types/index.ts` | add 4 types; extend `Issue` + `CodeFix` |
| `webview/src/bridge/pluginBridge.ts` | add `onEngineStatus`, `onFixApplied`, `applyFix` |
| `webview/src/stores/appStore.ts` | add `engineStatus`, `applyingFix`; add 3 actions |
| `webview/src/App.tsx` | add 2 subscriptions; migrate root `<div>` colors |
| `webview/src/components/layout/StatusBar.tsx` | token migration; add `EngineStatusPill` |
| `webview/src/components/detail-panel/DetailPanel.tsx` | token migration; add `ProvenanceBadge`, `TrustBadge`, Apply Fix button |
| `src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt` | add `sendEngineStatus` |
| `src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt` | call `sendEngineStatus` after analysis |
| `src/test/kotlin/com/ghostdebugger/bridge/EngineStatusBridgeTest.kt` | **new** |
| `webview/src/__tests__/engineStatusPill.test.tsx` | **new** |

Kotlin files not listed above are **untouched**. Webview files not listed above are **untouched**.
