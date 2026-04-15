# Aegis Debug — Roadmap V2 → V5

**Date:** 2026-04-15
**Status:** V1 shipped (static-first, TS/JS full, Kotlin/Java graph-only, NeuroMap, 3 fixers, Ollama/OpenAI).
**What this doc is:** a high-level north-star. Scope only — no binding requirements, no acceptance criteria. Each version is "the theme of that release," not a contract.

---

## Guiding principles (hold across all versions)

- **Static-first, AI-optional.** AI never becomes load-bearing for correctness.
- **Privacy by default.** No telemetry. No cloud unless explicitly enabled.
- **Deterministic fixes only.** If we can't guarantee PSI validity, we don't offer it as a one-click fix.
- **Provenance always visible.** Engine vs. AI_LOCAL vs. AI_CLOUD stays labeled on every finding.

Every roadmap item below must respect these. If a feature requires breaking one of them, it needs an explicit user-facing opt-in.

---

## V2 — Dynamic validation + language breadth + IDE-native integration

**Theme:** prove that the findings are real. Static analysis is fast and safe but produces false positives — V2 adds a runtime cross-check so users trust what Aegis is telling them. Language breadth and IDE-native polish ship alongside.

### Headline: dynamic validation pass

Goal: for every static finding, answer "does this actually manifest at runtime?" before we flag it as an error.

- **Runtime-verified provenance tier** — add a fourth source tag alongside `STATIC` / `AI_LOCAL` / `AI_CLOUD`: `RUNTIME_CONFIRMED`. Findings promoted to this tier get a distinct badge in the NeuroMap and detail panel, and outrank unconfirmed siblings in sort order.
- **Test-suite cross-check** — when the user runs their project's tests from inside IntelliJ, hook into the test runner, correlate executed lines with static findings, and mark findings that sit on a failing-test code path (or an exception-producing path) as `RUNTIME_CONFIRMED`. Findings on unreachable lines get downgraded to `UNREACHED` with a "needs test coverage" hint.
- **Debug-session cross-check** — during an active debug session (we already have `XDebuggerManagerListener` wired from V1), observe variable values at breakpoints and confirm/deny relevant null-safety and state-before-init findings in real time. A null-safety warning on a line where the debugger just saw a non-null value gets visibly demoted.
- **False-positive suppression memory** — a finding that was reported, dismissed by the user, and never runtime-confirmed across N subsequent analyses auto-hides (with a "show hidden" toggle). Local-only, no telemetry.
- **Confidence score surface** — the existing `confidence` field in `Issue` (currently populated but barely shown) becomes a visible `CONFIRMED / LIKELY / UNCONFIRMED` pill. Drives the default issue-list sort.

Explicit boundary: dynamic validation is **non-destructive**. It only reads runtime state — it never injects code, never modifies the running program, never leaks traces outside the machine.

### Other V2 scope

- **Python support** — full analyzer pipeline (None safety, async flow, complexity, circular deps). Primary tier, same treatment as TS/JS.
- **IntelliJ Problems tool window** — all issues also surface in the native Problems panel so keyboard-driven users aren't locked into the Aegis tool window.
- **Quick-fix intention actions** — expose the three existing fixers as `IntentionAction` entries so `Alt+Enter` on a flagged line offers the same fix we show in the detail panel.
- **Inspection profile integration** — our rules become togglable in `Settings → Editor → Inspections`, respecting per-project severity overrides.
- **Streaming AI responses** — replace the current "wait then paste" model with token streaming so detail-panel explanations feel live.

**Explicit non-goals for V2:** team/cloud sync, custom rule authoring, profiler correlation (that's V4), cross-repo validation (V5).

---

## V3 — Fixer breadth + custom rule authoring

**Theme:** extend the fix catalog, and let power users define their own rules without forking the plugin.

- **Six more deterministic fixers** — rough candidates: unused-import removal, `const`/`let` tightening, redundant `await`, promise rejection without handler, missing default case, dead-code elimination. All PSI-validated.
- **Custom rule authoring** — a YAML/Kotlin-DSL way to declare pattern → severity → fix, checked into the user's repo (`.aegis/rules/*.yml`). Loaded per-project.
- **Rule packs** — curated bundles ("React strict," "Kotlin coroutines safety," "Node.js security") that users enable/disable per project.
- **Fix preview improvements** — side-by-side diff, keyboard-only apply/skip, batch-apply for the same rule across many files.
- **Analyzer author SDK** — document how the existing `Analyzer` interface can be implemented by third parties and loaded from a `.jar` dropped into the project.

**Explicit non-goals for V3:** debug-runtime features, cross-repo analysis.

---

## V4 — Debug-time UX on top of V2's runtime plumbing

**Theme:** V2 shipped the *mechanism* for reading runtime state (debug-session cross-check, confirmation tiers). V4 builds the *experience* — a debug session that actively teaches the user where to look.

- **Breakpoint-aware relevance ranking** — when paused, the detail panel re-sorts findings by relevance to the current frame and call stack, not just severity.
- **Variable-at-breakpoint AI explanations** — "why is `user.id` null here?" uses the current frame's variable values + static context, local-first via Ollama.
- **Call-stack hotspot overlay** — hotspots from the NeuroMap get highlighted live as the debugger steps through them; cycles flash when the call stack re-enters a node.
- **Profiler correlation (opt-in)** — if the IntelliJ profiler ran recently, overlay CPU hotspots on the NeuroMap so the visual map tells you where to look before you even hit a bug.
- **Debug-session report** — export a session log: steps taken, exceptions caught, variables inspected, findings reviewed and confirmed/dismissed. Still local-only unless the user uploads it.

**Explicit non-goals for V4:** team-shared debug sessions, cloud storage of traces. V2 already established the privacy boundary for runtime reads — V4 inherits it.

---

## V5 — Team / multi-repo scale

**Theme:** organizations running Aegis across many repos, without ever centralizing source code.

- **Cross-repo graph** — a local daemon (per-machine, not a server) that stitches NeuroMaps across sibling repos on disk so import cycles across packages become visible.
- **Shared rule configs** — repo-level `.aegis/` directory is already versioned; V5 adds an org-level overlay loaded from a git URL, merged at analysis time (still read-only, still local).
- **Policy packs** — compliance-style bundles ("no `eval`," "no unguarded fetch to external hosts") that fail analysis if violated. Hook-ready for pre-commit.
- **Audit log export** — timestamped record of "which rules ran, what they found, which fixes were applied" for teams that need to prove analysis coverage. Written locally; user decides where it goes.
- **CLI / CI runner** — headless mode so the same engine runs in GitHub Actions / GitLab CI and produces the same report as the IDE.
- **Multi-seat license story** — if we go commercial, this is the natural pricing boundary.

**Explicit non-goals for V5:** SaaS hosting of customer code, centralized telemetry.

---

## Sequencing notes

- **V2 is now doing double duty** — dynamic validation is the trust-builder that makes every subsequent finding believable, and it also lands the runtime-reading plumbing that V4 builds on. If V2 slips, V4 slips with it.
- **V2 before V3** because trust-in-findings matters more than fixer breadth — there's no point shipping more fixers on top of findings users don't believe. Quick-fix intentions and the Problems panel are secondary V2 wins.
- **V3 before V4** because custom rules feed debug-time relevance ranking — if users have authored project-specific rules, V4's breakpoint-aware panel becomes much richer.
- **V4 before V5** because the debug-runtime story is what makes "Aegis **Debug**" defensible as a brand; V5 is the monetization / enterprise layer that only matters if V1–V4 are compelling.

If priorities shift, the defensible re-orderings are: V3 ↔ V4 (if we get marketplace feedback that power users want custom rules more than runtime features), or V5 sliced into V4.5 + V5 (CI runner is often pulled forward by teams). **V2's dynamic validation should not be sliced or deferred** — false-positive noise is the #1 reason users abandon analysis tools.

---

## What stays out of the roadmap entirely (for now)

- Web-based version of the plugin.
- Mobile language support (Swift, Objective-C, Android-specific Kotlin rules beyond what the graph already does).
- Auto-apply fixes without human review.
- Any feature that sends source code to a server we operate.

If the market pulls us toward any of these, that's a V6+ conversation.
