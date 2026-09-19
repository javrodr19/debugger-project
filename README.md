# Aegis Debug — privacy-first debugging for IntelliJ

**Static-first analysis. Deterministic fix engine (gated). Optional local or cloud AI (gated).**

Aegis Debug is an IntelliJ IDEA plugin that finds real bugs in your code without compromising your
privacy. It operates entirely locally: analysis runs through 12 analyzers (11 built-in rules plus
a custom-rule engine for user-authored YAML rules) to identify issues in TypeScript, JavaScript,
Kotlin, and Java.

**3.0.0 ships as a read-only analysis and visualization tool.** Several implemented, tested
capabilities — fix application, AI augmentation, the JVM dependency graph's impact analysis, rule
packs, the external analyzer SDK, native Problems-view integration, and the live-debugger
cross-check — are gated off in this release rather than shipped half-finished. Every gate, and the
one-sentence reason behind it, is recorded in **[docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md)**
— read that document for what actually works today, not just this README.

## Key Features

- **🧠 NeuroMap** — A visual project graph that highlights hotspots, circular dependencies, and complex architecture. Dependency edges resolve for relative imports only (TypeScript/JavaScript); Kotlin- and Java-only projects render nodes without edges, and the graph now says so instead of looking broken.
- **🛡️ Static-First Analysis** — 12 analyzers (11 built-in rules + a custom-rule engine) covering **Null Safety**, **State Initialization**, **Async Flow**, **Circular Dependencies**, **Complexity**, syntax/compile-error detection, and Kotlin-specific checks (unsafe cast, type mismatch, redundant `let`). Runs unconditionally — this is the part of the plugin that is fully live.
- **⚡ 8 deterministic fixers** — implemented, tested, and PSI-validity-gated (`fix/FixerRegistry.kt`), but **fix application is gated in 3.0.0**; nothing is applied automatically or via Alt+Enter this release.
- **🤖 AI Augmentation (implemented, gated)** — Ollama (local) and OpenAI (cloud) backends exist and are tested in isolation, but the AI analysis pass and AI explanations are both gated off in 3.0.0 — no AI provider is reachable from a running instance of this release.
- **📉 Provenance Tracking** — Visual badges distinguish engine-verified findings from ones your test suite confirms at runtime (test-suite cross-check is active). AI-sourced badges exist in the code but are inactive while AI is gated.
- **🔒 Privacy by default** — No telemetry, ever. No AI provider is reachable in this release, which is the strongest form of the privacy claim: there is nothing to configure that would send code anywhere. The cloud-upload consent check (`allowCloudUpload`) is implemented and enforced at a single chokepoint for when AI augmentation is re-enabled in a future release.

## Supported Languages

Static analysis is not the same depth for every language — see
**[docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md#language-coverage)** for the full
breakdown. In short:

- **TypeScript and JavaScript** — Full line-oriented lexical analysis (regex-based, with string- and comment-masking; IntelliJ Community bundles no JS/TS PSI) covering null safety, state-before-init, async flow, complexity, and circular dependencies. Internal dependency edges — and therefore cycle detection and impact analysis — resolve here because imports are relative.
- **Kotlin** — Type-aware Analysis API analyzers: null safety, unsafe cast, type mismatch, redundant `let`. Two of these four rules are shadowed by the compile-error gate (they don't fire on a file that already has a compile error).
- **Java** — PSI-backed symbol extraction and circular dependency analysis.

## Getting Started

1. **Install** — Download the latest release ZIP from [GitHub Releases](https://github.com/javrodr19/debugger-project/releases) and install it via Settings → Plugins → ⚙ → Install Plugin from Disk.
2. **Open** — Activate the **Aegis Debug** tool window (located on the right gutter by default).
3. **Analyze** — Click **Analyze Project** to build your NeuroMap and identify code issues.
4. **Review** — Click any node in the NeuroMap to see its findings in the detail panel.
5. **Read the gaps** — See [Implementation Status](docs/IMPLEMENTATION_STATUS.md) for what's gated in this release and why, before assuming a feature you read about elsewhere is live.

## Privacy & Security

Your code is your business. Aegis Debug 3.0.0 ships fully local and read-only: no AI provider is
reachable in this release, so there is no configuration that results in a network request. The
cloud-upload consent mechanism is implemented and enforced at a single chokepoint
(`AIServiceFactory.create`) for when AI augmentation is re-enabled — only specific code snippets
would ever be transmitted, and only after explicit opt-in via the `allowCloudUpload` setting
("Allow cloud upload (OpenAI)" in Settings → Tools → Aegis Debug). See
[`DATA_HANDLING.md`](DATA_HANDLING.md) for the full data-flow table.

---
© 2026 Aegis Debug Team. All rights reserved.
