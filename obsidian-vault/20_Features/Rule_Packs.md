---
title: "Rule Packs (V3.2)"
type: "feature"
status: "active"
related_components:
  - "[[Custom_Rule_Authoring]]"
  - "[[GhostDebuggerService]]"
tags:
  - feature
  - rule-packs
  - aegis-debug
---

# Rule Packs (V3.2)

Rule Packs are curated, togglable rule bundles that allow users to enable or disable groups of rules for specific frameworks, libraries, or security domains per project.

## Bundled Packs
Aegis Debug ships with three bundled rule packs stored as YAML resources:
1. **React Strict Practices** (`react-strict.yml`) — Enforces clean React lifecycle, hook discipline, and prevents direct state mutation or `eval`.
2. **Kotlin Coroutines Safety** (`kotlin-coroutines.yml`) — Prevents common coroutine pitfalls and swallowing of `ProcessCanceledException`.
3. **Node.js Security Guard** (`node-security.yml`) — Security rules preventing command injection (e.g. `child_process.exec`) and unsafe dynamic execution.

## Project-Level Custom Packs
Projects can declare custom rule packs in `.aegis/packs/*.yml`. These are automatically discovered and loaded by `RulePackService`.

## Precedence Resolution
Repo-specific rules declared in `.aegis/rules/*.yml` override rules loaded from active Rule Packs if they share the same rule ID.
