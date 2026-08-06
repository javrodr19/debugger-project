# Aegis Debug — V3 Frontier Overview & Decomposition

**Date:** 2026-06-16
**Status:** Approved & Shipped in `v.2.0.0-beta.1` (V3.1, V3.2, V3.3, V3.4).
**Version target:** V3.x (post-2.0.0). All pillars (Rule Packs, Fix Preview UX, External SDK) fully implemented and merged.

---

## 0. Summary

A large slice of what the roadmap calls "V3" already shipped **ahead of schedule** — the
AI-supervised fix engine (`fix/engine/`), the fixer-catalog breadth (batches 1–5), code
simplification, and AI + JS/TS extract-method are all merged. What the roadmap labelled V3's
headline — **letting power users extend the analysis without forking the plugin** — is the part
that remains genuinely unbuilt. This is the **V3 frontier**.

This spec frames that frontier as **four sequenced pillars**, establishes the cross-cutting
constraints they all share, and hands the first pillar off to its own deep-dive design spec. It
deliberately does *not* design each pillar in full — each pillar gets its own spec → plan →
implementation cycle. This is the map, not the territory.

The four pillars, in build order:

| # | Pillar | One-line | Foundation for |
|---|---|---|---|
| **V3.1** | **Custom rule authoring** | Declarative `.aegis/rules/*.yml`: pattern → severity → fix, loaded per-project | everything below |
| **V3.2** | **Rule packs** | Curated, togglable bundles of V3.1 rules ("React strict", "Coroutines safety") | — |
| **V3.3** | **Fix-preview improvements** | Side-by-side diff, keyboard-only apply/skip, batch-apply across files | richer custom-fix UX |
| **V3.4** | **Analyzer-author SDK** | Documented `Analyzer` interface + `.jar` drop-in for logic YAML can't express | power-user escape hatch |

---

## 1. Motivation

The roadmap's sequencing note is explicit: *"custom rules feed debug-time relevance ranking — if
users have authored project-specific rules, V4's breakpoint-aware panel becomes much richer."* The
extensibility frontier is therefore load-bearing for V4, not optional polish.

Three forces make *now* (post-stabilization) the right time:

1. **The fix engine made custom *fixes* safe to offer.** Before V3's `FixEngine`, a user-authored
   fix would have been free-form text — un-vettable, PSI-unsafe, a direct violation of the
   "deterministic fixes only" principle. The engine's verified `FixOperation` catalog +
   `FixPlanApplicator` verify gate now give us a way for a YAML rule to *compose* deterministic
   operations that are validated the same way the AI planner's are. The keystone already exists.
2. **The inspection-profile + Problems-panel surfaces (V2) give custom findings a home.** A
   custom rule's finding flows through the same `AegisLocalInspection` / `ProblemsViewCoordinator`
   path as a built-in one — no new surface to build, only a new *source* of findings.
3. **Trust is established (V2's whole thesis).** Custom rules multiply the finding count; they only
   make sense once the runtime-validation machinery exists to keep false positives in check.

---

## 2. Cross-cutting constraints (every pillar respects these)

These are the four guiding principles applied to *user-authored* analysis — the place they are
easiest to violate:

| Principle | What it forces on the frontier |
|---|---|
| **Deterministic fixes only** | A custom rule's `fix:` is **not** free-form text. It is a small `FixPlan` composed of existing, verified `FixOperation`s, applied by `FixPlanApplicator` and run through the verify gate. If a rule can't express its fix as deterministic ops, it declares **no fix** (detection-only) — never a guessed one. |
| **Provenance always visible** | Custom-rule findings carry a distinct provenance (`CUSTOM`) **plus the originating rule id**, so the detail panel always shows "flagged by *your* rule `no-raw-thread`", never an anonymous engine finding. |
| **Privacy by default** | Rules load from the repo's `.aegis/` dir (already versioned) and an optional org overlay (V5). **No rule, pack, or telemetry is fetched from a server Aegis operates.** YAML rules execute **no arbitrary code** — see §3. |
| **Static-first, AI-optional** | Custom rules are pure static matchers. AI is never required to load, match, or fix a custom rule. |

**The false-positive-aversion bias extends to user rules too:** when a custom matcher can't decide
(unresolved symbol, `KaErrorType`), it does **not** flag — the same conservative-miss contract the
built-in analyzers honor. A noisy user rule erodes trust in *all* findings, not just its own.

---

## 3. The central architectural decision — YAML-declarative, not code

The roadmap left the format open ("a YAML/Kotlin-DSL way"). **Recommendation: YAML-declarative for
V3.1–V3.2; compiled code is confined to the V3.4 SDK.**

| Option | Trade-off | Verdict |
|---|---|---|
| **YAML declarative** (recommended) | No compilation; safe to load from any repo (no arbitrary code execution = privacy/safety win); reviewable in a PR diff; bounded matcher vocabulary keeps authoring approachable | **V3.1 / V3.2** |
| **Kotlin-DSL / scripting** | Maximally expressive, but loading user Kotlin means compiling + executing untrusted code inside the IDE — a privacy-by-default and security violation; also a heavy authoring burden | rejected for the declarative tier |
| **Compiled `.jar` analyzer** | Full power of the `Analyzer` interface, but requires the user to build + trust a binary | **V3.4 only** — the explicit, opt-in power-user escape hatch |

The split is the design: **90% of custom rules are "flag PSI shape X, optionally apply
FixOperation Y" — fully serveable in safe declarative YAML. The 10% that need real logic drop to
the vetted `.jar` SDK,** which the user knowingly opts into. This keeps the common path safe and the
escape hatch honest. V3.1 designs the YAML tier in full (see the deep-dive spec).

---

## 4. The four pillars

### V3.1 — Custom rule authoring (the foundation)

- **What:** A per-project `.aegis/rules/*.yml` loader. Each rule declares a structured *matcher*
  (element kind + text/structural predicates over the existing PSI/analysis primitives), a
  *severity*, a message, and an optional *fix* expressed as a `FixPlan` of existing `FixOperation`s.
  Findings flow through the existing `AnalysisEngine` → inspection/Problems surfaces with `CUSTOM`
  provenance + rule id.
- **Why first:** every other pillar depends on the rule model and loader. Rule packs *are* curated
  V3.1 rules; the SDK *is* the escape hatch when V3.1's vocabulary is too small; fix-preview
  improvements show V3.1 fixes.
- **Deep-dive spec:** `docs/superpowers/specs/2026-06-16-v3-custom-rule-authoring-design.md`.
- **Key risk:** matcher expressiveness vs. safety — too small a vocabulary is useless, too large
  re-invents a query language. Mitigation: ship a **bounded v1 vocabulary** (the 6–8 predicates
  that cover the roadmap's own fixer candidates) and let the SDK absorb the long tail.

### V3.2 — Rule packs

- **What:** Curated, named, togglable bundles of V3.1 rules ("React strict", "Kotlin coroutines
  safety", "Node.js security"), enabled/disabled per project in Settings. Bundled with the plugin
  and/or loaded from the repo's `.aegis/`.
- **Why after V3.1:** a pack is just a versioned collection of V3.1 rule files plus enable metadata
  — it has no meaning until the rule model and loader exist.
- **Depends on:** V3.1 rule schema (stable) + the inspection-profile toggle surface (V2, built).
- **Key risk:** pack-vs-built-in finding collisions / duplicate flags. Mitigation: dedup by
  (file, range, rule-equivalence) at the `AnalysisEngine` merge step; packs never silently
  override a built-in severity without showing it.

### V3.3 — Fix-preview improvements

- **What:** Side-by-side before/after diff in the detail panel; keyboard-only apply/skip; batch-
  apply the same rule's fix across many files in one reviewed action.
- **Why here:** it is the UX layer over the (now larger, custom-inclusive) fix surface. Batch-apply
  is only safe *because* every fix — built-in, AI-supervised, or custom — is a verified `FixPlan`;
  the preview reuses `FixPlanPreview`.
- **Depends on:** `FixPlanPreview` (built), the V3.1 custom-fix path (so custom fixes preview too).
- **Key risk:** batch-apply amplifies any single bad fix across a repo. Mitigation: each file in a
  batch is independently verified by the existing verify gate; a single verify failure skips *that*
  file and reports it, never aborts or half-applies the batch.

### V3.4 — Analyzer-author SDK

- **What:** Document the existing `Analyzer` interface as a stable public contract; support loading
  a third-party analyzer from a `.jar` dropped into the project. The expressiveness escape hatch
  for rules YAML can't state.
- **Why last:** it is only worth the API-stability commitment once V3.1's declarative tier has
  proven *where* the ceiling actually is — premature SDK freezing locks in the wrong surface.
- **Depends on:** a settled `Analyzer` contract; V3.1 in the field (to learn the real gaps).
- **Key risk:** a public API is a forever-commitment + an untrusted-`.jar` execution surface.
  Mitigation: explicit, per-project opt-in to load external analyzer jars; the contract is
  versioned; SDK findings still carry distinct provenance.

---

## 5. Sequencing & rationale

```
V3.1 custom rules (foundation)
   └─> V3.2 rule packs        (curated V3.1 rules)
   └─> V3.3 fix-preview UX     (shows V3.1 + AI + built-in fixes)
   └─> V3.4 analyzer SDK       (escape hatch, after V3.1 reveals the ceiling)
```

- **V3.1 strictly first** — it is the schema + loader everything else consumes.
- **V3.2 and V3.3 are parallelizable** after V3.1 (packs don't depend on preview UX, or vice versa);
  build whichever marketplace feedback prioritizes.
- **V3.4 last** — defer the API-stability commitment until V3.1 has shown the real expressiveness
  ceiling. Freezing the SDK before that locks in the wrong surface (the roadmap's own
  "defensible re-orderings" note allows V3↔V4 slippage, but the SDK should not precede V3.1).

This ordering preserves the roadmap's "V3 before V4" rationale: custom rules feed V4's
breakpoint-aware relevance ranking, so V3.1 must land before V4 starts.

---

## 6. Success criteria for the frontier (per-pillar specs refine these)

1. A user can drop a `.yml` file in `.aegis/rules/`, get a flag on matching code, and (if the rule
   declares a deterministic fix) apply it via Alt+Enter — with the finding labelled `CUSTOM` + rule id.
2. No custom rule can produce a PSI-invalid fix or execute arbitrary code from YAML.
3. A noisy or undecidable custom matcher fails *closed* (does not flag), honoring conservative-miss.
4. Each pillar ships behind its own spec → plan → green-bar cycle; none regresses the V2 trust surfaces.

---

## 7. Non-goals (frontier-wide)

- **Cloud-hosted or Aegis-served rule registries** — rules live in the user's repo / org git (V5
  overlay), never a server Aegis runs.
- **Executing user Kotlin/JS from YAML** — declarative only; real code is the opt-in `.jar` SDK.
- **Auto-applying custom fixes without review** — the roadmap's standing non-goal holds.
- **Debug-runtime features** — that is V4; the frontier is pure static extensibility.
- **Designing V3.2–V3.4 in detail here** — each gets its own spec. This doc only sequences them.

---

## 8. Hand-off

The next concrete step on the frontier is the **V3.1 deep-dive**:
`docs/superpowers/specs/2026-06-16-v3-custom-rule-authoring-design.md`. It designs the YAML schema,
the bounded matcher vocabulary, the loader's integration with `AnalysisEngine`, the `FixPlan`-backed
fix path, and the provenance/privacy handling — then flows into its own writing-plans cycle.

This frontier work begins **only after** the v2-stabilization phase reaches its finish line and the
deferred ship-vs-build decision is made. It is sequenced, not scheduled.
