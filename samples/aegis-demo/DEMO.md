# Aegis Debug demo

A minimal project that exercises seven of Aegis Debug's analyzer rules end to end, so you can see
the plugin find real issues without first having to write code that happens to match its trigger
patterns. Every sample file under `src/` is deliberately small and is annotated with the rule it
demonstrates.

TypeScript/React makes up six of the seven samples, and that's a deliberate, evidence-driven
choice, not a preference: `DependencyResolver` only turns relative imports (`./x`, `../x`) into
internal graph edges. Kotlin and Java imports are fully qualified, so a Kotlin- or Java-only
project renders the NeuroMap as disconnected boxes with no edges at all — the one thing this demo
exists to avoid. The two Kotlin-specific rules that don't depend on the graph are demonstrated
separately in `src/KotlinSample.kt`.

## Prerequisites

- **IntelliJ IDEA Community 2024.3 or newer.** The plugin's `idea-version` range is
  `since-build="243.0"`.
- **`npm` on `PATH`** — only if you are building the plugin from source. Gradle's
  `processResources` task depends on `buildWebview`, which runs the webview's npm build. If you
  already have a built plugin ZIP, you don't need npm at all.

## Build

Gradle's `instrumentTestCode`/build tasks require `JAVA_HOME` to point at the JetBrains Runtime
(JBR) bundled with the IDE dependency, not a generic JDK — a generic JDK is missing a `Packages`
directory the IntelliJ Platform Gradle Plugin probes for, and the build aborts before it gets far.
From the repository root:

```bash
export JAVA_HOME=$(find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1)
export PATH=$JAVA_HOME/bin:$PATH
./gradlew buildPlugin
```

This produces `build/distributions/ghostdebugger-3.0.0.zip`.

## Install

**Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk**, then pick the ZIP built above (or a release
ZIP downloaded from GitHub Releases).

## Run

1. Open this folder — `samples/aegis-demo` — as its own project in IntelliJ IDEA (not as a
   subfolder of a larger project; the analysis pass scans whatever project is currently open).
2. Press **Ctrl+Alt+G** (Analyze Project). This opens the Aegis Debug tool window and runs the
   static analyzers over every file under `src/`.

## Expected findings

| File | Rule ID | What it means |
|---|---|---|
| `src/NullSafety.tsx` | `AEG-NULL-001` | `user` is declared with `let user = null` and then dereferenced (`user.name`) with no null check anywhere nearby. |
| `src/StateInit.tsx` | `AEG-STATE-001` | `items` comes from `useState()` with no initial value (so it starts `undefined`) and is called with `.map(...)` before it is ever set. |
| `src/StateInit.tsx` | `AEG-NULL-001` | Same line, a second finding — see **Known duplicate reporting** below. |
| `src/AsyncFlow.tsx` | `AEG-ASYNC-001` | Reported twice: a `setInterval` inside `useEffect` with no cleanup returned, and a `fetch(...).then(...)` with no `.catch(...)`. |
| `src/Complexity.ts` | `AEG-CPX-001` | `evaluateShippingRate` has eleven decision points (`if`/`for`/`while`/`&&`/`||`) in a single function — a cyclomatic complexity of 12 against the default threshold of 10. |
| `src/cycleA.ts` + `src/cycleB.ts` | `AEG-CYCLE-001` | The two files import each other via relative imports, forming a two-node circular dependency. |
| `src/KotlinSample.kt` | `AEG-CAST-KT-001` | `parseConfigValue` downcasts `Any` to `String` with `as`, which the compiler cannot prove safe. |
| `src/KotlinSample.kt` | `AEG-REDUNDANT-LET-KT-001` | `logConfigValue` calls `value?.let { ... }` on a parameter that is already non-nullable `String`. |

### Known duplicate reporting

`StateInit.tsx` is reported **twice** for the same line, under two different rule IDs. This is
expected, not a bug in the demo. `NullSafetyAnalyzer`'s pattern for a `useState()` call treats the
`null`/`undefined` initializer as optional, so a bare `useState()` — with no argument at all —
satisfies both its own regex and `StateInitAnalyzer`'s "no initial value" regex on the same line.
Both findings are individually correct; the plugin just has two rules with overlapping triggers
for this one shape. `DemoSampleFindingsTest` pins both rule IDs on this file so this stays true.

## Where to look

- **The Aegis Debug tool window**, docked on the right-hand tool bar (it opens automatically when
  you run Analyze Project). It lists every finding, grouped by file, with the NeuroMap project
  graph alongside.
- **The editor gutter**, on any file with a finding — open `src/NullSafety.tsx` or any other
  sample and look for the inline markers on the flagged lines.
- **Settings ▸ Editor ▸ Inspections ▸ Aegis Debug** — every rule is also a standard IntelliJ
  inspection here, with its own severity and enable/disable toggle, independent of the tool
  window.

## Exporting a report

**Tools ▸ Aegis Debug ▸ Export Analysis Report** writes the current analysis results to an HTML
file you can share without giving someone access to the project itself.

## What this release does not do

Aegis Debug 3.0.0 ships as a **read-only analysis and visualization tool**. Several implemented,
tested capabilities are deliberately gated off rather than shipped half-finished. Clicking **Apply
Fix**, an **AI Explanation** surface, or any other gated action below raises a roadmap dialog by
design — that dialog is not a malfunction, it is the feature telling you it isn't enabled in this
release. The dialog and this section both read the same `why` text from
`AegisCapability.kt`, so they cannot drift apart:

- **Apply Fix** — Aegis Debug 3.0 ships as a read-only analysis tool. The fix engine is implemented and tested, but fix application is not enabled in this release.
- **AI Explanation** — Issue and system explanations require a local Ollama server or an OpenAI key. The provider transport is untested end-to-end, so the feature is disabled rather than shipped unreliable.
- **AI Analysis Pass** — The AI analysis pass is disabled in this release. All findings you see come from the deterministic static analyzers.
- **Dependency Graph for Kotlin and Java** — Internal graph edges are resolved only for relative imports, so Kotlin and Java projects produce an edgeless graph. Impact analysis and cycle detection are therefore available for TypeScript and JavaScript only.
- **Rule Packs** — The three bundled packs cannot currently match real code, so enabling them would add controls that never produce a finding.
- **External Analyzer SDK** — The loader exists, but there is no published SDK artifact and no documented contract for third-party analyzers yet.
- **Problems Tool Window Integration** — Publishing to the native Problems view violates the platform's threading contract on 2024.3. Findings are available in the Aegis Debug tool window and in the editor.
- **Debugger Cross-Check** — Runtime confirmation from a paused debug session is not reachable on IntelliJ IDEA Community. Test-suite cross-check remains active.

See [`docs/IMPLEMENTATION_STATUS.md`](../../docs/IMPLEMENTATION_STATUS.md) in the repository root
for the full breakdown of what's live versus gated, and why.
