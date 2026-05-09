# Aegis Debug — Claude conventions

This file documents project-specific build prerequisites, conventions, and gotchas for Claude sessions.

## Build prerequisites

The project uses the IntelliJ Platform Gradle Plugin (IPGP) 2.14.0 with `intellijIdeaCommunity("2024.3.2")`. Gradle's `instrumentTestCode` task requires `JAVA_HOME` to point at a JetBrains Runtime (JBR), not a generic JDK. On Linux the default `~/.jdks/ms-21.0.10` JDK fails with `Packages does not exist`.

Locate the bundled JBR:

```bash
find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1
```

Set both `JAVA_HOME` and `PATH`:

```bash
export JAVA_HOME=/path/to/jbr
export PATH=$JAVA_HOME/bin:$PATH
```

Then `./gradlew test`, `./gradlew verifyPlugin`, `./gradlew check`, `./gradlew buildPlugin` all work. The output zip is `build/distributions/ghostdebugger-<version>.zip`.

## Conventions

### Error handling

Every `catch (e: Exception)` block in production code MUST include `if (e is ProcessCanceledException) throw e` (rethrow). PCE is how IntelliJ cancels long-running operations; swallowing it leaves the IDE in an inconsistent state.

### Analyzer bias

When the Analysis API can't decide (e.g., `KaErrorType`, unresolved symbol), analyzers DO NOT flag. False positives erode trust faster than false negatives.

### Fixer principle

Deterministic fixes only. Every fixer must produce output that's PSI-valid; otherwise return null and let the AI fallback handle it.

### Kotlin Analysis API

Single chokepoint: `parser/KotlinAnalysisHelpers.withKtAnalysis(KtFile, KaSession.(KtFile) -> T): T?`. Wraps `analyze(file) { … }`, rethrows PCE, returns null on other failures. All Kotlin analyzers go through it.

Test base class: `AegisKotlinAnalysisTestCase` (in `src/test/kotlin/com/ghostdebugger/`). It pulls in Kotlin stdlib via `AegisKotlinStdlibProjectDescriptor` (locates `kotlin-stdlib.jar` inside the IDE distribution at runtime). Tests run **off-EDT** (`runInDispatchThread() = false`) because the Analysis API throws `ProhibitedAnalysisException` from the EDT.

### File structure

- Spec docs live at `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`
- Implementation plans at `docs/superpowers/plans/YYYY-MM-DD-<topic>.md`
- The version source-of-truth is `build.gradle.kts`. Gradle patches `plugin.xml` at build time; the inline `<version>` in `plugin.xml` should still match (cosmetic).

## Common gotchas

- **`trimIndent()` interpolation order**: Kotlin evaluates `${…}` *before* `.trimIndent()`. Interpolating a multi-line string into a raw-string template can leave 12+ spaces of leading whitespace per line in the output (V1.4 fixed this in `ReportGenerator`). Use `StringBuilder` for HTML/text output that interpolates multi-line content.
- **`KotlinLightProjectDescriptor` is unreachable in IPGP 2.14.0** — the test fixtures jar isn't on the classpath. V1.3 introduced `AegisKotlinStdlibProjectDescriptor` as a substitute. Use that for any new Analysis-API-dependent test.
- **`expressionType` doesn't always include smart-cast info** — for `var` reassignments and `is`-check / `if (x != null)` narrowing, you need `effectiveType` (consults `smartCastInfo` first) or `effectiveTypeWithStructuralSmartCast` (parent-chain walker, V1.4).

## Roadmap pointer

`docs/aegis_debug_roadmap_v2_to_v5.md` has the long-term north star. V2 = language breadth + IDE-native integration. V3 = fixer breadth + custom rules. V4 = debug-time UX. V5 = team/multi-repo.
