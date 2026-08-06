---
title: "CI and Release Automation"
type: "meta"
status: "active"
related_components: []
tags:
  - ci
  - release
  - aegis-debug
---

# CI and Release Automation

Aegis Debug uses GitHub Actions workflows for continuous integration and automated release packaging.

## Workflows
1. **Continuous Integration (`.github/workflows/ci.yml`)**:
   - Provisions JetBrains Runtime (JBR) for accurate test execution.
   - Runs `./gradlew test`, `./gradlew detekt`, and `./gradlew verifyPlugin`.
2. **Draft Release Packaging (`.github/workflows/release.yml`)**:
   - Triggers on tag pushes matching `v.*`.
   - Runs `./gradlew buildPlugin` and attaches `ghostdebugger-<version>.zip` to GitHub Releases as a draft.

## Git Standards & Conventions
- **Commit Messages**: Enforced via `.gitmessage` template, `CONTRIBUTING.md`, and `.githooks/commit-msg`.
- **Branch & Tag Discipline**: Merged feature branches pruned; tags follow `v.<version>` format (e.g. `v.2.0.0-beta.1`).
