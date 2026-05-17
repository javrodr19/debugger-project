# Aegis Debug — Landing page implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and deploy a single-page, CV-first landing site for Aegis Debug at `https://javrodr19.github.io/debugger-project/`.

**Architecture:** Hand-rolled static site in a new top-level `site/` folder. One `index.html` containing all seven sections, one `styles.css` with CSS-custom-property design tokens, one `main.js` with three vanilla-JS modules (tab switcher, scroll reveals, smooth scroll). No framework, no bundler, no preprocessor. Fonts via Google Fonts CDN. Deploy via GitHub Actions to a `gh-pages` branch.

**Tech Stack:** HTML5, CSS3 (custom properties, grid, flexbox, `prefers-reduced-motion`), ES2020 vanilla JS (IntersectionObserver, ES modules optional), IBM Plex Sans + IBM Plex Mono via Google Fonts. GitHub Actions for CI/CD.

**Spec reference:** `docs/superpowers/specs/2026-05-11-landing-page-design.md`

---

## File structure

```
debugger-project/
├── site/                          ← NEW (this plan creates everything here)
│   ├── index.html                 ~600 lines, all seven sections inline
│   ├── styles.css                 ~400 lines, organized in 7 commented blocks
│   ├── main.js                    ~80 lines, three named init functions
│   ├── assets/
│   │   ├── plugin-icon.svg        copied from src/main/resources/META-INF/
│   │   ├── plugin-icon-dark.svg   copied from src/main/resources/META-INF/
│   │   ├── neuromap-hero.png      screenshot placeholder until captured
│   │   └── og-image.png           1200×630 social card placeholder
│   └── README.md                  one paragraph on local preview
└── .github/workflows/site.yml     ← NEW (~25 lines)
```

`architecture.svg` is **inlined** in `index.html` so it can use CSS custom properties for color (per spec §14).

---

## Acceptance criteria (from spec §17)

Every task ends with a verification step. Final acceptance against:

1. Page renders in Chrome, Firefox, Safari at 375px / 768px / 1280px.
2. Total payload < 200 kB excluding `neuromap-hero.png`.
3. ≤ 5 HTTP requests on first load (HTML, CSS, JS, fonts CSS, one screenshot).
4. Lighthouse desktop performance ≥ 95; mobile ≥ 85.
5. New visitor can reach the GitHub repo within 2 clicks from any section.
6. A developer-user can find install instructions within one scroll of the hero.
7. A reviewer can finish at least one case study in under 90 seconds.

---

## Task 1: Scaffold the site/ folder and local preview

**Files:**
- Create: `site/index.html`
- Create: `site/styles.css`
- Create: `site/main.js`
- Create: `site/README.md`
- Create: `site/assets/.gitkeep`

- [ ] **Step 1: Create the four scaffold files.**

`site/index.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Aegis Debug — scaffold</title>
</head>
<body>
  <h1>Aegis Debug — scaffold</h1>
  <p>If you can read this, local preview works.</p>
</body>
</html>
```

`site/styles.css`:

```css
/* placeholder — populated in Task 2 */
```

`site/main.js`:

```javascript
/* placeholder — populated in Task 7 */
```

`site/README.md`:

```markdown
# Aegis Debug landing site

Hand-rolled static site. To preview locally:

```bash
python3 -m http.server 8000 --directory site
```

Then open <http://localhost:8000>.

No build step. No dependencies. Deployed via `.github/workflows/site.yml` to GitHub Pages on every push to `main` that touches `site/**`.
```

`site/assets/.gitkeep`:

```
```

- [ ] **Step 2: Verify the assets directory exists.**

Run: `ls -la site/assets/`
Expected output: `.gitkeep` listed.

- [ ] **Step 3: Verify local preview.**

Run: `python3 -m http.server 8000 --directory site` in one terminal.
In another terminal: `curl -s http://localhost:8000 | head -5`
Expected output: First five lines of the HTML doctype/head.

Stop the server (Ctrl+C).

- [ ] **Step 4: Commit.**

```bash
git add site/
git commit -m "feat(site): scaffold site/ folder with local-preview README

Single landing page for Aegis Debug deployed to GitHub Pages.
Hand-rolled (no framework, no bundler). Spec at
docs/superpowers/specs/2026-05-11-landing-page-design.md."
```

---

## Task 2: HTML shell + CSS tokens + sticky nav

This task sets up the entire HTML skeleton with semantic landmarks and the CSS design-token foundation. All subsequent tasks add content to existing landmarks.

**Files:**
- Modify: `site/index.html` (complete rewrite)
- Modify: `site/styles.css` (replace placeholder)

- [ ] **Step 1: Write the full HTML shell.**

Replace `site/index.html` with:

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="Aegis Debug — a privacy-first static-analysis plugin for JetBrains IDEs. Eleven deterministic analyzers, five PSI-validated fixers, optional local AI, zero telemetry. By Javier Rodríguez.">

  <meta property="og:type" content="website">
  <meta property="og:title" content="Aegis Debug — static-first debugging for JetBrains IDEs">
  <meta property="og:description" content="Eleven deterministic analyzers. Five PSI-validated fixers. Optional local AI. Zero telemetry.">
  <meta property="og:image" content="assets/og-image.png">
  <meta property="og:url" content="https://javrodr19.github.io/debugger-project/">

  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:title" content="Aegis Debug — static-first debugging for JetBrains IDEs">
  <meta name="twitter:description" content="Eleven deterministic analyzers. Five PSI-validated fixers. Optional local AI. Zero telemetry.">
  <meta name="twitter:image" content="assets/og-image.png">

  <title>Aegis Debug — static-first debugging for JetBrains IDEs</title>

  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap" rel="stylesheet">

  <link rel="stylesheet" href="styles.css">
  <link rel="icon" type="image/svg+xml" href="assets/plugin-icon.svg">
</head>
<body>
  <header class="site-nav">
    <a class="brand" href="#top">AEGIS DEBUG</a>
    <nav class="nav-links" aria-label="Primary">
      <a href="#features">Features</a>
      <a href="#case-studies">Case studies</a>
      <a href="#about">About</a>
      <a href="https://github.com/javrodr19/debugger-project" rel="noopener" target="_blank">GitHub <span aria-hidden="true">↗</span></a>
    </nav>
  </header>

  <main id="top">
    <section class="hero" aria-labelledby="hero-headline">
      <!-- Hero content added in Task 3 -->
    </section>

    <section id="features" class="features" aria-labelledby="features-heading">
      <!-- Features content added in Task 4 -->
    </section>

    <section id="how-it-works" class="how-it-works" aria-labelledby="how-heading">
      <!-- How-it-works content added in Task 5 -->
    </section>

    <section id="case-studies" class="case-studies" aria-labelledby="cs-heading">
      <!-- Case-studies content added in Tasks 6-8 -->
    </section>

    <section id="stats" class="stats" aria-labelledby="stats-heading">
      <!-- Stats content added in Task 9 -->
    </section>

    <section id="about" class="about" aria-labelledby="about-heading">
      <!-- About content added in Task 10 -->
    </section>
  </main>

  <footer class="site-footer">
    <!-- Footer content added in Task 10 -->
  </footer>

  <script src="main.js" defer></script>
</body>
</html>
```

- [ ] **Step 2: Write CSS tokens, reset, and nav styles.**

Replace `site/styles.css` with:

```css
/* 1. tokens ------------------------------------------------------------ */
:root {
  --c-navy:        #0e1726;
  --c-navy-deep:   #0a1120;
  --c-panel:       #1a2436;
  --c-border:      #243049;
  --c-cream:       #f3ead3;
  --c-cream-soft:  #f3ead3cc;
  --c-cream-faint: #f3ead380;
  --c-gold:        #c9a96a;
  --c-gold-soft:   #c9a96a22;
  --c-pass-green:  #8db580;
  --c-comment:     #6b7488;
  --c-muted:       #4a5570;

  --font-sans: 'IBM Plex Sans', system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
  --font-mono: 'IBM Plex Mono', ui-monospace, SFMono-Regular, Menlo, monospace;

  --shell-max:   1180px;
  --shell-pad-x: clamp(20px, 4vw, 48px);

  --radius:      4px;
  --radius-card: 6px;

  --t-fast: 120ms ease-out;
  --t-med:  220ms ease-out;
}

/* 2. reset + base ----------------------------------------------------- */
*, *::before, *::after { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
html { scroll-behavior: smooth; }
body {
  background: var(--c-navy);
  color: var(--c-cream);
  font-family: var(--font-sans);
  font-size: 16px;
  line-height: 1.55;
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
}
img, svg { max-width: 100%; display: block; }
a { color: inherit; text-decoration: none; }
button { font: inherit; cursor: pointer; }
h1, h2, h3, h4, p { margin: 0; }

@media (prefers-reduced-motion: reduce) {
  html { scroll-behavior: auto; }
  *, *::before, *::after { animation: none !important; transition: none !important; }
}

main { display: block; }
section { padding-block: clamp(56px, 8vw, 96px); padding-inline: var(--shell-pad-x); }
section > .shell { max-width: var(--shell-max); margin-inline: auto; }

/* 3. nav -------------------------------------------------------------- */
.site-nav {
  position: sticky; top: 0; z-index: 50;
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px var(--shell-pad-x);
  background: rgba(14, 23, 38, 0.85);
  backdrop-filter: saturate(140%) blur(8px);
  -webkit-backdrop-filter: saturate(140%) blur(8px);
  border-bottom: 1px solid var(--c-border);
}
.site-nav .brand {
  font-weight: 600; letter-spacing: 0.06em;
  font-size: 14px;
}
.site-nav .nav-links {
  display: flex; gap: 24px;
  font-size: 13px;
  color: var(--c-cream-soft);
}
.site-nav .nav-links a {
  transition: color var(--t-fast);
}
.site-nav .nav-links a:hover { color: var(--c-cream); }

@media (max-width: 640px) {
  .site-nav .nav-links { gap: 14px; font-size: 12px; }
}
```

- [ ] **Step 3: Preview locally.**

Run: `python3 -m http.server 8000 --directory site`
Open <http://localhost:8000> in a browser.

Expected: A dark-navy page with a sticky nav at the top showing "AEGIS DEBUG" on the left and four anchor links on the right ("Features · Case studies · About · GitHub ↗"). Body is empty below the nav.

Verify in DevTools: only `index.html`, `styles.css`, and the Google Fonts request are in the Network panel. (`main.js` returns the placeholder, also fine.)

- [ ] **Step 4: Commit.**

```bash
git add site/index.html site/styles.css
git commit -m "feat(site): HTML shell, CSS tokens, sticky nav

Semantic landmarks for all seven sections (empty bodies, content lands
in tasks 3-10). Design tokens as CSS custom properties: navy/cream/gold,
IBM Plex Sans+Mono via Google Fonts CDN. Reset and base styles. Sticky
nav with brand mark and primary anchor links. Respects
prefers-reduced-motion."
```

---

## Task 3: Hero section — asymmetric layout with code panel

**Files:**
- Modify: `site/index.html` (replace `<!-- Hero content added in Task 3 -->`)
- Modify: `site/styles.css` (append hero block)

- [ ] **Step 1: Add hero HTML inside `<section class="hero">`.**

Find this block in `site/index.html`:

```html
    <section class="hero" aria-labelledby="hero-headline">
      <!-- Hero content added in Task 3 -->
    </section>
```

Replace the comment with:

```html
      <div class="shell hero-grid">
        <div class="hero-text">
          <p class="eyebrow">An IntelliJ IDEA plugin <span class="dot" aria-hidden="true">·</span> by Javier Rodríguez</p>
          <h1 id="hero-headline" class="headline">
            Static-first debugging that <span class="accent">never leaves your machine.</span>
          </h1>
          <p class="sub">
            Eleven deterministic analyzers. Five PSI-validated fixers.
            Optional local AI. Zero telemetry.
          </p>
          <div class="cta-row">
            <a class="cta cta-primary" href="https://plugins.jetbrains.com/" rel="noopener" target="_blank">Install from Marketplace</a>
            <a class="cta cta-secondary" href="#case-studies">Read the case studies <span aria-hidden="true">→</span></a>
          </div>
          <p class="person-row">
            <a href="https://github.com/javrodr19" rel="noopener" target="_blank">GitHub</a>
            <span class="sep" aria-hidden="true">·</span>
            <a href="#" rel="noopener" target="_blank" data-linkedin>LinkedIn</a>
          </p>
        </div>

        <aside class="code-panel" aria-label="Example analyzer output">
          <div class="code-panel-hdr">UserService.kt — analyzer output</div>
          <pre class="code-panel-body"><span class="lineno">42</span><span class="comment">// inferred: String?</span>
<span class="lineno">43</span><span class="kw">val</span> name = user.name
<span class="lineno">44</span>
<span class="lineno"></span><span class="rule">▸ AEG-NULL-KT-001</span> · unsafe deref
<span class="lineno"></span><span class="pass">✓ fix available</span> · convert to <span class="kw">?.</span></pre>
        </aside>
      </div>
```

Note the `data-linkedin` attribute on the LinkedIn link: this is intentional. The real URL is an open question in the spec (§16, item 1); a final step replaces this attribute with the real URL before launch.

- [ ] **Step 2: Append hero CSS to `site/styles.css`.**

Append to `site/styles.css`:

```css
/* 4. hero ------------------------------------------------------------- */
.hero { padding-top: clamp(48px, 8vw, 80px); padding-bottom: clamp(64px, 10vw, 120px); }
.hero-grid {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: clamp(24px, 4vw, 56px);
  align-items: center;
}
@media (max-width: 720px) {
  .hero-grid { grid-template-columns: 1fr; gap: 32px; }
}

.eyebrow {
  font-size: 11px; letter-spacing: 0.14em; text-transform: uppercase;
  color: var(--c-gold); font-weight: 500;
  margin-bottom: 16px;
}
.eyebrow .dot { opacity: 0.6; margin: 0 6px; }

.headline {
  font-size: clamp(28px, 4.2vw, 44px);
  font-weight: 600; line-height: 1.12;
  letter-spacing: -0.015em;
  margin-bottom: 18px;
  max-width: 18ch;
}
.headline .accent { color: var(--c-gold); }

.sub {
  font-size: 15px; line-height: 1.6;
  color: var(--c-cream-soft);
  max-width: 46ch;
  margin-bottom: 26px;
}

.cta-row { display: flex; gap: 10px; flex-wrap: wrap; }
.cta {
  display: inline-flex; align-items: center;
  font-size: 13px; padding: 10px 16px;
  border-radius: var(--radius); font-weight: 500;
  letter-spacing: 0.01em;
  transition: background var(--t-fast), color var(--t-fast), border-color var(--t-fast);
}
.cta-primary { background: var(--c-cream); color: var(--c-navy); }
.cta-primary:hover { background: #fff; }
.cta-secondary { border: 1px solid var(--c-cream-faint); color: var(--c-cream); }
.cta-secondary:hover { border-color: var(--c-cream); }

.person-row {
  margin-top: 22px;
  display: flex; gap: 10px; align-items: center;
  font-size: 12px; color: var(--c-cream-soft);
}
.person-row a:hover { color: var(--c-gold); }
.person-row .sep { opacity: 0.45; }

.code-panel {
  background: var(--c-panel);
  border: 1px solid var(--c-border);
  border-left: 2px solid var(--c-gold);
  border-radius: var(--radius);
  padding: 16px 18px;
  font-family: var(--font-mono);
  font-size: 12px; line-height: 1.7;
  color: #d6c89c;
  overflow-x: auto;
}
.code-panel-hdr {
  font-size: 10px; letter-spacing: 0.1em; text-transform: uppercase;
  color: var(--c-cream-faint);
  margin-bottom: 10px;
}
.code-panel-body { margin: 0; font-family: inherit; white-space: pre; }
.code-panel .lineno { color: var(--c-muted); user-select: none; display: inline-block; width: 2.5ch; margin-right: 14px; }
.code-panel .comment { color: var(--c-comment); }
.code-panel .kw      { color: #e0b677; }
.code-panel .rule    { color: var(--c-gold); }
.code-panel .pass    { color: var(--c-pass-green); }
```

- [ ] **Step 3: Reload local preview and inspect.**

Refresh <http://localhost:8000>.

Expected at desktop width (≥ 1024px):
- Gold eyebrow line ("An IntelliJ IDEA plugin · by Javier Rodríguez")
- Large headline with the second clause in gold
- Subhead in cream-soft
- Two CTAs (cream primary, outlined secondary)
- Small "GitHub · LinkedIn" row below
- On the right: a dark code panel with the analyzer output, gold left border, hand-coloured spans

Expected at mobile width (375px in DevTools responsive mode):
- Columns stack: text on top, code panel below
- All text wraps cleanly, no horizontal scroll

- [ ] **Step 4: Verify "Read the case studies →" anchor jumps to `#case-studies`.**

Click the secondary CTA. The page should attempt to jump to `#case-studies` (which is empty for now — that's fine).

- [ ] **Step 5: Commit.**

```bash
git add site/index.html site/styles.css
git commit -m "feat(site): hero with asymmetric layout and code panel

Eyebrow + headline (gold accent on second clause) + sub + two CTAs +
GitHub/LinkedIn row in the left column. Code panel on the right shows
real AEG-NULL-KT-001 analyzer output, hand-coloured spans (no Prism).
Stacks under 720px. LinkedIn URL flagged with data-linkedin attribute
for the pre-launch copy pass (spec §16 open question 1)."
```

---

## Task 4: Features section

Three feature cards in a row, with one large screenshot placeholder below.

**Files:**
- Modify: `site/index.html` (replace `<!-- Features content added in Task 4 -->`)
- Modify: `site/styles.css` (append features block)
- Create: `site/assets/neuromap-hero.png` (placeholder)

- [ ] **Step 1: Generate a placeholder screenshot.**

Until the real NeuroMap screenshot is captured (spec §14), use a 1600×1000 placeholder PNG with the project's navy background. Generate it with ImageMagick:

```bash
convert -size 1600x1000 xc:'#0e1726' \
  -gravity center -fill '#f3ead380' -pointsize 36 \
  -font 'IBM-Plex-Sans' -annotate +0+0 'NeuroMap screenshot — placeholder' \
  site/assets/neuromap-hero.png
```

If `IBM-Plex-Sans` isn't installed, omit the `-font` line; ImageMagick will fall back to a default font.

If ImageMagick isn't available, run instead:

```bash
python3 -c "
from PIL import Image, ImageDraw
img = Image.new('RGB', (1600, 1000), '#0e1726')
d = ImageDraw.Draw(img)
d.text((800, 500), 'NeuroMap screenshot — placeholder', fill='#f3ead380', anchor='mm')
img.save('site/assets/neuromap-hero.png')
"
```

Verify: `ls -la site/assets/neuromap-hero.png` shows a file > 1 KB.

- [ ] **Step 2: Replace the features HTML.**

Find this block:

```html
    <section id="features" class="features" aria-labelledby="features-heading">
      <!-- Features content added in Task 4 -->
    </section>
```

Replace the comment with:

```html
      <div class="shell">
        <h2 id="features-heading" class="section-title">What it does</h2>
        <p class="section-lead">Three things, deliberately scoped. Aegis Debug runs every analysis through deterministic static analyzers first, then optionally augments with AI.</p>

        <div class="feature-grid">
          <article class="feature-card">
            <div class="feature-badge">01</div>
            <h3>Static-first analysis</h3>
            <p>Eleven analyzers across TypeScript, JavaScript, Kotlin, and Java — null safety, state-before-init, async flow, complexity, circular dependencies, syntax, and compilation errors.</p>
          </article>

          <article class="feature-card">
            <div class="feature-badge">02</div>
            <h3>Deterministic fixers</h3>
            <p>Five one-click fixers with PSI-validity guarantees: every produced edit parses or the fixer returns null and falls back to AI.</p>
          </article>

          <article class="feature-card">
            <div class="feature-badge">03</div>
            <h3>Privacy by default</h3>
            <p>No telemetry. No cloud uploads without explicit opt-in. Optional local Ollama or cloud OpenAI for AI augmentation. API keys stored in IntelliJ PasswordSafe.</p>
          </article>
        </div>

        <figure class="screenshot">
          <img src="assets/neuromap-hero.png" alt="NeuroMap view of an Aegis Debug analysis — project graph with per-file issue overlay" loading="lazy" width="1600" height="1000">
          <figcaption>NeuroMap — the project graph view, with per-file issue overlay.</figcaption>
        </figure>
      </div>
```

- [ ] **Step 3: Append features CSS.**

Append to `site/styles.css`:

```css
/* 5. features --------------------------------------------------------- */
.section-title {
  font-size: clamp(22px, 3vw, 30px);
  font-weight: 600;
  letter-spacing: -0.01em;
  margin-bottom: 10px;
}
.section-lead {
  font-size: 15px; color: var(--c-cream-soft);
  max-width: 60ch;
  margin-bottom: 40px;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 18px;
  margin-bottom: 48px;
}
@media (max-width: 900px) {
  .feature-grid { grid-template-columns: 1fr; }
}

.feature-card {
  background: var(--c-panel);
  border: 1px solid var(--c-border);
  border-left: 2px solid transparent;
  border-radius: var(--radius-card);
  padding: 22px 22px 24px;
  transition: border-left-color var(--t-med), transform var(--t-med);
}
.feature-card:hover {
  border-left-color: var(--c-gold);
  transform: translateY(-2px);
}
.feature-badge {
  font-family: var(--font-mono);
  font-size: 11px; letter-spacing: 0.1em;
  color: var(--c-gold); margin-bottom: 16px;
}
.feature-card h3 {
  font-size: 17px; font-weight: 600;
  margin-bottom: 10px;
}
.feature-card p {
  font-size: 14px; line-height: 1.6;
  color: var(--c-cream-soft);
}

.screenshot {
  margin: 0;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-card);
  overflow: hidden;
}
.screenshot img {
  width: 100%; height: auto;
  display: block;
}
.screenshot figcaption {
  font-size: 12px; color: var(--c-cream-faint);
  padding: 10px 14px;
  border-top: 1px solid var(--c-border);
  background: var(--c-navy-deep);
}
```

- [ ] **Step 4: Reload and verify.**

Refresh <http://localhost:8000>. Scroll to "What it does" (or click Features in nav).

Expected:
- Section title "What it does" + lead paragraph
- Three cards in a row at desktop (stacked under 900px)
- Each card has a gold "01/02/03" badge, a title, a body paragraph, and on hover a gold left border + lift
- Below the cards: the placeholder screenshot with a caption

- [ ] **Step 5: Commit.**

```bash
git add site/index.html site/styles.css site/assets/neuromap-hero.png
git commit -m "feat(site): features section with three cards and screenshot

Three cards: static-first analysis, deterministic fixers, privacy by
default. Each card lifts and surfaces a gold left border on hover.
Placeholder NeuroMap screenshot below; real screenshot captured before
launch (spec §14)."
```

---

## Task 5: How it works — prose + inlined architecture SVG

**Files:**
- Modify: `site/index.html` (replace `<!-- How-it-works content added in Task 5 -->`)
- Modify: `site/styles.css` (append how-it-works block)

- [ ] **Step 1: Replace the how-it-works HTML.**

Find:

```html
    <section id="how-it-works" class="how-it-works" aria-labelledby="how-heading">
      <!-- How-it-works content added in Task 5 -->
    </section>
```

Replace the comment with:

```html
      <div class="shell">
        <h2 id="how-heading" class="section-title">How it works</h2>

        <div class="how-grid">
          <div class="how-prose">
            <p>
              Aegis Debug runs every analysis through deterministic static analyzers first, then optionally augments with AI. Static findings are PSI-backed and type-aware — the Kotlin analyzers use the Kotlin Analysis API; the Java analyzers use PSI directly.
            </p>
            <p>
              AI is a fallback path, only if you opt in. Every finding carries a provenance badge so engine-verified results look distinct from local-AI or cloud-AI results. The reader always knows what they're trusting.
            </p>
          </div>

          <figure class="architecture">
            <svg viewBox="0 0 560 360" xmlns="http://www.w3.org/2000/svg" role="img" aria-labelledby="arch-title arch-desc">
              <title id="arch-title">Aegis Debug analysis pipeline</title>
              <desc id="arch-desc">Source file flows through PSI parse, early syntax/compile pass, deterministic static analyzers, optional AI augmentation, and ends at an issue list plus NeuroMap overlay.</desc>

              <defs>
                <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#f3ead3" />
                </marker>
                <marker id="arrow-gold" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#c9a96a" />
                </marker>
              </defs>

              <!-- nodes -->
              <g font-family="IBM Plex Sans, sans-serif" font-size="12" fill="#f3ead3">
                <rect x="200" y="10"  width="160" height="38" rx="4" fill="#1a2436" stroke="#243049"/>
                <text x="280" y="34"  text-anchor="middle">Source file</text>

                <rect x="200" y="72"  width="160" height="38" rx="4" fill="#1a2436" stroke="#243049"/>
                <text x="280" y="96"  text-anchor="middle">PSI parse</text>

                <rect x="20"  y="72"  width="160" height="38" rx="4" fill="#1a2436" stroke="#243049"/>
                <text x="100" y="96"  text-anchor="middle">Early pass (syntax / compile)</text>

                <rect x="200" y="142" width="160" height="44" rx="4" fill="#1a2436" stroke="#c9a96a" stroke-width="2"/>
                <text x="280" y="160" text-anchor="middle">Static analyzers</text>
                <text x="280" y="176" text-anchor="middle" font-size="10" fill="#c9a96a">deterministic · engine badge</text>

                <rect x="200" y="218" width="160" height="44" rx="4" fill="#1a2436" stroke="#243049" stroke-dasharray="4 3"/>
                <text x="280" y="236" text-anchor="middle" fill="#f3ead3cc">AI augmentation</text>
                <text x="280" y="252" text-anchor="middle" font-size="10" fill="#f3ead380">optional · local or cloud</text>

                <rect x="200" y="294" width="160" height="38" rx="4" fill="#1a2436" stroke="#243049"/>
                <text x="280" y="318" text-anchor="middle">Issue list + NeuroMap</text>
              </g>

              <!-- edges -->
              <g stroke="#f3ead3" stroke-width="1.2" fill="none">
                <line x1="280" y1="48" x2="280" y2="68" marker-end="url(#arrow)"/>
                <line x1="200" y1="91" x2="184" y2="91" marker-end="url(#arrow)"/>
              </g>
              <g stroke="#c9a96a" stroke-width="1.4" fill="none">
                <line x1="280" y1="110" x2="280" y2="138" marker-end="url(#arrow-gold)"/>
              </g>
              <g stroke="#f3ead3" stroke-width="1.2" fill="none" stroke-dasharray="4 3">
                <line x1="280" y1="186" x2="280" y2="214" marker-end="url(#arrow)"/>
              </g>
              <g stroke="#f3ead3" stroke-width="1.2" fill="none">
                <line x1="280" y1="262" x2="280" y2="290" marker-end="url(#arrow)"/>
              </g>
            </svg>
            <figcaption>Solid path: deterministic. Dashed path: optional AI augmentation.</figcaption>
          </figure>
        </div>
      </div>
```

- [ ] **Step 2: Append how-it-works CSS.**

Append to `site/styles.css`:

```css
/* 6. how it works ----------------------------------------------------- */
.how-it-works { background: var(--c-navy-deep); }
.how-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: clamp(24px, 4vw, 48px);
  align-items: center;
}
@media (max-width: 900px) {
  .how-grid { grid-template-columns: 1fr; }
}
.how-prose p {
  font-size: 15px; line-height: 1.65;
  color: var(--c-cream-soft);
  margin-bottom: 16px;
  max-width: 50ch;
}
.architecture { margin: 0; }
.architecture svg {
  width: 100%; height: auto;
  max-width: 560px;
  margin-inline: auto;
}
.architecture figcaption {
  margin-top: 12px;
  font-size: 12px; color: var(--c-cream-faint);
  text-align: center;
}
```

- [ ] **Step 3: Reload and verify.**

Refresh. Scroll to "How it works".

Expected:
- Two-column layout: prose left, SVG diagram right (stacks at < 900px)
- SVG renders crisp: five boxes connected by arrows, the "Static analyzers" box has a gold border and the arrow into it is gold; the "AI augmentation" box has a dashed border and a dashed arrow
- Caption reads "Solid path: deterministic. Dashed path: optional AI augmentation."

- [ ] **Step 4: Commit.**

```bash
git add site/index.html site/styles.css
git commit -m "feat(site): how-it-works section with inlined architecture SVG

Two-column section explaining the static-first + optional-AI pipeline.
Architecture diagram is inlined SVG so it picks up CSS-token colors.
Solid gold path marks the deterministic flow; dashed path marks the
optional AI augmentation. Section background is deeper navy to break
visual rhythm from features section above."
```

---

## Task 6: Case studies — tab switcher markup

This task lays down the markup for the tab UI and three (empty) panels. The JS to make tabs work lands in Task 7; the case-study content lands in Task 8.

**Files:**
- Modify: `site/index.html` (replace `<!-- Case-studies content added in Tasks 6-8 -->`)
- Modify: `site/styles.css` (append case-studies block)

- [ ] **Step 1: Replace the case-studies HTML.**

Find:

```html
    <section id="case-studies" class="case-studies" aria-labelledby="cs-heading">
      <!-- Case-studies content added in Tasks 6-8 -->
    </section>
```

Replace the comment with:

```html
      <div class="shell">
        <h2 id="cs-heading" class="section-title">Engineering case studies</h2>
        <p class="section-lead">Three different kinds of work from the V1 → V1.5 cycle: a migration, a debugging dig, and a deliberate refactor.</p>

        <div class="tabs-wrap">
          <div class="tabs" role="tablist" aria-label="Case studies">
            <button type="button" role="tab" class="tab" id="tab-k2"        aria-controls="panel-k2"        aria-selected="true"  tabindex="0">K2 migration</button>
            <button type="button" role="tab" class="tab" id="tab-hang"      aria-controls="panel-hang"      aria-selected="false" tabindex="-1">The hanging tests</button>
            <button type="button" role="tab" class="tab" id="tab-refactor"  aria-controls="panel-refactor"  aria-selected="false" tabindex="-1">Pre-V2 refactor</button>
          </div>

          <article role="tabpanel" id="panel-k2"        aria-labelledby="tab-k2"       class="tab-panel"            data-active>
            <p class="cs-placeholder">[ Case study 1 content lands in Task 8 ]</p>
          </article>
          <article role="tabpanel" id="panel-hang"      aria-labelledby="tab-hang"     class="tab-panel" hidden>
            <p class="cs-placeholder">[ Case study 2 content lands in Task 8 ]</p>
          </article>
          <article role="tabpanel" id="panel-refactor"  aria-labelledby="tab-refactor" class="tab-panel" hidden>
            <p class="cs-placeholder">[ Case study 3 content lands in Task 8 ]</p>
          </article>
        </div>
      </div>
```

- [ ] **Step 2: Append case-studies CSS.**

Append to `site/styles.css`:

```css
/* 7. case studies ----------------------------------------------------- */
.case-studies { padding-bottom: clamp(72px, 10vw, 120px); }
.tabs-wrap { margin-top: 32px; }
.tabs {
  display: flex; gap: 4px;
  border-bottom: 1px solid var(--c-border);
  margin-bottom: 28px;
  flex-wrap: wrap;
}
.tab {
  background: transparent;
  border: 0;
  color: var(--c-cream-soft);
  padding: 12px 16px;
  font-size: 13px; font-weight: 500;
  letter-spacing: 0.01em;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: color var(--t-fast), border-color var(--t-fast);
}
.tab:hover { color: var(--c-cream); }
.tab[aria-selected="true"] {
  color: var(--c-cream);
  border-bottom-color: var(--c-gold);
}
.tab:focus-visible {
  outline: 2px solid var(--c-gold);
  outline-offset: 2px;
  border-radius: 2px;
}

.tab-panel { display: none; }
.tab-panel[data-active] { display: block; }

.cs-placeholder {
  color: var(--c-cream-faint);
  font-family: var(--font-mono);
  font-size: 13px;
}

/* case-study content (used in Task 8) */
.cs-hook {
  font-style: italic;
  font-size: 16px; line-height: 1.55;
  color: var(--c-cream);
  max-width: 60ch;
  margin-bottom: 28px;
  padding-left: 16px;
  border-left: 2px solid var(--c-gold);
}
.cs-section { margin-bottom: 22px; max-width: 64ch; }
.cs-section h3 {
  font-size: 11px; letter-spacing: 0.14em; text-transform: uppercase;
  color: var(--c-gold); font-weight: 500;
  margin-bottom: 10px;
}
.cs-section p {
  font-size: 14.5px; line-height: 1.65;
  color: var(--c-cream-soft);
}
.cs-code {
  background: var(--c-panel);
  border: 1px solid var(--c-border);
  border-left: 2px solid var(--c-gold);
  border-radius: var(--radius);
  padding: 14px 16px;
  font-family: var(--font-mono);
  font-size: 12px; line-height: 1.7;
  overflow-x: auto;
  margin-top: 14px;
  color: #d6c89c;
  white-space: pre;
}
.cs-code .comment { color: var(--c-comment); }
.cs-code .kw      { color: #e0b677; }
.cs-code .str     { color: #a7c080; }
.cs-code .fn      { color: #e3c08a; }
.cs-code .type    { color: #b6c9e8; }
```

- [ ] **Step 3: Reload and verify.**

Refresh. Scroll to "Engineering case studies" (or click "Case studies" in nav).

Expected:
- Section title + lead
- Three tabs in a row with the first active (gold underline, brighter text)
- Below: a placeholder line "[ Case study 1 content lands in Task 8 ]"
- Clicking tabs **does nothing yet** — that's Task 7

- [ ] **Step 4: Commit.**

```bash
git add site/index.html site/styles.css
git commit -m "feat(site): case-studies tab markup with ARIA roles

Three tabs (K2 migration, hanging tests, pre-V2 refactor) + three
panels. First tab and panel active by default. ARIA roles (tablist /
tab / tabpanel) + aria-selected + tabindex set up for keyboard
navigation; JS lands in next commit. Panels are placeholders for now."
```

---

## Task 7: Case studies — tab switcher JS

**Files:**
- Modify: `site/main.js` (replace placeholder)

- [ ] **Step 1: Write the tab switcher.**

Replace `site/main.js` with:

```javascript
// site/main.js — landing page interactivity
// Three named init functions. No framework. No bundler. ES2020 syntax.

/* tab switcher -------------------------------------------------------- */
function initTabSwitcher() {
  const tablists = document.querySelectorAll('[role="tablist"]');
  tablists.forEach((tablist) => {
    const tabs = Array.from(tablist.querySelectorAll('[role="tab"]'));
    if (tabs.length === 0) return;

    const select = (tab) => {
      tabs.forEach((t) => {
        const isSelected = t === tab;
        t.setAttribute('aria-selected', String(isSelected));
        t.setAttribute('tabindex', isSelected ? '0' : '-1');
        const panel = document.getElementById(t.getAttribute('aria-controls'));
        if (!panel) return;
        if (isSelected) {
          panel.removeAttribute('hidden');
          panel.setAttribute('data-active', '');
        } else {
          panel.setAttribute('hidden', '');
          panel.removeAttribute('data-active');
        }
      });
    };

    tablist.addEventListener('click', (event) => {
      const tab = event.target.closest('[role="tab"]');
      if (!tab || !tabs.includes(tab)) return;
      select(tab);
      tab.focus();
    });

    tablist.addEventListener('keydown', (event) => {
      const currentIndex = tabs.findIndex((t) => t === document.activeElement);
      if (currentIndex === -1) return;
      let nextIndex = null;
      if (event.key === 'ArrowRight') nextIndex = (currentIndex + 1) % tabs.length;
      if (event.key === 'ArrowLeft')  nextIndex = (currentIndex - 1 + tabs.length) % tabs.length;
      if (event.key === 'Home')       nextIndex = 0;
      if (event.key === 'End')        nextIndex = tabs.length - 1;
      if (nextIndex === null) return;
      event.preventDefault();
      const next = tabs[nextIndex];
      select(next);
      next.focus();
    });
  });
}

document.addEventListener('DOMContentLoaded', () => {
  initTabSwitcher();
});
```

- [ ] **Step 2: Reload and verify mouse switching.**

Refresh. Scroll to case studies.

Expected:
- Clicking the second tab ("The hanging tests") hides the first panel and shows the second placeholder
- Clicking the third tab hides the second and shows the third
- The active tab always has the gold underline

- [ ] **Step 3: Verify keyboard navigation.**

Focus the active tab (Tab key from somewhere above) and press:
- `ArrowRight` → next tab activates and gets focus
- `ArrowLeft` → previous tab activates and gets focus
- `Home` → first tab activates
- `End` → last tab activates

Expected: All four keys work. Focus follows the active tab so a screen-reader user hears the new tab.

- [ ] **Step 4: Verify ARIA in DevTools.**

Open DevTools → Elements. Click a tab. Expected: `aria-selected="true"` on the clicked tab, `aria-selected="false"` on the others. The corresponding panel has no `hidden` attribute and has `data-active`; the other panels have `hidden`.

- [ ] **Step 5: Commit.**

```bash
git add site/main.js
git commit -m "feat(site): tab switcher with ARIA + keyboard nav

Vanilla JS, ~50 lines. Handles click and keyboard (Arrow/Home/End)
to switch between case studies. Properly toggles aria-selected,
tabindex, hidden, and data-active so screen readers and assistive
tech see the change. Wired up on DOMContentLoaded; no framework."
```

---

## Task 8: Case studies — content

This task replaces the three placeholders with full case-study content. Each panel follows the four-beat template from spec §7 (Hook · Investigation · Fix · Why it matters), in first-person prose.

**Files:**
- Modify: `site/index.html` (replace each panel's placeholder)

- [ ] **Step 1: Replace panel-k2 content.**

Find:

```html
          <article role="tabpanel" id="panel-k2"        aria-labelledby="tab-k2"       class="tab-panel"            data-active>
            <p class="cs-placeholder">[ Case study 1 content lands in Task 8 ]</p>
          </article>
```

Replace with:

```html
          <article role="tabpanel" id="panel-k2" aria-labelledby="tab-k2" class="tab-panel" data-active>
            <p class="cs-hook">
              Name-based matching was producing the wrong answers on smart-cast variables. The fix was to stop matching names and start asking the compiler.
            </p>

            <div class="cs-section">
              <h3>The investigation</h3>
              <p>V1.1.x's Kotlin null-safety analyzer used a regex-and-name-table approach: scan the file, build a table of which identifiers had been null-checked in scope, flag dereferences of identifiers not in the table. It produced false positives on every <code>if (x != null) { x.foo() }</code> idiom because the table never captured the narrowing — name-based matching can't see types.</p>
              <p>I considered the <code>BindingContext</code> API first; rejected because it's deprecated under K2. The Kotlin Analysis API's <code>analyze(ktFile) { … }</code> block exposes smart-cast and type-inference data directly, but its session-bound values throw <code>KaInvalidLifetimeOwnerAccessException</code> if they escape the block. Catching that consistently across analyzers needed a chokepoint.</p>
            </div>

            <div class="cs-section">
              <h3>The fix</h3>
              <p>One helper, used by every Kotlin analyzer. Wraps <code>analyze { }</code>, rethrows <code>ProcessCanceledException</code> immediately (so the IDE's cancel button works), and returns <code>null</code> on any other Analysis API failure:</p>
<pre class="cs-code"><span class="kw">internal fun</span> &lt;<span class="type">T</span>&gt; <span class="fn">withKtAnalysis</span>(
    file: <span class="type">KtFile</span>,
    block: <span class="type">KaSession</span>.(<span class="type">KtFile</span>) -&gt; <span class="type">T</span>
): <span class="type">T</span>? = <span class="kw">try</span> {
    <span class="fn">analyze</span>(file) { <span class="fn">block</span>(file) }
} <span class="kw">catch</span> (e: <span class="type">ProcessCanceledException</span>) { <span class="kw">throw</span> e }
  <span class="kw">catch</span> (_: <span class="type">KaAnalysisNonPublicApiException</span>) { <span class="kw">null</span> }
  <span class="kw">catch</span> (_: <span class="type">KaInvalidLifetimeOwnerAccessException</span>) { <span class="kw">null</span> }
  <span class="kw">catch</span> (_: <span class="type">Throwable</span>) { <span class="kw">null</span> }</pre>
            </div>

            <div class="cs-section">
              <h3>Why it matters</h3>
              <p>Funnelling every analyzer through one chokepoint means new Analysis API exceptions surface in one file. A reviewer who adds a Kotlin analyzer and forgets to call <code>withKtAnalysis</code> is immediately spottable in code review — every direct <code>analyze { }</code> call outside the helper is suspicious by definition. Documented in <code>CLAUDE.md</code> so future contributors inherit the convention without reading the V1.3 PR.</p>
            </div>
          </article>
```

- [ ] **Step 2: Replace panel-hang content.**

Find:

```html
          <article role="tabpanel" id="panel-hang"      aria-labelledby="tab-hang"     class="tab-panel" hidden>
            <p class="cs-placeholder">[ Case study 2 content lands in Task 8 ]</p>
          </article>
```

Replace with:

```html
          <article role="tabpanel" id="panel-hang" aria-labelledby="tab-hang" class="tab-panel" hidden>
            <p class="cs-hook">
              A transitive <code>kotlinx-coroutines-core</code> was shadowing IntelliJ's forked variant of the same jar. Five <code>BasePlatformTestCase</code> tests had been silently disabled for a release cycle.
            </p>

            <div class="cs-section">
              <h3>The investigation</h3>
              <p>Symptom: <code>IndexingTestUtil.waitUntilIndexesAreReady</code> polled forever. The test daemon never threw, never logged, never finished. My first hypothesis was test-framework wiring — I upgraded the IntelliJ Platform Gradle Plugin from 2.2.1 to 2.14.0 and re-ran. The hang persisted.</p>
              <p>The real root cause sat one layer deeper. Stock <code>kotlinx-coroutines-core:1.9.0</code> was being pulled transitively (including via <code>mockk</code>) and was winning classpath resolution over IntelliJ's forked <code>kotlinx-coroutines-core-jvm-*-intellij.jar</code>. The fork exposes platform-only methods like <code>runBlockingWithParallelismCompensation</code> that <code>UnindexedFilesScanner</code> invokes during test setup. The stock jar threw <code>NoSuchMethodError</code> inside the scanning coroutine, which the scanner caught and converted to "still indexing" — silent forever.</p>
            </div>

            <div class="cs-section">
              <h3>The fix</h3>
              <p>Demote <code>kotlinx-coroutines-core</code> to <code>compileOnly</code> and apply a configuration-level <code>exclude</code> on both runtime classpaths. Per-dependency excludes weren't enough — <code>mockk</code> re-introduces the jar transitively, so the exclude has to live at the configuration level:</p>
<pre class="cs-code"><span class="fn">configurations</span> {
    <span class="fn">all</span> {
        <span class="fn">exclude</span>(group = <span class="str">"org.jetbrains.kotlinx"</span>,
                module = <span class="str">"kotlinx-coroutines-core"</span>)
    }
}

<span class="fn">dependencies</span> {
    <span class="fn">compileOnly</span>(<span class="str">"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0"</span>)
    <span class="comment">// IntelliJ's forked jar wins at runtime now</span>
}</pre>
            </div>

            <div class="cs-section">
              <h3>Why it matters</h3>
              <p>Test-infrastructure failures that masquerade as "indexing slowness" can hide silent test-suite regressions across releases — in this case, five tests had been disabled since V1.1 without anyone noticing. The platform's forked-jar trick is invisible from outside JetBrains; finding the root cause required reading Gradle dependency-resolution output, the IntelliJ Community source, and the coroutine scanner's catch block.</p>
            </div>
          </article>
```

- [ ] **Step 3: Replace panel-refactor content.**

Find:

```html
          <article role="tabpanel" id="panel-refactor"  aria-labelledby="tab-refactor" class="tab-panel" hidden>
            <p class="cs-placeholder">[ Case study 3 content lands in Task 8 ]</p>
          </article>
```

Replace with:

```html
          <article role="tabpanel" id="panel-refactor" aria-labelledby="tab-refactor" class="tab-panel" hidden>
            <p class="cs-hook">
              Every V2 feature would land code inside or adjacent to <code>GhostDebuggerService</code>. Splitting now means V2 lands on a clean seam; splitting later means V3 inherits a worse problem.
            </p>

            <div class="cs-section">
              <h3>The investigation</h3>
              <p>By V1.4.1, <code>GhostDebuggerService</code> was 918 lines and held five distinct responsibilities: analysis orchestration, VFS-listener plumbing, debug-event subscription, UI-event routing, and report export. V2's roadmap adds five capabilities — test-runner cross-check, debug-session cross-check, problems-tool-window integration, IntentionAction quick-fixes, streaming AI — and every one of them would land in or adjacent to that class.</p>
              <p>Mapping each V2 feature to its eventual home revealed four natural seams: analysis state, UI events, VFS plumbing, debug events. The decision was whether to split before V2 (paying the refactor cost on a stable baseline) or after (paying it on top of new features). Splitting before means each V2 feature lands on a clean seam. Splitting after means V3 inherits a 1500-line god class.</p>
            </div>

            <div class="cs-section">
              <h3>The fix</h3>
              <p>Facade pattern. The class keeps its name and <code>getInstance(project)</code> entry point — six external callers depend on that signature — but the body becomes a thin delegator over four new project-scoped services. State stays on the facade; collaborators read it through the facade and write it through one internal mutator:</p>
<pre class="cs-code"><span class="comment">// One writer, many readers. Invariant documented in CLAUDE.md.</span>
<span class="kw">internal fun</span> <span class="fn">updateIssues</span>(issues: <span class="type">List</span>&lt;<span class="type">Issue</span>&gt;) {
    currentIssues = issues
    issuesByFile = issues.<span class="fn">groupBy</span> { it.file }
}

<span class="comment">// Collaborator side — reads through facade, never assigns directly.</span>
<span class="kw">class</span> <span class="type">AnalysisOrchestrator</span>(<span class="kw">private val</span> service: <span class="type">GhostDebuggerService</span>) {
    <span class="kw">fun</span> <span class="fn">currentIssueCount</span>(): <span class="type">Int</span> = service.currentIssues.size
}</pre>
            </div>

            <div class="cs-section">
              <h3>Why it matters</h3>
              <p>State ownership matters in a multi-collaborator system. If each collaborator owned its own copy of the issue list, the views drift and the user sees stale or contradictory findings across surfaces. One writer (the facade), many readers (collaborators) is the invariant that prevents that — and it's documented in <code>CLAUDE.md</code> precisely so V2's new collaborators inherit the rule instead of re-litigating it.</p>
            </div>
          </article>
```

- [ ] **Step 4: Reload and verify all three panels.**

Refresh. Click each tab:

- **Tab 1 (K2 migration):** Italic hook with gold left border, three sections (Investigation, Fix, Why it matters), a code panel showing the `withKtAnalysis` helper.
- **Tab 2 (Hanging tests):** Same structure, code panel shows the Gradle `configurations { all { exclude(...) } }` block.
- **Tab 3 (Pre-V2 refactor):** Same structure, code panel shows the `updateIssues` mutator and a collaborator reading through the facade.

In all three: inline `<code>` snippets in body prose render in mono font with subtle color tint (acceptable if they look like body text — they don't need a background panel, only `pre.cs-code` blocks do).

- [ ] **Step 5: Verify body-`<code>` styling.**

The case-study prose uses `<code>` tags inline (e.g. `<code>analyze(file) { ... }</code>`). Confirm in the browser these render in mono, not in serif/sans. If they're rendering in the default browser style, add to `styles.css` (in block 7, case-studies):

```css
.cs-section code {
  font-family: var(--font-mono);
  font-size: 0.92em;
  color: var(--c-cream);
  background: var(--c-gold-soft);
  padding: 1px 5px;
  border-radius: 3px;
}
```

Reload and re-verify.

- [ ] **Step 6: Commit.**

```bash
git add site/index.html site/styles.css
git commit -m "feat(site): three case studies with hand-coloured code excerpts

K2 / Analysis API migration (the withKtAnalysis chokepoint), the
classpath-collision debug (Gradle configuration-level exclude), and
the pre-V2 god-class split (facade + one-writer-many-readers).
First-person prose, four-beat template (hook / investigation / fix /
why it matters). Inline <code> styling tweaked to read as prose, not
as a block."
```

---

## Task 9: Stats + roadmap section

**Files:**
- Modify: `site/index.html` (replace `<!-- Stats content added in Task 9 -->`)
- Modify: `site/styles.css` (append stats block)

- [ ] **Step 1: Replace the stats HTML.**

Find:

```html
    <section id="stats" class="stats" aria-labelledby="stats-heading">
      <!-- Stats content added in Task 9 -->
    </section>
```

Replace the comment with:

```html
      <div class="shell">
        <h2 id="stats-heading" class="section-title">By the numbers</h2>

        <dl class="stat-grid">
          <div class="stat"><dt>Analyzers</dt><dd>11</dd></div>
          <div class="stat"><dt>Deterministic fixers</dt><dd>5</dd></div>
          <div class="stat"><dt>Languages</dt><dd>TS · JS · Kotlin · Java</dd></div>
          <div class="stat"><dt>Release cadence</dt><dd>V1.0 → V1.5 in under a month</dd></div>
          <div class="stat"><dt>Tests · verifier</dt><dd>~257 green · Compatible across 4 IDE versions</dd></div>
        </dl>

        <p class="roadmap">
          <strong>What's next.</strong> V2 is in design — language breadth (Python), IDE-native integration (problems tool window, <kbd>Alt</kbd>+<kbd>Enter</kbd>), runtime-confirmation via test and debug-session cross-check.
          <a href="https://github.com/javrodr19/debugger-project/blob/main/docs/aegis_debug_roadmap_v2_to_v5.md" rel="noopener" target="_blank">See the roadmap <span aria-hidden="true">→</span></a>
        </p>
      </div>
```

- [ ] **Step 2: Append stats CSS.**

Append to `site/styles.css`:

```css
/* 8. stats + roadmap -------------------------------------------------- */
.stats { background: var(--c-navy-deep); }
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  margin: 32px 0 36px;
}
.stat {
  background: var(--c-panel);
  border: 1px solid var(--c-border);
  border-radius: var(--radius);
  padding: 18px 18px 20px;
}
.stat dt {
  font-size: 11px; letter-spacing: 0.12em; text-transform: uppercase;
  color: var(--c-cream-faint);
  margin-bottom: 10px;
}
.stat dd {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 15px; line-height: 1.4;
  color: var(--c-gold);
}
.roadmap {
  font-size: 14px; line-height: 1.65;
  color: var(--c-cream-soft);
  max-width: 70ch;
}
.roadmap strong { color: var(--c-cream); font-weight: 600; }
.roadmap a { color: var(--c-gold); text-decoration: underline; text-underline-offset: 3px; }
.roadmap kbd {
  font-family: var(--font-mono);
  font-size: 11px;
  background: var(--c-panel);
  border: 1px solid var(--c-border);
  border-radius: 3px;
  padding: 1px 6px;
}
```

- [ ] **Step 3: Reload and verify.**

Refresh. Scroll past case studies.

Expected:
- "By the numbers" title
- Five stat cards in a responsive grid (auto-fit, min 180px)
- Each card: small uppercase label + mono gold value
- Roadmap paragraph below with "What's next" bolded, `<kbd>` keys styled like keys, and an underlined gold link to the roadmap

- [ ] **Step 4: Commit.**

```bash
git add site/index.html site/styles.css
git commit -m "feat(site): stats grid and roadmap teaser

Five stat cards (analyzers, fixers, languages, release cadence,
tests+verifier). Roadmap line points to the V2-V5 roadmap doc in the
repo. Section background is deeper navy to break visual rhythm from
case studies."
```

---

## Task 10: About + footer + inline SVG icons

**Files:**
- Modify: `site/index.html` (replace `<!-- About content added in Task 10 -->` and `<!-- Footer content added in Task 10 -->`)
- Modify: `site/styles.css` (append about + footer block)

- [ ] **Step 1: Replace the about section.**

Find:

```html
    <section id="about" class="about" aria-labelledby="about-heading">
      <!-- About content added in Task 10 -->
    </section>
```

Replace the comment with:

```html
      <div class="shell about-shell">
        <h2 id="about-heading" class="section-title">About the author</h2>

        <p class="bio">
          I'm <strong>Javier Rodríguez</strong>. I build developer tools — currently a JetBrains plugin for static analysis, the project you're looking at. I care about deterministic-first systems, deliberate API design, and writing code that reads well a year after I wrote it. I'm open to roles and collaborations on Aegis Debug or adjacent work in developer tooling and IDE/language ecosystems.
        </p>

        <div class="author-cta-row">
          <a class="cta cta-primary" href="https://github.com/javrodr19" rel="noopener" target="_blank">
            <svg class="icon" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
              <path fill="currentColor" d="M8 0C3.58 0 0 3.58 0 8a8 8 0 005.47 7.59c.4.07.55-.17.55-.38 0-.19-.01-.69-.01-1.36-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.42 7.42 0 014 0c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0016 8c0-4.42-3.58-8-8-8z"/>
            </svg>
            GitHub
          </a>
          <a class="cta cta-secondary" href="#" rel="noopener" target="_blank" data-linkedin>
            <svg class="icon" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
              <path fill="currentColor" d="M0 1.146C0 .513.526 0 1.175 0h13.65C15.474 0 16 .513 16 1.146v13.708c0 .633-.526 1.146-1.175 1.146H1.175C.526 16 0 15.487 0 14.854V1.146zm4.943 12.248V6.169H2.542v7.225h2.401zm-1.2-8.212c.837 0 1.358-.554 1.358-1.248-.015-.709-.52-1.248-1.342-1.248-.822 0-1.359.54-1.359 1.248 0 .694.521 1.248 1.327 1.248h.016zm4.908 8.212V9.359c0-.216.016-.432.08-.586.173-.431.568-.878 1.232-.878.869 0 1.216.662 1.216 1.634v3.865h2.401V9.25c0-2.22-1.184-3.252-2.764-3.252-1.274 0-1.845.7-2.165 1.193v.025h-.016l.016-.025V6.169h-2.4c.03.678 0 7.225 0 7.225h2.4z"/>
            </svg>
            LinkedIn
          </a>
        </div>
      </div>
```

- [ ] **Step 2: Replace the footer.**

Find:

```html
  <footer class="site-footer">
    <!-- Footer content added in Task 10 -->
  </footer>
```

Replace the comment with:

```html
    <div class="shell footer-grid">
      <div class="footer-row footer-install">
        <strong>Aegis Debug</strong> is on the <a href="https://plugins.jetbrains.com/" rel="noopener" target="_blank">JetBrains Marketplace <span aria-hidden="true">↗</span></a> — or <a href="https://github.com/javrodr19/debugger-project/releases" rel="noopener" target="_blank">download v1.5.0 from GitHub <span aria-hidden="true">↗</span></a>.
      </div>
      <div class="footer-row footer-links">
        <a href="https://github.com/javrodr19/debugger-project" rel="noopener" target="_blank">Repo</a>
        <span class="sep" aria-hidden="true">·</span>
        <a href="https://github.com/javrodr19/debugger-project/blob/main/CHANGELOG.md" rel="noopener" target="_blank">CHANGELOG</a>
        <span class="sep" aria-hidden="true">·</span>
        <a href="https://github.com/javrodr19/debugger-project/tree/main/docs/superpowers/specs" rel="noopener" target="_blank">Spec docs</a>
        <span class="sep" aria-hidden="true">·</span>
        <a href="https://github.com/javrodr19/debugger-project/blob/main/LICENSE" rel="noopener" target="_blank">License (MIT)</a>
      </div>
      <div class="footer-row footer-sign">
        Made by Javier Rodríguez <span class="sep" aria-hidden="true">·</span> 2026 <span class="sep" aria-hidden="true">·</span> This page has zero analytics.
      </div>
    </div>
```

- [ ] **Step 3: Append about + footer CSS.**

Append to `site/styles.css`:

```css
/* 9. about ------------------------------------------------------------ */
.about-shell { max-width: 760px; }
.bio {
  font-size: 16px; line-height: 1.7;
  color: var(--c-cream);
  margin: 24px 0 28px;
}
.bio strong { color: var(--c-cream); font-weight: 600; }
.author-cta-row { display: flex; gap: 10px; flex-wrap: wrap; }
.author-cta-row .cta {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 11px 18px;
}
.icon { display: inline-block; flex-shrink: 0; }

/* 10. footer ---------------------------------------------------------- */
.site-footer {
  padding: 36px var(--shell-pad-x) 48px;
  background: var(--c-navy-deep);
  border-top: 1px solid var(--c-border);
  font-size: 12.5px;
  color: var(--c-cream-faint);
}
.footer-grid { display: flex; flex-direction: column; gap: 10px; }
.footer-row { line-height: 1.6; }
.footer-row a { color: var(--c-cream-soft); transition: color var(--t-fast); }
.footer-row a:hover { color: var(--c-gold); }
.footer-row strong { color: var(--c-cream); font-weight: 600; }
.footer-row .sep { opacity: 0.45; margin: 0 6px; }
```

- [ ] **Step 4: Reload and verify.**

Refresh. Scroll to the bottom.

Expected:
- **About section:** Title + four-sentence bio (placeholder copy from spec §9 with the role-type sentence as a default — to be finalized before launch) + two pill CTAs (GitHub with cream background, LinkedIn outlined). Both CTAs have an inline SVG icon.
- **Footer:** Three rows. Install line, links row with separators, sign-off line. All small, low-opacity, gold-on-hover for links.

- [ ] **Step 5: Commit.**

```bash
git add site/index.html site/styles.css
git commit -m "feat(site): about section and footer with inline SVG icons

About: bio paragraph + GitHub/LinkedIn buttons with inlined SVG icons
(no icon library — three icons total don't justify the dependency).
Footer: install line repeats the marketplace CTA, links row, sign-off
('zero analytics'). LinkedIn URL flagged with data-linkedin for the
pre-launch copy pass."
```

---

## Task 11: Scroll reveals + smooth scroll

**Files:**
- Modify: `site/main.js` (append two init functions)
- Modify: `site/styles.css` (append reveal styles)
- Modify: `site/index.html` (add `.reveal` class to sections)

- [ ] **Step 1: Append the two init functions to main.js.**

In `site/main.js`, find:

```javascript
document.addEventListener('DOMContentLoaded', () => {
  initTabSwitcher();
});
```

Replace with:

```javascript
/* scroll-triggered reveals ------------------------------------------- */
function initScrollReveals() {
  const targets = document.querySelectorAll('.reveal');
  if (targets.length === 0 || !('IntersectionObserver' in window)) {
    targets.forEach((el) => el.classList.add('is-visible'));
    return;
  }
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -60px 0px' });
  targets.forEach((el) => observer.observe(el));
}

/* smooth-scroll on anchor click -------------------------------------- */
function initSmoothScroll() {
  document.addEventListener('click', (event) => {
    const link = event.target.closest('a[href^="#"]');
    if (!link) return;
    const href = link.getAttribute('href');
    if (href === '#' || href.length < 2) return;
    const target = document.querySelector(href);
    if (!target) return;
    event.preventDefault();
    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    if (history.pushState) history.pushState(null, '', href);
  });
}

document.addEventListener('DOMContentLoaded', () => {
  initTabSwitcher();
  initScrollReveals();
  initSmoothScroll();
});
```

- [ ] **Step 2: Append reveal styles.**

Append to `site/styles.css`:

```css
/* 11. reveals --------------------------------------------------------- */
.reveal {
  opacity: 0;
  transform: translateY(12px);
  transition: opacity 480ms ease-out, transform 480ms ease-out;
  will-change: opacity, transform;
}
.reveal.is-visible {
  opacity: 1;
  transform: translateY(0);
}
@media (prefers-reduced-motion: reduce) {
  .reveal { opacity: 1; transform: none; transition: none; }
}
```

- [ ] **Step 3: Add `.reveal` class to below-the-fold sections only.**

The hero is intentionally excluded — it's above the fold on first paint. If it had `.reveal`, it would render at `opacity: 0` for ~100ms before the IntersectionObserver fired (the script has `defer`), causing a flash of invisible content.

In `site/index.html`, modify each below-the-fold section's opening tag. Find and replace each:

```html
    <section id="features" class="features" aria-labelledby="features-heading">
```
→
```html
    <section id="features" class="features reveal" aria-labelledby="features-heading">
```

```html
    <section id="how-it-works" class="how-it-works" aria-labelledby="how-heading">
```
→
```html
    <section id="how-it-works" class="how-it-works reveal" aria-labelledby="how-heading">
```

```html
    <section id="case-studies" class="case-studies" aria-labelledby="cs-heading">
```
→
```html
    <section id="case-studies" class="case-studies reveal" aria-labelledby="cs-heading">
```

```html
    <section id="stats" class="stats" aria-labelledby="stats-heading">
```
→
```html
    <section id="stats" class="stats reveal" aria-labelledby="stats-heading">
```

```html
    <section id="about" class="about" aria-labelledby="about-heading">
```
→
```html
    <section id="about" class="about reveal" aria-labelledby="about-heading">
```

The `<section class="hero">` tag is left unchanged.

- [ ] **Step 4: Reload and verify reveals.**

Refresh and scroll slowly.

Expected: Each below-the-fold section fades in + slides up slightly when it enters the viewport (threshold 12% visible). Once revealed it stays visible. The hero is excluded from the effect — it renders at full opacity on first paint, no flash.

If you set "Reduce motion" in your OS / browser, the reveals disable cleanly (everything shows up at full opacity, no transitions).

- [ ] **Step 5: Verify smooth scroll.**

Click each nav link: Features, Case studies, About, GitHub. The first three should smoothly scroll to the right section. The GitHub link is external and should open in a new tab.

Also click the hero's "Read the case studies →" — should smooth-scroll to `#case-studies`.

After clicking, check the URL bar: the hash should update (e.g. `#case-studies`).

- [ ] **Step 6: Commit.**

```bash
git add site/main.js site/styles.css site/index.html
git commit -m "feat(site): scroll-triggered reveals and smooth-scroll

IntersectionObserver fades sections in when they enter the viewport
(12% threshold). Smooth-scroll on any in-page anchor click, with
history.pushState to update the URL hash. Both features respect
prefers-reduced-motion (reveal becomes no-op; smooth-scroll falls back
to CSS scroll-behavior which is auto under reduced motion)."
```

---

## Task 12: Copy plugin icon assets + OG image placeholder

**Files:**
- Create: `site/assets/plugin-icon.svg`
- Create: `site/assets/plugin-icon-dark.svg`
- Create: `site/assets/og-image.png` (placeholder)

- [ ] **Step 1: Copy the plugin icons.**

```bash
cp src/main/resources/META-INF/pluginIcon.svg      site/assets/plugin-icon.svg
cp src/main/resources/META-INF/pluginIcon_dark.svg site/assets/plugin-icon-dark.svg
```

Verify: `ls -la site/assets/plugin-icon*.svg` shows two files.

- [ ] **Step 2: Generate an OG image placeholder.**

A 1200×630 social card. Use one of the two commands (whichever toolchain is available):

```bash
convert -size 1200x630 xc:'#0e1726' \
  -gravity center -fill '#f3ead3' -pointsize 64 \
  -annotate +0-40 'Aegis Debug' \
  -fill '#c9a96a' -pointsize 28 \
  -annotate +0+40 'Static-first debugging for JetBrains IDEs' \
  site/assets/og-image.png
```

Or:

```bash
python3 -c "
from PIL import Image, ImageDraw
img = Image.new('RGB', (1200, 630), '#0e1726')
d = ImageDraw.Draw(img)
d.text((600, 275), 'Aegis Debug', fill='#f3ead3', anchor='mm')
d.text((600, 355), 'Static-first debugging for JetBrains IDEs', fill='#c9a96a', anchor='mm')
img.save('site/assets/og-image.png')
"
```

Verify: `ls -la site/assets/og-image.png` shows a file > 5 KB.

- [ ] **Step 3: Verify favicon loads.**

Refresh <http://localhost:8000>. Look at the browser tab — it should show the plugin icon as the favicon. (May require hard refresh / Ctrl+Shift+R because of favicon caching.)

- [ ] **Step 4: Verify OG meta tag references resolve.**

Run: `curl -sI http://localhost:8000/assets/og-image.png`
Expected: HTTP 200 status.

Run: `grep -E '(og:image|twitter:image)' site/index.html`
Expected: Two lines referencing `assets/og-image.png`.

- [ ] **Step 5: Commit.**

```bash
git add site/assets/
git commit -m "feat(site): plugin icon assets + OG image placeholder

Favicon points at the existing pluginIcon.svg; dark variant copied
alongside for future use. OG image is a placeholder until a final
1200×630 social card is captured (spec §14). All other meta-tag URLs
already point at this file."
```

---

## Task 13: Mobile responsive pass

This task is a single sweep through the page at three viewport widths to catch and fix any layout breakage. The CSS written so far should already handle most cases, but this is the formal check.

**Files:**
- Modify: `site/styles.css` if any breakpoint adjustments needed

- [ ] **Step 1: Open Chrome DevTools responsive mode.**

Open <http://localhost:8000>. F12 → click the device toolbar icon (or Ctrl+Shift+M). Set viewport to **375 × 667** (iPhone SE).

- [ ] **Step 2: Scroll the entire page at 375px and check each section.**

Verify:
- **Nav:** brand mark + 4 links fit without overlap. Links may be smaller (12px); should not wrap to a second row at 375px. If they do, fine — just verify they don't break out of the nav.
- **Hero:** Eyebrow, headline, sub, CTAs, person-row all stack vertically. The code panel sits below the CTAs. Code panel scrolls horizontally if its lines are too wide.
- **Features:** Three cards stack vertically (one column). Cards remain readable.
- **How it works:** Prose and SVG stack vertically. SVG is centered and < 100% width.
- **Case studies:** Tabs may wrap to two rows (acceptable). Active tab still has gold underline. Panel content remains readable; `cs-code` blocks scroll horizontally if needed.
- **Stats:** Grid collapses to single column (auto-fit + minmax handles this).
- **About:** Bio reads cleanly; CTAs may wrap to two rows (acceptable).
- **Footer:** Three rows stack; separators visible.

- [ ] **Step 3: Test at 768px (tablet).**

Set viewport to **768 × 1024**. Verify:
- Hero stacks (under 720px breakpoint, so 768px should be side-by-side — but check what feels right; side-by-side is fine at 768)
- Features cards may be 1 or 3 columns depending on width (900px breakpoint); at 768 they're 1 column. Acceptable.
- All other sections render without overlap.

- [ ] **Step 4: Test at 1280px (laptop).**

Set viewport to **1280 × 800**. Verify everything looks like the desktop design from earlier tasks. No regressions.

- [ ] **Step 5: Fix anything broken.**

If a specific section breaks at one of the three widths, locate the offending CSS rule and add a media query. Example for a hypothetical wrapping nav issue at 380px:

```css
@media (max-width: 380px) {
  .site-nav .nav-links { gap: 10px; font-size: 11px; }
}
```

If nothing broke, no edit needed — move to step 6.

- [ ] **Step 6: Commit (only if changes made; otherwise skip).**

```bash
git add site/styles.css
git commit -m "fix(site): mobile responsive adjustments

Sweep through 375/768/1280 viewports. [Describe specific tweaks in body
if any were needed; if no tweaks, this commit can be omitted entirely.]"
```

If no fixes were needed, just leave a note in the task tracking: "Task 13 verified clean at 375/768/1280, no commit."

---

## Task 14: Accessibility pass

**Files:**
- Modify: `site/index.html` if any a11y fix needed
- Modify: `site/styles.css` if any contrast fix needed

- [ ] **Step 1: Verify semantic HTML.**

Run: `grep -E '^\s*<(header|main|section|article|aside|footer|nav|h[1-3])' site/index.html | head -30`

Expected: A list showing `<header>`, `<nav>`, `<main>`, six `<section>`s, three `<article>`s (case-study panels) + three `<article>`s (feature cards), one `<aside>` (hero code panel), one `<footer>`. One `<h1>` total (in hero). All other section titles are `<h2>` or `<h3>`.

If the heading hierarchy is wrong (multiple `<h1>`, skipping levels), fix.

- [ ] **Step 2: Verify alt text.**

Run: `grep -E '<img' site/index.html`
Expected: One image (the NeuroMap screenshot). It must have a meaningful `alt` attribute — verify it reads something like "NeuroMap view of an Aegis Debug analysis…", not "" or "image" or "screenshot".

- [ ] **Step 3: Verify ARIA on tabs.**

Run: `grep -E '(role="tab|aria-selected|aria-controls|aria-labelledby)' site/index.html | wc -l`
Expected: At least 12 matches (3 tabs × 3 attrs each + 3 panels × 1 attr = 12).

- [ ] **Step 4: Add a skip-link.**

Add as the very first child of `<body>` in `site/index.html`:

```html
  <a class="skip-link" href="#top">Skip to content</a>
```

Append to `site/styles.css` (in the reset block, after the `@media (prefers-reduced-motion)` rule):

```css
.skip-link {
  position: absolute;
  top: 0; left: 0;
  background: var(--c-cream);
  color: var(--c-navy);
  padding: 8px 14px;
  font-size: 13px; font-weight: 500;
  transform: translateY(-100%);
  transition: transform var(--t-fast);
  z-index: 100;
  border-radius: 0 0 var(--radius) 0;
}
.skip-link:focus { transform: translateY(0); }
```

- [ ] **Step 5: Verify keyboard navigation end-to-end.**

Refresh. Press Tab from the URL bar repeatedly. Verify:
- First Tab: skip-link becomes visible
- Subsequent Tabs: brand link → nav links → hero CTAs → person-row links → case-study tabs (arrow keys cycle within) → stats roadmap link → about CTAs → footer links
- Each focused element has a visible focus ring

If any element is reachable but invisible-on-focus, that element is missing a `:focus-visible` style. Add a fallback to the global reset block:

```css
:focus-visible {
  outline: 2px solid var(--c-gold);
  outline-offset: 2px;
}
```

- [ ] **Step 6: Verify contrast.**

The two foreground colors on navy background:
- `--c-cream` (#f3ead3) on `--c-navy` (#0e1726) — should be ~14.5:1, well above WCAG AA 4.5:1
- `--c-cream-faint` (#f3ead380 = cream at 50% alpha) — verify in DevTools that effective contrast is still ≥ 4.5:1 for footer/caption text

Use DevTools' built-in contrast checker (open the element panel for any text element, click the color swatch in Styles, the contrast ratio is shown).

If any text is below 4.5:1, raise its alpha. Likely candidates: `--c-cream-faint` for body text (currently used for sub-section captions). If insufficient, change to `cream-soft` (`f3ead3cc`, ~80% alpha) for body text and keep `cream-faint` for purely decorative captions.

- [ ] **Step 7: Run an axe DevTools scan (manual).**

If axe DevTools is installed in the browser, run a scan. Expected: 0 critical or serious violations. Note any warnings for follow-up but don't necessarily fix in this pass.

- [ ] **Step 8: Commit.**

```bash
git add site/index.html site/styles.css
git commit -m "feat(site): accessibility pass — skip-link, focus styles, contrast

Skip-link as first body child, hidden until focused. Global focus-visible
ring (2px gold). Verified heading hierarchy (one h1, h2/h3 cascade),
alt text on the NeuroMap screenshot, ARIA on tabs (role/aria-selected/
aria-controls/aria-labelledby). Contrast verified ≥ 4.5:1 for body text;
cream-faint reserved for purely decorative captions."
```

---

## Task 15: GitHub Actions workflow

**Files:**
- Create: `.github/workflows/site.yml`

- [ ] **Step 1: Create the workflow.**

Create `.github/workflows/site.yml`:

```yaml
name: Deploy site

on:
  push:
    branches: [main]
    paths:
      - 'site/**'
      - '.github/workflows/site.yml'
  workflow_dispatch: {}

permissions:
  contents: write

concurrency:
  group: pages
  cancel-in-progress: false

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Publish to gh-pages
        uses: peaceiris/actions-gh-pages@v3
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: ./site
          publish_branch: gh-pages
          force_orphan: true
          commit_message: 'deploy: site from ${{ github.sha }}'
```

- [ ] **Step 2: Validate the YAML.**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/site.yml'))"`
Expected: No output (silent success). If a parse error prints, fix it.

- [ ] **Step 3: Commit.**

```bash
git add .github/workflows/site.yml
git commit -m "ci(site): GitHub Actions workflow to publish site/ to gh-pages

Triggers on pushes to main that touch site/** or the workflow file
itself. Uses peaceiris/actions-gh-pages@v3 to publish site/ to a
gh-pages branch with force_orphan (clean branch each deploy). Manual
trigger via workflow_dispatch also available. After first run, enable
Pages in GitHub repo settings: Settings → Pages → Source: gh-pages
branch, folder: /."
```

---

## Task 16: Local Lighthouse + final acceptance check

**Files:** None (verification only).

- [ ] **Step 1: Run a production-mode local preview.**

In one terminal: `python3 -m http.server 8000 --directory site`

- [ ] **Step 2: Measure payload size.**

Run, against the running server:

```bash
total=0
for url in / styles.css main.js assets/neuromap-hero.png assets/og-image.png assets/plugin-icon.svg; do
  size=$(curl -sI "http://localhost:8000/$url" | awk '/[Cc]ontent-[Ll]ength:/ {print $2}' | tr -d '\r')
  echo "$size  $url"
  total=$((total + size))
done
echo "TOTAL: $total bytes"
```

Expected: TOTAL minus `neuromap-hero.png` and `og-image.png` should be under 200 000 bytes (spec §17 criterion 2).

Record the result for the commit message.

- [ ] **Step 3: Count HTTP requests on first load.**

Open <http://localhost:8000> in Chrome with DevTools open → Network tab → hard refresh (Ctrl+Shift+R) → check request count.

Expected: ≤ 5 main-frame requests on the document load:
1. `/` (HTML)
2. `styles.css`
3. Google Fonts CSS (one request)
4. `main.js`
5. `neuromap-hero.png`

Plus possibly favicon and font woff2 files (count those separately — they don't count against the 5-request criterion which is "HTML, CSS, JS, fonts CSS, one screenshot").

- [ ] **Step 4: Run Lighthouse on desktop.**

In Chrome DevTools → Lighthouse panel → Mode: Navigation, Device: Desktop, Categories: Performance + Accessibility + Best Practices + SEO → Analyze page load.

Expected:
- Performance ≥ 95
- Accessibility ≥ 95
- Best Practices ≥ 90
- SEO ≥ 95

Record the scores.

- [ ] **Step 5: Run Lighthouse on mobile.**

Same panel, switch Device: Mobile → Re-run.

Expected:
- Performance ≥ 85 (the screenshot makes this the loose criterion)
- Accessibility ≥ 95
- Best Practices ≥ 90
- SEO ≥ 95

- [ ] **Step 6: Manual reviewer-experience check.**

Pretend you arrived from a CV link. Time yourself:

1. From cold load, can you tell what the project is in 4 seconds? (Expected: yes — hero copy answers it.)
2. From cold load, can you finish reading the K2 case study in 90 seconds? (Expected: yes — ~200 words.)
3. From the about section, how many clicks to reach the GitHub repo? (Expected: 1.)
4. From the hero, how many scrolls to reach install instructions? (Expected: 0 — Marketplace CTA is in the hero. Plus the footer install line.)

- [ ] **Step 7: Document any remaining issues.**

If any acceptance criterion isn't met, add a note to the spec's "Open questions" section (or open a follow-up issue) and decide whether to address before or after launch.

- [ ] **Step 8: Final commit.**

```bash
git add -A
git commit --allow-empty -m "chore(site): final acceptance check pre-launch

Acceptance results (from spec §17):
- Payload (HTML + CSS + JS + fonts CSS): <RECORD ACTUAL bytes> / 200000 budget
- HTTP requests on first load: <RECORD ACTUAL> / 5 budget
- Lighthouse desktop: P=<n> A=<n> BP=<n> SEO=<n>
- Lighthouse mobile:  P=<n> A=<n> BP=<n> SEO=<n>
- Manual reviewer-experience: pass

Open questions still pending pre-launch (spec §16):
1. LinkedIn URL (replace data-linkedin in hero + about)
2. Bio fine-tuning (prior work, role-type preferences)
3. JetBrains Marketplace URL once plugin is published
4. License confirmation (assumed MIT from LICENSE file)"
```

After this commit, push to `main`. The GitHub Actions workflow takes over: builds and publishes to `gh-pages`. After the first successful run, enable Pages in repo settings (Settings → Pages → Source: gh-pages branch, folder: /) and the site goes live at <https://javrodr19.github.io/debugger-project/>.

---

## Pre-launch checklist (not part of plan execution — final copy pass)

These four items are flagged in the spec (§16) and require user input before the page is launch-ready:

1. **LinkedIn URL** — replace `data-linkedin` attributes in `site/index.html` (hero section + about section, 2 occurrences) with the real URL.
2. **Bio** — refine the four-sentence bio in `site/index.html` § About (currently uses defaults from spec §9).
3. **JetBrains Marketplace URL** — replace `https://plugins.jetbrains.com/` placeholders in hero CTA + footer install line (2 occurrences) once the plugin is published. If not published at launch, the GitHub releases URL is a fine fallback.
4. **License confirmation** — verify `LICENSE` file is MIT; footer line in `site/index.html` already says MIT.

A 5-minute final-edit pass before launch handles all four.
