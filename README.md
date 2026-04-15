# Aegis Debug — privacy-first debugging for IntelliJ

**Static-first analysis. Deterministic fixes. Optional local or cloud AI.**

Aegis Debug is an IntelliJ IDEA plugin that finds real bugs in your TypeScript and JavaScript code without sending anything to the cloud by default. Every finding is labeled with its source — engine-verified, local AI, or cloud AI — so you always know what you are trusting.

## Key Features

- **🧠 NeuroMap** — A visual project graph that highlights hotspots and dependency cycles.
- **🛡️ Static-First Analysis** — Five deterministic analyzers for null safety, state initialization, async flow, circular dependencies, and complexity.
- **⚡ Deterministic Fixers** — One-click fixes for common issues with diff preview and native IDE undo.
- **🤖 Optional AI Augmentation** — Connect to **Ollama** for local, privacy-first reasoning or **OpenAI** for cloud-powered deep analysis.
- **📉 Provenance Tracking** — Clear visual badges distinguish between engine-verified and AI-suggested results.
- **🔒 Privacy by Default** — No telemetry. No cloud uploads without explicit configuration. API keys stored securely in IntelliJ PasswordSafe.

## Supported Languages

- **TypeScript and JavaScript** — Full static analysis and deterministic fixers.
- **Kotlin and Java** — Project graph, complexity analysis, and circular dependency detection.

## Getting Started

1. Install "Aegis Debug" from the JetBrains Marketplace.
2. Open the **Aegis Debug** tool window on the right side of your IDE.
3. Click **Analyze Project** to build your NeuroMap.
4. (Optional) Configure an AI provider in **Settings → Tools → Aegis Debug**.

## Privacy & Security

We believe your code is your business. Aegis Debug is designed to work entirely locally. If you choose to enable cloud AI, only the specific code snippets required for analysis are transmitted, and only when you explicitly opt-in.

---
© 2026 Aegis Debug Team. All rights reserved.
