---
title: "Roadmap V2 to V5"
type: "meta"
status: "active"
related_components: []
aliases:
  - "Roadmap"
tags:
  - aegis-debug
---

# Roadmap: V2 to V5

This is the high-level north star for Aegis Debug.

## V2: Dynamic validation + language breadth + IDE-native integration
Theme: Prove that the static findings are real.
- **Dynamic validation pass**: Correlate static findings with runtime paths via test runners and debug sessions. Add `RUNTIME_CONFIRMED` provenance tier.
- **IntelliJ Problems tool window** integration.
- **Quick-fix intention actions** (`Alt+Enter`).
- **Streaming AI responses**.
- *(Explicit non-goals: team sync, cross-repo validation).*

## V3: Fixer breadth + custom rule authoring
Theme: Extend the fix catalog and let power users define rules.
- **Six more deterministic fixers**.
- **Custom rule authoring**: YAML/Kotlin-DSL definition of rules in `.aegis/rules/*.yml`.
- **Rule packs**.
- **Analyzer author SDK**.

## V4: Debug-time UX
Theme: A debug session that actively teaches the user where to look.
- **Breakpoint-aware relevance ranking** in the detail panel.
- **Variable-at-breakpoint AI explanations**.
- **Call-stack hotspot overlay** on NeuroMap.
- **Profiler correlation**.

## V5: Team / multi-repo scale
Theme: Organizations running Aegis across many repos (without centralizing source code).
- **Cross-repo graph**.
- **Shared rule configs**.
- **Audit log export**.
- **CLI / CI runner**.

## Not on Roadmap
- Web-based version.
- Mobile language support.
- Auto-apply fixes.
- Cloud hosting of customer code.
