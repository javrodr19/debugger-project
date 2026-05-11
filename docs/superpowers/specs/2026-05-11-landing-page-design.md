# Aegis Debug — Landing page design

**Date:** 2026-05-11
**Status:** Draft — pending review
**Target URL:** `https://javrodr19.github.io/debugger-project/`
**Source location:** `site/` (new top-level folder)
**Author:** Javier Rodríguez

---

## 0. Summary

A single-page, hand-rolled static landing site for Aegis Debug, deployed via GitHub Actions to GitHub Pages. The page is **CV-first** — its primary job is to demonstrate engineering depth to recruiters and technical reviewers — with a secondary "install the plugin" path for developer-users who discover it.

The page consists of seven sections in a single long scroll: hero, what it does, how it works, three engineering case studies in a tabbed switcher, stats + roadmap teaser, about the author, and a footer with the install CTA. Visual identity mirrors the in-IDE plugin (deep navy `#0e1726`, cream `#f3ead3`, warm gold `#c9a96a` accent, IBM Plex Sans + Mono). The hero uses an asymmetric layout with a code panel showing real analyzer output, so the first screenful proves the product is built and works.

No JavaScript framework. No CSS framework. Hand-rolled `index.html` (~600 lines), `styles.css` (~400 lines, CSS custom properties for design tokens), `main.js` (~80 lines for tab switcher + scroll-triggered reveals + smooth-scroll-on-anchor-click). IBM Plex via Google Fonts CDN. Three icons inlined as SVG. Syntax highlighting hand-coloured via `<span>` classes — three short snippets total, not enough to justify Prism.js.

Deployment is a `.github/workflows/site.yml` workflow that triggers on pushes to `main` touching `site/**`, uploads to the `gh-pages` branch via `peaceiris/actions-gh-pages@v3`, and serves from there. Custom domain deferred. No analytics, no tracking — consistent with the plugin's "zero telemetry" positioning.

---

## 1. Motivation

This is Javier's first piece of public-facing self-marketing. The page has to carry the entire CV signal on its own — there is no broader portfolio site to fall back on. Aegis Debug is the right vehicle for that signal because the V1 → V1.5 history surfaces multiple distinct kinds of engineering work (a K2 Analysis API migration, a platform-internals classpath-collision debug, a deliberate pre-V2 god-class refactor), and the codebase carries enough structural taste (the `withKtAnalysis` chokepoint, the facade + collaborators pattern, the deterministic-first analyzer bias) to support deep-dive case studies that reviewers can read end-to-end.

A README on GitHub is not enough: it surfaces *what* the plugin does, but not the *why behind the choices*. A static page lets the deep-dive case studies sit one click from a CV link and read like considered engineering writeups instead of changelog entries.

The decision to publish the case studies on a single landing page (rather than a multi-page case-study site, or fragmenting them across blog posts) is deliberate. A reviewer scanning a CV link has roughly one scroll's worth of attention; consolidating the engineering payload onto one URL means a reviewer who arrives cold can finish the strongest case study before deciding whether to keep scrolling.

---

## 2. Scope decisions (locked in)

| # | Decision | Rationale |
|---|---|---|
| Q1 | **CV-first, product-second.** Hero leads with engineering positioning; install CTA is present but visually secondary to "Read the case studies." | Reviewer attention is the scarce resource; converting developer-users is a nice-to-have. |
| Q2 | **Single long scroll, one HTML file.** | A CV reviewer follows one URL; fragmenting across sub-pages loses the "strongest signal on one screen" property. Sub-pages can be added later if a case study becomes referenceable on its own. |
| Q3 | **Light interactivity — vanilla HTML/CSS + ~80 lines of vanilla JS.** No React, no Vite, no Tailwind. | The page's job is to *demonstrate engineering taste*. A 200kb React bundle for a static page would signal the opposite. Hand-rolled is short, fast, and identical to the existing JCEF webview's CSS muscle. |
| Q4 | **Plugin-aligned visual identity** (deep navy `#0e1726`, cream `#f3ead3`, warm gold `#c9a96a` accent, IBM Plex Sans + Mono). | Mirrors the actual plugin UI. Visitors who later install the plugin see the same identity carry through — evidence of deliberate brand work. |
| Q5 | **Personal-led framing** (C). Hero eyebrow reads "An IntelliJ IDEA plugin · by Javier Rodríguez". Name visible above the fold. | First public marketing piece; name surfacing in the hero is the strongest CV signal short of a co-equal byline. |
| Q6 | **No photo.** Initial monogram or text attribution only. | Author's stated preference. |
| Q7 | **GitHub and LinkedIn links only.** Email/Twitter/Mastodon excluded for now. | Author's stated preference; can be added later without redesign. |
| Q8 | **Asymmetric hero with code panel** (variant B). Real analyzer output (`AEG-NULL-KT-001`) above the fold. | First screenful proves the product is real and engineered. Variant A (centered, calmer) was the safer alternative; B was chosen because for a CV-first page the cost of looking too "developer-y" is lower than the cost of looking too generic. |
| Q9 | **Three case studies, presented in tabs** (not accordion). K2/Analysis-API migration (V1.3), classpath-collision debug (V1.1.2), pre-V2 god-class refactor (V1.5). | Three different *kinds* of work (migration / debugging / refactoring) — the diversity is itself a signal. Tabs encourage picking one to read deeply rather than skimming all three. |
| Q10 | **First-person prose** in case studies ("I traced through…", "the fix was…"). | Third-person ("the analyzer was rewritten") reads as if it could be anyone. First-person is load-bearing for CV credit assignment. |
| Q11 | **Roadmap teaser stays.** Small section near the bottom: "V1 → V1.5 in 6 weeks · V2 in design." | Reads as "this person plans deliberately," not "the project is unfinished." |
| Q12 | **Footer install CTA repeated.** A visitor who reads to the bottom has earned the CTA again. | Standard product-page pattern; no downside for CV-first either. |

---

## 3. Information architecture

Seven sections in a single long scroll. Anchor-linked nav in the header.

| # | Section | Anchor | Purpose | Approx. height |
|---|---|---|---|---|
| 1 | Hero | (top) | Position project + person in 4 seconds | 100vh |
| 2 | What it does | `#features` | Three feature cards + one large plugin screenshot | ~60vh |
| 3 | How it works | `#how-it-works` | Prose paragraph + architecture diagram | ~50vh |
| 4 | Engineering case studies | `#case-studies` | Tabbed switcher with three deep-dives | ~80vh (tab content auto-sized) |
| 5 | Stats + roadmap | `#stats` | Small grid + roadmap link to repo | ~30vh |
| 6 | About the author | `#about` | 3–4 sentence bio + GH/LinkedIn buttons | ~30vh |
| 7 | Footer | (bottom) | Repo link, version, install CTA, JetBrains Marketplace link | ~15vh |

Sticky nav at top with anchors to **Features · Case studies · About · GitHub ↗**. The "Case studies" anchor is intentionally promoted to nav-level visibility — it's the CV payload.

`★ Insight ─────────────────────────────────────`
Case studies sit at section 4, not section 6 — a recruiter who scrolls past two screens loses attention. Putting the depth signal *after* "what it does" framing means a reader encounters engineering content while still engaged. Section 6 would filter out everyone who hadn't already decided to read the whole page.
`─────────────────────────────────────────────────`

---

## 4. Hero (section 1)

### Layout

Asymmetric grid (variant B from brainstorming). Left column: eyebrow + headline + sub + CTA row + person row. Right column: code panel showing real analyzer output. On viewports < 720px, columns stack and the code panel drops below the CTAs.

### Copy

```
Eyebrow:     An IntelliJ IDEA plugin · by Javier Rodríguez
Headline:    Static-first debugging that never leaves your machine.
             (second clause "never leaves your machine" in warm gold #c9a96a)
Subhead:     Eleven deterministic analyzers. Five PSI-validated fixers.
             Optional local AI. Zero telemetry.
CTA primary: Install from Marketplace
CTA second:  Read the case studies → (anchor to #case-studies)
Person row:  GitHub · LinkedIn (small, low-opacity, below CTAs)
```

### Right-column code panel

Real `AEG-NULL-KT-001` analyzer output on a Kotlin source line. Hand-coloured spans (no Prism.js):

```kotlin
// UserService.kt — analyzer output
42  // inferred: String?
43  val name = user.name
44
    ▸ AEG-NULL-KT-001 · unsafe deref
    ✓ fix available · convert to ?.
```

Code panel has a 2px gold left border, dark navy background `#1a2436`, IBM Plex Mono. The `▸ AEG-NULL-KT-001` line uses the gold accent; `✓ fix available` uses a muted green.

### Nav

```
AEGIS DEBUG    Features  Case studies  About  GitHub ↗
```

`AEGIS DEBUG` is the brand mark (no logo image, just IBM Plex Sans 600). Right-side links are 12px, low-opacity, hover-brightens.

---

## 5. What it does (section 2)

Three feature cards in a row:

| Card | Title | Body (one sentence) |
|---|---|---|
| 1 | **Static-first analysis** | Eleven analyzers across TypeScript, JavaScript, Kotlin, and Java — null safety, state-before-init, async flow, complexity, circular dependencies, syntax, compilation. |
| 2 | **Deterministic fixers** | Five one-click fixers with PSI-validity guarantees: every produced edit parses or the fixer returns null and falls back to AI. |
| 3 | **Privacy by default** | No telemetry. No cloud uploads without explicit opt-in. Optional local Ollama or cloud OpenAI for AI augmentation. |

Below the cards: one large screenshot of the NeuroMap with realistic findings. Caption: *"NeuroMap — the project graph view, with per-file issue overlay."*

**Card styling:** Dark navy background, 1px border in `#243049`, subtle gold left border (2px) on hover, IBM Plex Sans for titles, lower-opacity body text.

---

## 6. How it works (section 3)

A short prose paragraph (~80 words) followed by an architecture diagram.

### Prose

> Aegis Debug runs every analysis through deterministic static analyzers first, then optionally augments with AI. Static findings are PSI-backed and type-aware (the Kotlin analyzers use the Kotlin Analysis API; the Java analyzers use PSI directly). AI is a fallback path — if you opt in. Every finding carries a provenance badge: engine-verified findings look different from local-AI findings, which look different from cloud-AI findings. The reader always knows what they're trusting.

### Diagram

Hand-rolled SVG (or inlined `<svg>`), ~600px wide. Flow:

```
  Source file
       │
       ▼
  PSI parse ──→ early pass (syntax, compile)
       │
       ▼
  Static analyzers ──→ deterministic findings (engine badge)
       │
       ▼  (optional, if enabled)
  AI augmentation ──→ local-AI or cloud-AI findings (provenance badge)
       │
       ▼
  Issue list + NeuroMap overlay
```

Diagram styling matches the plugin: dark navy nodes, cream edges, gold accent on the "deterministic findings" output (the path being emphasised).

---

## 7. Engineering case studies (section 4)

### Switcher mechanism

Three tabs above a single content panel. Click a tab to swap content (~80 lines of vanilla JS, no framework). Default: tab 1 active. Tab labels are short:

```
[ K2 migration ]   [ The hanging tests ]   [ Pre-V2 refactor ]
```

Active tab has a 2px gold underline; inactive tabs have low-opacity labels.

### Per-case-study template

Four beats, identical structure across all three:

1. **The problem** — one italicized hook sentence (~25 words)
2. **The investigation** — diagnostic path: what was tried, what was ruled out, what made the root cause visible (~80 words)
3. **The fix** — what changed, plus one code excerpt of ~10 lines (~60 words + code)
4. **Why it matters** — engineering principle behind the choice (~40 words)

First-person prose throughout ("I traced through…", "the fix was…").

### Case study 1 — Migrating the Kotlin analyzer to the K2 Analysis API

**Hook:** *Name-based matching was producing the wrong answers on smart-cast variables. The fix was to stop matching names and start asking the compiler.*

**Investigation focus:** Why V1.1.x's regex-and-name-table approach failed on `if (x != null) { x.foo() }`-style narrowing; why `BindingContext` was rejected (deprecated under K2); how the Analysis API's `analyze(ktFile) { … }` block exposes smart-cast and type-inference data directly.

**Fix focus:** The `withKtAnalysis(KtFile, KaSession.(KtFile) -> T): T?` chokepoint — a single helper that wraps `analyze { }`, rethrows `ProcessCanceledException`, returns null on any other Analysis API failure. Every Kotlin analyzer goes through it. Code excerpt: ~10-line snippet of `withKtAnalysis` itself + one call site.

**Why it matters:** Centralising the Analysis API entry point means new exceptions surface in one file. Catching `KaAnalysisNonPublicApiException` and `KaInvalidLifetimeOwnerAccessException` once at the chokepoint protects every analyzer downstream.

### Case study 2 — Why my tests were hanging at "indexing"

**Hook:** *A transitive `kotlinx-coroutines-core` was shadowing IntelliJ's forked variant of the same jar. Five `BasePlatformTestCase` tests had been silently disabled for a release cycle.*

**Investigation focus:** Symptom = `IndexingTestUtil.waitUntilIndexesAreReady` polled forever. First hypothesis = test framework wiring; ruled out by upgrading IPGP 2.2.1 → 2.14.0 and seeing the hang persist. Real root cause = stock `kotlinx-coroutines-core:1.9.0` (pulled transitively, including via `mockk`) won classpath resolution over IntelliJ's forked `kotlinx-coroutines-core-jvm-*-intellij.jar`, which exposes platform-only methods like `runBlockingWithParallelismCompensation` that `UnindexedFilesScanner` invokes during test setup. Stock jar produced `NoSuchMethodError` inside the scanning coroutine; the scanner caught it and polled instead of throwing.

**Fix focus:** Demote `kotlinx-coroutines-core` to `compileOnly`. Add a *configuration-level* `exclude` on `runtimeClasspath` + `testRuntimeClasspath` — per-dependency excludes are insufficient because `mockk` re-introduces the jar transitively. Code excerpt: ~8-line Gradle configuration block.

**Why it matters:** Test infrastructure failures that masquerade as "indexing slowness" can hide silent test-suite regressions across releases. The platform's forked-jar trick is invisible from outside; finding the root cause required reading Gradle dependency-resolution output and the IntelliJ Community source.

### Case study 3 — Splitting a 918-line god class before V2 instead of after

**Hook:** *Every V2 feature would land code inside or adjacent to `GhostDebuggerService`. Splitting now means V2 lands on a clean seam; splitting later means V3 inherits a worse problem.*

**Investigation focus:** What V2's roadmap actually adds (test-runner cross-check, debug-session cross-check, problems-tool-window integration, IntentionAction quick-fixes, streaming AI). Each of those features touches a different responsibility currently mashed together in `GhostDebuggerService`: analysis orchestration, VFS-listener plumbing, debug-event subscription, UI-event routing, report export. Mapping each V2 feature to its eventual home revealed four natural seams.

**Fix focus:** Facade pattern. `GhostDebuggerService` keeps its `getInstance(project)` entry point and the 6 external callers see identical method signatures, but the body becomes a thin delegator over four new project-scoped services: `AnalysisOrchestrator`, `UIEventRouter`, `FileChangeWatcher`, `DebugSessionCoordinator` (+ a `ReportExporter` helper). Total LOC: 918 → ~150 facade + four ~100-300 LOC collaborators. Code excerpt: the facade's `internal fun updateIssues(...)` mutator + one collaborator reading via `service.currentIssues`.

**Why it matters:** State ownership matters in a multi-collaborator system. Direct field assignment from a collaborator would re-introduce the scattered mutation the refactor was designed to eliminate. One writer (the facade), many readers (collaborators) — invariant documented in `CLAUDE.md` for V2's new collaborators to follow.

---

## 8. Stats + roadmap (section 5)

Small grid of five stats, then a one-line roadmap teaser linking to the repo roadmap doc.

| Stat | Value |
|---|---|
| Analyzers | 11 |
| Deterministic fixers | 5 |
| Languages | TypeScript · JavaScript · Kotlin · Java |
| Releases | V1.0 → V1.5 in under a month (2026-04-15 → 2026-05-11) |
| Tests | ~257 green · `verifyPlugin` Compatible across 4 IDE versions |

Roadmap line, in a separate row:

> **What's next.** V2 is in design — language breadth (Python), IDE-native integration (problems tool window, `Alt+Enter`), runtime-confirmation via test/debug cross-check. See [the roadmap →](https://github.com/javrodr19/debugger-project/blob/main/docs/aegis_debug_roadmap_v2_to_v5.md).

---

## 9. About the author (section 6)

Short bio block, no photo. Layout: left-aligned text, GH/LinkedIn buttons below.

### Bio (~4 sentences, placeholder pending author input)

> I'm Javier Rodríguez. I build developer tools — currently a JetBrains plugin for static analysis, previously [TBD: prior work or focus area]. I care about deterministic-first systems, deliberate API design, and writing code that reads well a year after I wrote it. I'm open to roles in [TBD: backend platform / dev tooling / IDE / language tooling] and to collaborations on Aegis Debug. **Find me on [GitHub](https://github.com/javrodr19) or [LinkedIn](TBD).**

**Open questions before launch:** (1) Prior work or focus area to fill the second sentence. (2) Role-type preferences for the third sentence (or strike it if not seeking). (3) LinkedIn URL.

### Buttons

Two pill-shaped buttons: `GitHub →` and `LinkedIn →`. Same styling as hero secondary CTA but slightly larger.

---

## 10. Footer (section 7)

Three rows, all small (12px, low-opacity):

1. **Install row.** "Aegis Debug is on the [JetBrains Marketplace](TBD-marketplace-url) · [download v1.5.0 from GitHub](https://github.com/javrodr19/debugger-project/releases)."
2. **Links row.** Repo · CHANGELOG · Spec docs · License (MIT — confirm).
3. **Sign-off row.** "Made by Javier Rodríguez · 2026 · This page has zero analytics."

---

## 11. File layout

```
debugger-project/
├── site/                            ← NEW
│   ├── index.html                   ~600 lines including inlined SVGs
│   ├── styles.css                   ~400 lines, CSS custom properties
│   ├── main.js                      ~80 lines (tab switcher + reveals + smooth-scroll)
│   ├── assets/
│   │   ├── plugin-icon.svg          copied from src/main/resources/META-INF/
│   │   ├── plugin-icon-dark.svg     copied from src/main/resources/META-INF/
│   │   ├── neuromap-hero.png        screenshot — to be captured before launch
│   │   └── architecture.svg         hand-drawn or hand-rolled diagram
│   ├── og-image.png                 1200×630 social-card image
│   └── README.md                    "how to preview locally" (one paragraph)
├── .github/workflows/site.yml       ← NEW (~20 lines)
└── .gitignore                       ← ADD .superpowers/ (one-line addition)
```

The existing `docs/` folder stays untouched. The existing `webview/` folder (in-IDE React app) is unrelated and stays untouched.

---

## 12. Tech choices

| Decision | Choice | Why not alternative |
|---|---|---|
| CSS framework | Hand-rolled with CSS custom properties (`:root { --c-navy: #0e1726; --c-cream: #f3ead3; --c-gold: #c9a96a; --font-sans: 'IBM Plex Sans'; --font-mono: 'IBM Plex Mono'; }`) | Tailwind via CDN ships a runtime CSS generator. Tailwind via build pulls in Vite + node_modules for one page. Hand-rolled is ~400 lines, fast, identical to the existing JCEF webview's CSS muscle. |
| JS framework | None — vanilla ES modules in `main.js` | Three interactive pieces (tab switcher ~30 lines, scroll-reveal IntersectionObserver ~25 lines, smooth-scroll-on-anchor ~10 lines) total ~80 lines. A framework would be more code than it replaces. |
| Syntax highlighting | Hand-coloured `<span class="kw">…</span>`, `<span class="comment">…</span>`, `<span class="rule">…</span>`. CSS in `styles.css`. | Prism.js is 5kb + a language grammar. For three short snippets, hand-coloring is faster than configuring it. |
| Fonts | IBM Plex Sans (400/500/600/700) + IBM Plex Mono (400/500) via Google Fonts (one `<link>` in `<head>`, `display=swap`) | Self-hosting woff2 would ship ~300kb of font files for no perceived benefit on a single-page site. |
| Icons | Three inlined SVGs (GitHub, LinkedIn, external-link arrow) | Three icons doesn't justify an npm install. |
| Image format | PNG for screenshot (lossless, sharp at retina); SVG for diagrams + icons | WebP is smaller but introduces fallback complexity for one image. Not worth it at this scale. |

`★ Insight ─────────────────────────────────────`
The "none, none, none" tech stack isn't dogma — the page's job is to demonstrate engineering taste. A reviewer who opens DevTools and sees only three HTTP 200s (HTML, CSS, JS) takes that as a signal of considered tooling choices. A 200kb React bundle on a static page would signal the opposite, and not even subtly.
`─────────────────────────────────────────────────`

### JavaScript modules

`main.js` has three named functions, each exported only if needed:

```javascript
function initTabSwitcher() { /* delegated click listener on .tabs */ }
function initScrollReveals() { /* IntersectionObserver on .reveal */ }
function initSmoothScroll() { /* preventDefault + scrollIntoView on a[href^="#"] */ }
```

Initialised on `DOMContentLoaded`. No bundler, no transpilation — ES2020 features only (IntersectionObserver, optional chaining, template literals) which are supported in every browser that runs IntelliJ's bundled JCEF or any 2022+ browser.

### CSS organization

`styles.css` is organized into seven blocks, each preceded by a single-line comment header:

```
/* 1. tokens          */ :root { … }
/* 2. reset + base    */ *, html, body { … }
/* 3. nav + hero      */ .hero, .nav { … }
/* 4. features        */ .features, .feature-card { … }
/* 5. how-it-works    */ .how-it-works, .architecture-svg { … }
/* 6. case studies    */ .case-studies, .tabs, .tab-content { … }
/* 7. footer          */ .footer { … }
```

No `@apply`, no preprocessor, no PostCSS. Direct CSS.

---

## 13. Deployment

GitHub Actions workflow `.github/workflows/site.yml`. Triggers on pushes to `main` that touch `site/**` or the workflow file itself. Roughly:

```yaml
name: Deploy site
on:
  push:
    branches: [main]
    paths:
      - 'site/**'
      - '.github/workflows/site.yml'

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: peaceiris/actions-gh-pages@v3
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: ./site
```

GitHub Pages settings (one-time): Source = `gh-pages` branch, folder = `/`. The implementation plan will include the click-through.

**URL:** `https://javrodr19.github.io/debugger-project/`

**Custom domain:** Deferred. Adding one later requires only a `CNAME` file in `site/` and a DNS change — no rewrite.

**SEO + social:** `<meta>` tags for description, OpenGraph (`og:title`, `og:description`, `og:image` → `og-image.png`), and Twitter cards. No JSON-LD structured data (premature).

**Analytics:** None. Consistent with the plugin's "zero telemetry" headline; also avoids GDPR cookie banners.

---

## 14. Assets to capture before launch

These are not blockers for writing the spec or the implementation plan — they can be produced in parallel.

| Asset | Source | Notes |
|---|---|---|
| `neuromap-hero.png` | Screenshot from the actual plugin running on a demo project | ~1600×1000 retina-quality. Show NeuroMap with realistic findings — null-safety issue, complexity hotspot, circular dependency edge. |
| `architecture.svg` | Hand-rolled SVG (inlined in `index.html` so it picks up CSS custom properties for color) | ~600×400. Style must match the page (dark navy nodes, cream edges, gold accent on the "deterministic findings" path). |
| `og-image.png` | Generated from a 1200×630 hero variant | Social card preview. Show the headline + brand mark, no nav. |
| `plugin-icon.svg`, `plugin-icon-dark.svg` | Already exist in `src/main/resources/META-INF/` | Copy into `site/assets/`. |

---

## 15. Out of scope

These are explicitly deferred to keep this release small:

- **Multi-page case-study site.** If any single case study becomes referenceable on its own (e.g. cited in a talk), it can graduate to a dedicated page. For now, all three live on the landing page.
- **Blog / changelog feed.** CHANGELOG.md in the repo is sufficient. A blog implies recurring content; this page is one-shot.
- **Newsletter signup.** No mailing list to send to.
- **Pricing section.** Plugin is free.
- **Testimonials / quotes.** None to quote yet.
- **Internationalization.** English only.
- **Dark/light mode toggle.** The page is dark by design (matches the plugin). No light variant.
- **Custom domain.** Deferred; default GitHub Pages URL is fine for launch.
- **Analytics.** Excluded by principle.
- **A11y audit.** Standard semantic HTML, alt text on the screenshot, sufficient contrast (4.5:1 minimum on cream-on-navy) — but no formal WCAG AA audit, no screen-reader testing pass. Worth adding in a follow-up.

---

## 16. Open questions to resolve before implementation

1. **LinkedIn URL** for the hero and About section.
2. **Bio content** — three blanks in section 9: prior work / focus area, role-type preferences (or strike that sentence), and confirmation of the LinkedIn URL.
3. **JetBrains Marketplace URL** for the Install CTA — the plugin must be published there before this URL can be linked. If unpublished at launch, the CTA can temporarily link to the GitHub releases page and be swapped later.
4. **License confirmation** for the footer ("MIT" assumed from `LICENSE` file — confirm).

None of these block writing the implementation plan; they block the final copy pass before launch.

---

## 17. Success criteria

The page is considered done when:

- A reviewer who lands cold from a CV link can finish at least one case study in under 90 seconds and leave with an accurate impression of the project's depth.
- The page loads under 200kb total (excluding the one screenshot, which is allowed to be larger).
- DevTools network panel shows ≤ 5 HTTP requests on first load (HTML, CSS, JS, fonts CSS from Google, one screenshot).
- The page renders correctly in Chrome, Firefox, and Safari at desktop and mobile widths.
- A new visitor can find the GitHub repo within two clicks from any section.
- A developer-user can find install instructions within one scroll of the hero.
- Lighthouse performance ≥ 95 on desktop (mobile is acceptable down to 85 given the screenshot).
