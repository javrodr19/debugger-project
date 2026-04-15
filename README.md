# Aegis Debug — privacy-first debugging for IntelliJ

**Static-first analysis. Deterministic fixes. Optional local or cloud AI.**

Aegis Debug is a high-performance IntelliJ IDEA plugin designed to find real bugs in your code without compromising your privacy. By default, it operates entirely locally, using five deterministic analyzers to identify issues in TypeScript, JavaScript, Kotlin, and Java.

## Key Features

- **🧠 NeuroMap** — A visual project graph that highlights hotspots, circular dependencies, and complex architecture.
- **🛡️ Static-First Analysis** — Five core analyzers for **Null Safety**, **State Initialization**, **Async Flow**, **Circular Dependencies**, and **Complexity**.
- **⚡ Deterministic Fixers** — One-click, PSI-validated fixes with diff previews and native IDE undo support.
- **🤖 Optional AI Augmentation** — Seamlessly connect to **Ollama** for local, privacy-first reasoning or **OpenAI** for cloud-powered deep analysis.
- **📉 Provenance Tracking** — Clear visual badges distinguish between engine-verified and AI-suggested results, so you always know what you are trusting.
- **🔒 Privacy by Default** — No telemetry. No cloud uploads without explicit configuration. API keys stored securely in IntelliJ PasswordSafe.

## Supported Languages

- **TypeScript and JavaScript** — Full static analysis (5 analyzers) and deterministic fixers.
- **Kotlin and Java** — Project graph, complexity analysis, and circular dependency detection.

## Getting Started

1. **Install** — Search for "Aegis Debug" in the JetBrains Marketplace or download the latest release ZIP.
2. **Open** — Activate the **Aegis Debug** tool window (located on the right gutter by default).
3. **Analyze** — Click **Analyze Project** to build your NeuroMap and identify code issues.
4. **Fix** — Click any node in the NeuroMap to view details and apply suggested fixes.
5. **Configure** — Set up an AI provider in **Settings → Tools → Aegis Debug** for enhanced reasoning.

## Privacy & Security

Your code is your business. Aegis Debug is designed to work entirely locally. If you choose to enable cloud AI, only the specific code snippets required for analysis are transmitted, and only when you explicitly opt-in via the `Allow Cloud Upload` setting.

---
© 2026 Aegis Debug Team. All rights reserved.
