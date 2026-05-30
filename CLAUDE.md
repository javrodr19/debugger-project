# Aegis Debug — Claude conventions

This file documents project-specific build prerequisites, conventions, and gotchas for Claude sessions.
It explains not just *what* each rule is but *why* it exists, so that future sessions can reason about
whether a rule applies rather than just pattern-match on the words.

## Build prerequisites

The project uses the IntelliJ Platform Gradle Plugin (IPGP) 2.14.0 with `intellijIdeaCommunity("2024.3.2")`.
Gradle's `instrumentTestCode` task requires `JAVA_HOME` to point at a JetBrains Runtime (JBR), not a generic
JDK. The reason is subtle: `instrumentTestCode` is IPGP's bytecode-instrumentation task that rewrites
`@NotNull`/`@Nullable` assertions into the compiled test classes. The implementation uses platform internals
that expect the JBR directory layout — specifically, it probes for a `Packages` directory that exists inside
the JBR but is absent in any generic OpenJDK or Microsoft JDK distribution. On Linux the default
`~/.jdks/ms-21.0.10` JDK fails with `Packages does not exist`; the build aborts before a single test runs.

Running `./gradlew test` without setting `JAVA_HOME` first will therefore fail unconditionally on any machine
that does not have JBR as the system Java. Always export the env-var before invoking Gradle:

Locate the bundled JBR:

```bash
find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1
```

Set both `JAVA_HOME` and `PATH`:

```bash
export JAVA_HOME=/path/to/jbr
export PATH=$JAVA_HOME/bin:$PATH
```

Then `./gradlew test`, `./gradlew verifyPlugin`, `./gradlew check`, `./gradlew buildPlugin` all work.
The output zip is `build/distributions/ghostdebugger-<version>.zip`.

## Conventions

### Error handling

Every `catch (e: Exception)` block in production code MUST include `if (e is ProcessCanceledException) throw e`
(rethrow). The reason matters here: `ProcessCanceledException` (PCE) is a `RuntimeException` subclass — it is
not a checked exception and does not stand out in catch-all handlers. IntelliJ uses it as the cancellation
mechanism throughout the platform: whenever the user clicks Cancel on a progress dialog, or the IDE itself
decides to abort a background operation, it throws PCE into whatever thread is doing the work.

If a catch block swallows PCE, the operation continues running even though the user asked it to stop. Worse,
the IDE's progress UI assumes that every cancellable operation is actually cancellable; it may stop drawing
the cancel button or mark the task as finished while the hidden computation still holds locks or mutates state.
The rule is: PCE must always escape catch blocks immediately, before any logging or cleanup that could mask it.

### Analyzer bias

When the Analysis API can't decide (e.g., `KaErrorType`, unresolved symbol), analyzers DO NOT flag.
False positives erode trust faster than false negatives.

The trust history matters here. V1.0 and V1.1 shipped with a few false-positive findings that affected real
user code in edge cases. It took two subsequent releases (1.1 and 1.2) to live those down, and users who had
been burned were slower to trust the tool's findings even after the bugs were fixed. The conservative-miss bias
is a deliberate product decision: Aegis Debug would rather have slightly weaker recall (miss a real bug
occasionally) than produce a spurious finding that a developer has to investigate and dismiss. Every false
positive has a cost in developer time and plugin credibility; false negatives are unfortunate but silent.

### Fixer principle

Deterministic fixes only. Every fixer must produce output that's PSI-valid; otherwise return null and let
the AI fallback handle it.

The Fixer interface's contract reflects V1.0's PSI-validity guarantee. A fixer that produces malformed output
(unbalanced braces, missing semicolons, broken import statements) leaves the file in a state where subsequent
PSI-based analysis throws exceptions or produces nonsensical results. Because Aegis Debug applies fixes inside
a write action and immediately re-reads the PSI tree, an invalid result propagates instantly. The `return null`
contract gives the orchestrator a clean signal to fall back to the AI path, which has no syntactic guarantees
but also does not corrupt the PSI. Any fixer that is not confident it can produce a parse-clean result for
every input it accepts must return null rather than guess.

### Kotlin Analysis API

**Single chokepoint**: `parser/KotlinAnalysisHelpers.withKtAnalysis(KtFile, KaSession.(KtFile) -> T): T?`

This function wraps `analyze(file) { … }`, rethrows PCE, and returns null on any other Analysis API failure.
All Kotlin analyzers go through it without exception. The reason for centralising here is that the Analysis API
has two session-state exceptions that are easy to trigger by mistake: `KaAnalysisNonPublicApiException` (thrown
when code calls an API marked `@KaExperimentalApi` without opting in) and `KaInvalidLifetimeOwnerAccessException`
(thrown when a `KaSession`-bound value escapes the `analyze { }` block and is accessed afterwards). By
funnelling every analyzer through `withKtAnalysis`, we have a single place to catch both exceptions and return
null gracefully. Adding a new Kotlin analyzer and forgetting to use the chokepoint is the most common mistake
new contributors make; the convention makes code review easier because any `analyze { }` call outside the helper
is immediately suspicious.

**Test base class**: `AegisKotlinAnalysisTestCase` (in `src/test/kotlin/com/ghostdebugger/`). It pulls in
Kotlin stdlib via `AegisKotlinStdlibProjectDescriptor`, which locates `kotlin-stdlib.jar` inside the IDE
distribution at runtime. Tests run **off-EDT** (`runInDispatchThread() = false`) because the Analysis API
throws `ProhibitedAnalysisException` from the EDT.

The reason we wrote our own descriptor rather than using the canonical `KotlinLightProjectDescriptor` is that
IPGP 2.14.0 does not expose the test fixtures jar that contains `KotlinLightProjectDescriptor` on the test
classpath. Referencing it compiles under some Gradle configurations but fails at runtime with
`ClassNotFoundException`. `AegisKotlinStdlibProjectDescriptor` substitutes by locating `kotlin-stdlib.jar`
directly within the unpacked IDE distribution at runtime — a path that is always available when running under
IPGP. Use `AegisKotlinAnalysisTestCase` as the base class for any test that exercises an Analysis API analyzer.

### Facade state ownership (post-V1.5)

`GhostDebuggerService` is the canonical project-level state holder for analysis results
(`currentIssues`, `issuesByFile`, `currentGraph`, `lastInMemoryGraph`, `suppressUntil`).
The four extracted collaborators (`AnalysisOrchestrator`, `UIEventRouter`,
`FileChangeWatcher`, `DebugSessionCoordinator`) **read** state from the facade via
`service.currentIssues` etc. and **write** via the facade's `internal fun updateIssues(...)`
mutator (or, for `currentGraph` / `lastInMemoryGraph`, by assigning to the `internal var`
fields). Direct assignment from a collaborator that bypasses the facade
(`orchestrator.currentIssues = ...`) is forbidden — it re-introduces the scattered
mutation V1.5 was designed to eliminate.

The reason this matters: V2 will add new collaborators (test-runner cross-check,
problems-tool-window emit path) that read the same state. If each collaborator owns its
own copy, the cross-collaborator views drift and the user sees stale or contradictory
issues across surfaces. One writer, many readers — through the facade.

When adding a new collaborator: register it as `@Service(Service.Level.PROJECT)` in
`plugin.xml`, register `Disposer.register(project, this)` in its `init { }`, and access
project-level state only via `GhostDebuggerService.getInstance(project)`. To emit JCEF
events, use `service.jcefBridge()?.send*()` for JcefBridge-only methods or
`service.bridgeChannel()?.send*()` for `BridgeChannel` methods (the latter respects the
test-recording stub installed via `setBridgeForTest`).

### Periodic commits

When developing new features or performing extensive refactoring, commit changes periodically at logical stopping points (e.g., after completing a component, resolving a compiler error, or getting a new test to pass). This preserves development history, prevents large unwieldy diffs, and matches best-practice git discipline.

### File structure

- Spec docs live at `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`
- Implementation plans at `docs/superpowers/plans/YYYY-MM-DD-<topic>.md`
- The version source-of-truth is `build.gradle.kts`. Gradle patches `plugin.xml` at build time; the inline
  `<version>` in `plugin.xml` should still match (cosmetic). V1.3 drifted and was fixed in commit `7e31776`;
  keep them in sync manually when bumping.

## Common gotchas

- **`trimIndent()` interpolation order**: Kotlin evaluates `${…}` *before* computing indentation. When you
  interpolate a multi-line string into a raw-string template and then call `.trimIndent()`, Kotlin first
  expands the interpolation — which may introduce lines that start at column 0 — and only then measures the
  common leading-whitespace prefix across *all* lines including the expanded result. If the interpolated text
  starts at column 0, the common indent is 0, and `trimIndent()` strips nothing from the surrounding template
  lines either. The result is that the surrounding template's indentation (e.g., 12 spaces) is left intact in
  the output. V1.4 hit this in `ReportGenerator` where interpolated HTML fragments caused 12-space indentation
  to bleed into the rendered report. The fix: avoid raw-string triple-quote templates that interpolate
  multi-line content. Use `StringBuilder` or explicit string concatenation instead; the indentation is then
  explicit and not subject to `trimIndent()`'s inference.

- **`KotlinLightProjectDescriptor` is unreachable in IPGP 2.14.0** — the test fixtures jar isn't on the
  classpath. V1.3 introduced `AegisKotlinStdlibProjectDescriptor` as a substitute. Use that for any new
  Analysis-API-dependent test. See the `withKtAnalysis` section above for the full explanation.

- **`expressionType` doesn't always include smart-cast info** — for `var` reassignments and `is`-check /
  `if (x != null)` narrowing, you need `effectiveType` (consults `smartCastInfo` first) or
  `effectiveTypeWithStructuralSmartCast` (parent-chain walker introduced in V1.4). Using `expressionType`
  directly on a smart-cast variable will return the declared (non-narrowed) type, causing false positives in
  the null-safety analyzer for code that is actually safe.

## Roadmap pointer

`docs/aegis_debug_roadmap_v2_to_v5.md` has the long-term north star. V2 = language breadth + IDE-native
integration. V3 = fixer breadth + custom rules. V4 = debug-time UX. V5 = team/multi-repo.
