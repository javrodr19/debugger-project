# Aegis Debug

> **Temporary README** — the project is currently under active **V1 development**.

**Aegis Debug** is a privacy-first IntelliJ plugin for visual code debugging and issue remediation. The product is being built around a simple trust model: **static analysis first**, **deterministic PSI-based fixes where possible**, and **optional local/cloud AI** as a secondary layer for contextual reasoning and explanation. The current product direction prioritizes trust, clarity, and explicit provenance over flashy automation. 

## Current Status

Aegis Debug is **not production-ready yet**. This repository is currently being used to build the first real V1 of the product.

At the moment, the focus is on shipping a reliable foundation with:
- a **static-first analysis pipeline**
- a **limited set of deterministic fixes**
- **optional Ollama / OpenAI support**
- **engine status + graceful fallback UX**
- **clear trust badges** that distinguish deterministic engine actions from AI suggestions

## V1 Focus

The V1 is being developed around the following core principles:

- **Static analysis is the foundation**
- **AI is the amplifier**
- **Trust is the product**

This means V1 is intentionally focused on:
- deterministic issue detection first
- safe PSI-based fixes for a limited set of issue classes
- optional AI-assisted explanations and missed-issue discovery
- privacy-first behavior with explicit provider control
- visible provenance for findings and fixes

## Planned V1 Capabilities

The current V1 target includes:

- **Static-first analysis** with explicit provider modes:
  - `NONE`
  - `OLLAMA`
  - `OPENAI`
- **Deterministic fix workflow** with diff preview and undo-safe application
- **Issue provenance tracking** (`source`, `provider`, optional `confidence`)
- **Engine status communication** in the UI
- **Enterprise-grade UI refresh** with a Dark Navy + Cream design system
- **Aegis Debug branding rollout** replacing the previous GhostDebugger identity

## Not in Scope for V1

The following ideas are intentionally deferred until after V1:

- freeform in-plugin chat
- autonomous large-scale code rewrites
- multi-step agentic refactoring workflows
- broad language/framework coverage without validated analyzer + fixer support

## Supported Direction for V1

The current recommended V1 scope is:
- **Primary language:** Kotlin
- **Secondary language:** Java (only where explicitly validated)
- **Platform:** IntelliJ-based IDEs

## Product Philosophy

Aegis Debug is being designed to help developers understand and fix issues with a workflow that is:
- fast
- privacy-conscious
- visually understandable
- explicit about confidence and provenance

The long-term vision is to create a debugging experience where deterministic analysis handles the baseline, and AI adds value only where it improves context rather than replacing trust.

## Branding Note

This project was previously developed under the working name **GhostDebugger**. As part of the V1 direction, the product is being renamed to **Aegis Debug** to better reflect its enterprise-grade, privacy-first positioning.

## Repository Note

Because the product is still in active V1 development, this README is intentionally temporary. It will be replaced with a fuller public README once:
- the initial analyzer set is stable
- deterministic fix flows are validated
- provider fallback behavior is hardened
- the V1 release surface is finalized

## Internal Docs

The canonical internal build direction currently lives in the V1 product specification document.

## License / Contribution

License, contribution guidelines, setup instructions, and public installation steps will be documented as the V1 foundation stabilizes.

---

**Working tagline:** *Enterprise-grade debugging. Zero privacy compromises.*
