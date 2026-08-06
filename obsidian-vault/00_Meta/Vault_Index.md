---
title: "Vault Index"
type: "meta"
status: "active"
related_components: []
aliases:
  - "Index"
  - "Home"
tags:
  - aegis-debug
---

# Vault Index (Aegis Debug)

Welcome to the Aegis Debug knowledge vault. Aegis Debug is a high-performance IntelliJ IDEA plugin designed to find real bugs in code (TypeScript, JavaScript, Kotlin, Java) without compromising privacy.

## Core Pillars & Principles
- [[Project_Principles]] — The five non-negotiable guiding rules.
- [[Agent_Guardrails]] — Instructions and constraints for AI agents.
- [[Claude_Conventions]] — Claude-specific conventions and gotchas.

## Meta & History
- [[Roadmap]] — High-level roadmap from V2 to V5.
- [[Changelog]] — Release history and highlights.

## Features
- [[Static_Analyzers]] — 11 deterministic static analyzers.
- [[Deterministic_Fixers]] — One-click PSI-valid fix engine.
- [[V2_Dynamic_Validation]] — Runtime confirmation, debug & test runner cross-checks.
- [[Custom_Rule_Authoring]] — Declarative YAML rules in `.aegis/rules/` (V3.1).
- [[Rule_Packs]] — Curated and project-level rule packs (V3.2).
- [[Fix_Preview_UX]] — Line/hunk diff previews for fixes (V3.3).
- [[External_Analyzer_SDK]] — Dynamic JAR analyzer plugins (V3.4).
- [[Plugin_Actions]] — Editor popup & keymap action suite (Batches 1–3).

## Meta & History
- [[Roadmap]] — High-level roadmap from V2 to V5.
- [[Changelog]] — Release history and highlights.
- [[CI_and_Release_Automation]] — GitHub Actions CI & release packaging.

## Architecture
- [[GhostDebuggerService]] — Facade & single source of truth for project state.
- [[AnalysisOrchestrator]] — Analysis execution & dependent cascade orchestrator.
- [[UIEventRouter]] — Event dispatching & AI response caching.
- [[FileChangeWatcher]] — VFS event watcher & live document sync.
- [[DebugSessionCoordinator]] — Debug session cross-check & dynamic validation.
- [[BaseAIService]] — Shared parent for Ollama and OpenAI backends.
- [[KotlinAnalysisHelpers]] — Kotlin Analysis API single chokepoint (`withKtAnalysis`).
- [[JcefBridge_and_BridgeChannel]] — JCEF communication & serialization security.
- [[InMemoryGraph_and_Parser]] — Thread-safe dependency graph & PSI symbol extractors.
- [[NeuroMap_Webview]] — React-based visual project graph.
- [[ProblemsViewCoordinator]] — Integration with IntelliJ Problems tool window.

## Guides
- [[Build_and_Testing_Guide]] — Environment setup, JBR requirements, and test execution.
- [[Creating_New_Analyzers_Guide]] — Step-by-step guide to adding static analyzers.
- [[Creating_New_Fixers_Guide]] — Step-by-step guide to building deterministic fixers.
- [[Plugin_Configuration_Guide]] — Configurable thresholds, settings, and privacy options.

## Specs & Plans
All technical specs and implementation plans are located in the [[30_Specs_and_Plans/]] folder.
