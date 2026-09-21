---
title: "CI and Release Automation"
type: "meta"
related_components: []
aliases: []
tags:
  - ci
  - release
  - aegis-debug
---

# CI and Release Automation

Aegis Debug uses GitHub Actions workflows for continuous integration and automated release packaging.

## Workflows
1. **Continuous Integration (`.github/workflows/ci.yml`)**:
   - Runs on every pull request and on push to `main`, `stream/**`, and `v2-*`.
   - Provisions JetBrains Runtime (JBR) via `actions/setup-java@v4` (`distribution: jetbrains`)
     for accurate test execution.
   - Runs a single `./gradlew test detekt verifyPlugin` invocation (one command, all three
     Gradle tasks).
   - `verifyPlugin` checks compatibility against the **four** IDE targets declared in
     `build.gradle.kts`'s `pluginVerification.ides` block: IntelliJ IDEA Ultimate 2024.3.2.2,
     2025.1, 2026.1, and 2026.2.
2. **Draft Release Packaging (`.github/workflows/release.yml`)**:
   - Triggers on tag pushes matching `v*`.
   - Runs `./gradlew buildPlugin` and attaches `ghostdebugger-<version>.zip` to GitHub Releases
     as a **draft** (`draft: true` — never auto-published).
   - Deliberately omits `./gradlew publishPlugin`: nothing in this repo publishes to the
     JetBrains Marketplace.
3. **Site Deploy (`.github/workflows/site.yml`)**:
   - Publishes `site/` to the `gh-pages` branch on push to `main` when `site/**` (or the
     workflow file itself) changes, or via manual `workflow_dispatch`.

## Git Standards & Conventions
- **Commit Messages**: Enforced via `.gitmessage` template, `CONTRIBUTING.md`, and `.githooks/commit-msg`.
- **Branch & Tag Discipline**: Merged feature branches pruned; tags follow `v.<version>` format (e.g. `v.1.5.0`).
