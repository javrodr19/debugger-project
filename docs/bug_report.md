# Aegis Debug — Comprehensive Codebase Audit & Bug Report

This document presents the results of a comprehensive technical audit of the Aegis Debug codebase, conducted on May 31, 2026. The entire Kotlin and Java backend source code (`src/main/kotlin/com/ghostdebugger/`) has been analyzed. 

A total of **26 bugs** have been identified, including several **CRITICAL** and **HIGH** severity JetBrains Platform API misuse issues (such as thread-safety violations, un-disposed resources, UI leaks, and background thread Read Action violations) that prevent key features from functioning correctly in production.

---

## ✅ Resolution Status — 2026-05-31

Every finding was re-verified against the current code before any change. **23 fixed, 1 hardened, 2 rejected as false positives.** Verification: `./gradlew compileKotlin compileTestKotlin` and the full `./gradlew test` suite (**277 tests**) pass; regression tests were added for BUG-18, BUG-21, and BUG-22.

| Bug(s) | Status | Resolution |
|:---|:---:|:---|
| BUG-02, 10, 13, 14 (PCE swallow) | ✅ Fixed | `if (e is ProcessCanceledException) throw e` as the first action in each catch (`UIEvent`, `JcefBridge`×2, `NeuroMapPanel`) |
| BUG-01 (double `initialize()`) | ✅ Fixed | `JcefBridge.initialize()` made idempotent; removed the redundant call in `NeuroMapPanel` |
| BUG-04, 11 (JCEF handlers) | ✅ Fixed | Explicit `removeDisplayHandler`/`removeLoadHandler` on teardown. NB: impact was *bounded by browser disposal* (browser owns/disposes its client), not the "permanent leak" described — this is deterministic cleanup |
| BUG-05 (JAR URI + dead var) | ✅ Fixed | `JarURLConnection.jarFileURL.toURI()` (handles spaces/Unicode); removed unused `rawJarPath` |
| BUG-06 (temp-dir disk leak) | ✅ Fixed | `deleteRecursively()` via a `Disposer` callback (LIFO-ordered after the browser) instead of `deleteOnExit()` |
| BUG-07 (dead code) | ✅ Fixed | Removed unused `verifyExtraction()` |
| BUG-12 (boolean serialization) | ✅ Fixed | Emit a real JSON boolean via `buildJsonObject`. **Nuance:** the "UI hangs" symptom did *not* actually occur — the TS receiver (`pluginBridge.ts`) already coerced `isComplete === 'true' || === true`. The fix is still correct (proper contract, matches the internal TS `boolean` type) |
| BUG-15, 16 (read-action) | ✅ Fixed | Wrapped `fromPsi()` in `runReadAction` in both PSI extractors. (`readPsi()` was already correct — only the `fromPsi` call escaped the read lock) |
| BUG-17 (unused `parentDisposable`) | ✅ Fixed | `RuntimeEvidenceStore.addListener` now registers listener removal against the disposable |
| BUG-18 (Windows path) | ✅ Fixed | `substringAfterLast('/').substringAfterLast('\\')` + regression test |
| BUG-19 (coverage read-action) | ✅ Fixed | Wrapped the `findFile`/`psiFile.classes` walk in `harvestCoverage()` in `runReadAction` |
| BUG-20 (OkHttpClient per-run) | ✅ Fixed | Single shared `OkHttpClient`; subclasses derive timeouts via `newBuilder()` (shares pool + dispatcher) |
| BUG-21, 22 (categorization) | ✅ Fixed | `IssueType.COMPILATION_ERROR` for cast/type-mismatch findings + regression assertions |
| BUG-23 (intention-action race) | ✅ Fixed | `invoke()` recomputes the issue from the live caret (no shared field read); a `@Volatile` display-only title drives the menu text |
| BUG-24 (no reanalyze after fix) | ✅ Fixed | `AegisLocalQuickFix.applyFix` calls `reanalyzeFile` on success, matching the other two fix paths |
| BUG-25 (router scope leak) | ✅ Fixed | `UIEventRouter` implements `Disposable`, registers with the project, and cancels its scope in `dispose()` |
| BUG-26 (configurable leak) | ✅ Fixed | Added `disposeUIResources()` that nulls the Swing references |
| BUG-09 (hardcoded tool-window id) | 🔧 Hardened | The id `"GhostDebugger"` *matched* `plugin.xml`, so cancel-on-hide worked; switched to the captured `toolWindow` reference to remove the latent fragility |
| BUG-03 (relative web paths) | ❌ Rejected | Not a functional bug. The `File("src/main/resources/web/...")` / `build/...` lookups are dev/sandbox fallbacks tried *before* the classpath/JAR resolution, which still succeeds in production. They fail harmlessly and mask nothing; removing them would risk the `runIde` dev workflow |
| BUG-08 (`Content` as parentDisposable) | ❌ Rejected | Not a bug. `Content` is the correct lifecycle anchor — JCEF resources should live exactly as long as the tool-window content and be torn down when it is removed/rebuilt. The report's implied "dispose on hide" would be incorrect behavior |

> **Note on counts:** the summary table below lists 5 CRITICAL but only 4 PCE bugs (BUG-02/10/13/14) are itemized under CRITICAL; all 26 BUG-IDs (01–26) are nonetheless present and accounted for above.

---

## 1. Summary of Findings

| Severity | Count | Key Impact |
|:---|:---:|:---|
| 🔴 **CRITICAL** | 5 | Swallowed `ProcessCanceledException` (PCE) in critical communication, JS execution, and event parsing pathways, corrupting the IDE progress and cancellation framework. |
| 🟠 **HIGH** | 12 | Background Read Action violations (crashing symbol extraction and test coverage on background threads), memory leaks (Cef client handlers, JBCefJSQuery, coroutines), and UI state desynchronizations. |
| 🟡 **MEDIUM** | 7 | Windows path compatibility bugs, dead code, memory leaks in settings configurable, and JCEF type serialization errors. |
| 🟢 **LOW** | 2 | UI categorization mismatches. |
| **TOTAL** | **26** | |

---

## 2. Complete Bug Directory

### 🔴 CRITICAL SEVERITY (5 Bugs)

These bugs represent direct violations of **AGENTS.md §5.1 / CLAUDE.md**, swallowing `ProcessCanceledException` (PCE) in production.Swallowing PCE corrupts platform locks, causes progress indicators to freeze, and leaves operations running after cancellation.

#### 🐜 BUG-02: PCE Swallow in `NeuroMapPanel.initJcef()`
- **File:** [NeuroMapPanel.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/toolwindow/NeuroMapPanel.kt#L74-L80)
- **Lines:** 74–80
- **Category:** PCE Swallow / Thread-Safety
- **Description:** The `catch (e: Exception)` block in `initJcef()` swallows all exceptions, including `ProcessCanceledException`. The block must explicitly rethrow `ProcessCanceledException` to ensure the platform can cancel panel initialization safely.

#### 🐜 BUG-10: PCE Swallow inside `JBCefJSQuery` Handler
- **File:** [JcefBridge.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt#L49-L51)
- **Lines:** 49–51
- **Category:** PCE Swallow
- **Description:** The query handler `catch (e: Exception)` catches all exceptions thrown during UI event parsing and dispatch (which routes through `UIEventRouter` and can trigger background coroutine cascades) but does not rethrow `ProcessCanceledException`, corrupting platform progress/cancellation bookkeeping.

#### 🐜 BUG-13: PCE Swallow in `JcefBridge.executeJS()`
- **File:** [JcefBridge.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt#L252-L258)
- **Lines:** 252–258
- **Category:** PCE Swallow
- **Description:** `executeJS()` executes JavaScript inside the JCEF browser and wraps the operation in a generic `catch (e: Exception)` block that swallows `ProcessCanceledException`. All bridge `send*` methods route through `executeJS()`, so any cancellation during UI updates is silently discarded.

#### 🐜 BUG-14: PCE Swallow in `UIEventParser.parse()`
- **File:** [UIEvent.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/bridge/UIEvent.kt#L101-L103)
- **Lines:** 101–103
- **Category:** PCE Swallow
- **Description:** `UIEventParser.parse()` wraps the entire JSON deserialization and type-mapping logic in a `try/catch (e: Exception)` block that returns `UIEvent.Unknown(message)` on failure. A `ProcessCanceledException` propagated during parsing is swallowed, returning a spurious `Unknown` event instead of cancelling.

---

### 🟠 HIGH SEVERITY (12 Bugs)

These bugs cause persistent memory leaks (JCEF resources, thread executors), background thread crashes due to lack of read/write locks, or significant UI/state desynchronization.

#### 🐜 BUG-01: Double `bridge.initialize()` (JSQuery Leak & Duplication)
- **File:** [NeuroMapPanel.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/toolwindow/NeuroMapPanel.kt#L56-L57)
- **Lines:** 56–57
- **Category:** API Misuse / Resource Leak
- **Description:** `bridge.initialize()` is called explicitly in `NeuroMapPanel`, then `service.setBridge(bridge)` is immediately called. However, `GhostDebuggerService.setBridge()` (in [GhostDebuggerService.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/GhostDebuggerService.kt#L116)) calls `bridge.initialize()` a **second time**. Each call to `initialize()` instantiates a new `JBCefJSQuery` and adds a new `CefLoadHandler` to the browser client. The first `JBCefJSQuery` is orphaned and leaked, and duplicate load handlers will inject the bridge script twice on every page load.

#### 🐜 BUG-03: Relative Paths Resolved Against JVM Working Dir
- **File:** [NeuroMapPanel.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/toolwindow/NeuroMapPanel.kt#L95-L106)
- **Lines:** 95–99, 102–106
- **Category:** Logic Error / Directory Resolution
- **Description:** Relative file paths like `File("src/main/resources/web/index.html")` and `File("build/resources/main/web/index.html")` are used to locate web resources. In a running IDE, relative paths resolve against the IDE installation directory (`idea.home`), **not** the plugin project directory. This causes the lookup to always silently fail and fall back to JAR extraction, masking classpath packaging errors.

#### 🐜 BUG-04: Memory Leak in `NeuroMapPanel` Display Handler
- **File:** [NeuroMapPanel.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/toolwindow/NeuroMapPanel.kt#L59-L70)
- **Lines:** 59–70
- **Category:** Memory Leak / Resource Leak
- **Description:** `jbBrowser.jbCefClient.addDisplayHandler(...)` registers an anonymous `CefDisplayHandlerAdapter` that is never removed (no corresponding `removeDisplayHandler` call exists). Because JCEF client handlers hold strong references, this anonymous object (and its implicit reference to `NeuroMapPanel`) is leaked permanently when the panel is closed.

#### 🐜 BUG-08: Fragile Content-Based Parent Disposable for JCEF
- **File:** [GhostDebuggerToolWindowFactory.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/toolwindow/GhostDebuggerToolWindowFactory.kt#L21)
- **Lines:** 21
- **Category:** API Misuse / Disposable Lifecycle Leak
- **Description:** `NeuroMapPanel(project, content)` passes the `Content` object as the `parentDisposable`. Heavy resources (JBCefBrowser, JcefBridge) are registered against this `parentDisposable`. Since `Content` is a UI content slot managed by the content manager, using it directly as the parent disposable for heavy JCEF components is fragile. If the tool window is hidden or rebuilt, JCEF threads/resources are not properly torn down, causing memory leaks. The correct approach is to register them against the `content` manager's lifetime or a dedicated Disposer lifecycle.

#### 🐜 BUG-11: Memory Leak in `JcefBridge` Load Handler
- **File:** [JcefBridge.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt#L55-L61)
- **Lines:** 55–61, 260–263
- **Category:** Memory Leak / Resource Leak
- **Description:** `browser.jbCefClient.addLoadHandler(...)` is called inside `JcefBridge.initialize()` but is never removed during `dispose()`. The load handler's `onLoadEnd` closure captures a reference to the `JcefBridge` instance via `query`, preventing the entire bridge from ever being garbage collected.

#### 🐜 BUG-15: Kotlin PSI Symbol Extractor Read Action Violation
- **File:** [KotlinPsiSymbolExtractor.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/parser/KotlinPsiSymbolExtractor.kt#L26-L38)
- **Lines:** 26–38
- **Category:** API Misuse / Read Action Violation
- **Description:** `fromPsi()` parses imports, variables, named functions, and class declarations from the `KtFile`. Accessing these PSI structures requires a platform **Read Action**. Since `SymbolExtractor.extract()` is called inside a background thread in `AnalysisOrchestrator` without wrapping the call in `runReadAction`, it throws a `ReadAccessException`. This is caught by `runCatching` on line 32 and logged as a warning, which causes symbol extraction for Kotlin files to **always** silently fall back to the lower-quality regex extractor in production.

#### 🐜 BUG-16: Java PSI Symbol Extractor Read Action Violation
- **File:** [JavaPsiSymbolExtractor.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/parser/JavaPsiSymbolExtractor.kt#L22-L34)
- **Lines:** 22–34
- **Category:** API Misuse / Read Action Violation
- **Description:** Identical to BUG-15: `fromPsi()` walks the Java file classes, imports, and methods without holding the read lock. Because `SymbolExtractor.extract()` is called on a background coroutine thread, it throws a `ReadAccessException` on PSI access, logging a warning and silently falling back to the basic regex-based parser.

#### 🐜 BUG-17: Persistent Memory Leak in `RuntimeEvidenceStore.addListener`
- **File:** [RuntimeEvidenceStore.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/store/RuntimeEvidenceStore.kt#L79-L83)
- **Lines:** 79–83
- **Category:** Disposable Leak / Memory Leak
- **Description:** `addListener(listener, parentDisposable)` accepts a `parentDisposable` parameter but never uses it or registers any cleanup callback against it. Listeners added by temporary UI panels or actions are never removed, resulting in a persistent memory leak of those temporary classes.

#### 🐜 BUG-19: Read Action Violation in `TestRunObserver.harvestCoverage`
- **File:** [TestRunObserver.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/store/TestRunObserver.kt#L113-L126)
- **Lines:** 113–126
- **Category:** API Misuse / Read Action Violation
- **Description:** `harvestCoverage()` accesses `PsiManager.getInstance(project).findFile(virtualFile)` and walks `psiFile.classes` (cast to `PsiClassOwner`) to locate fully qualified class names. Accessing these properties requires a **Read Action**. Since the SMRunner test listener callback `onTestingFinished()` runs on a background thread without a read lock, this throws a `ReadAccessException`. The exception is caught and logged, completely disabling dynamic test coverage evidence harvesting in production.

#### 🐜 BUG-20: Thread and Socket Leak via `OkHttpClient` Recreation
- **File:** [BaseAIService.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/ai/BaseAIService.kt#L26-L30)
- **Lines:** 26–30
- **Category:** Thread Leak / Resource Leak
- **Description:** `OkHttpClient` is instantiated inside the abstract `BaseAIService` constructor. Since `AIServiceFactory.create()` instantiates a fresh `OpenAIService` or `OllamaService` on **every single project analysis run**, a new connection pool and thread executor are spawned each time. Because `OkHttpClient.dispatcher.executorService` is never shut down, this causes a severe thread and socket leak in the JVM.

#### 🐜 BUG-23: Thread-Safety Race Condition in Intention Action Singleton
- **File:** [AegisQuickFixIntentionAction.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/intentions/AegisQuickFixIntentionAction.kt#L24)
- **Lines:** 24, 57, 67
- **Category:** Concurrency / Shared State Race
- **Description:** `AegisQuickFixIntentionAction` is registered as a singleton intention action. It stores the matching issue in a mutable member field `activeIssue` in `isAvailable()` and reads it in `invoke()`. Because `isAvailable()` is executed frequently on background threads for different editors and carets, the active issue is easily overwritten or set to `null` before `invoke()` runs, causing the wrong quick-fix to apply or no quick-fix to fire.

#### 🐜 BUG-25: Coroutine Thread Leak in `UIEventRouter`
- **File:** [UIEventRouter.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/UIEventRouter.kt#L39)
- **Lines:** 39, 42
- **Category:** Coroutine Misuse / Project Reference Leak
- **Description:** `UIEventRouter` spawns background coroutines inside a custom `CoroutineScope(Dispatchers.Default + SupervisorJob())`. However, it does not implement `Disposable` and never registers itself with the project disposer. When a project is closed, this scope remains active, leaking the disposed `Project` reference and keeping background threads alive indefinitely.

---

### 🟡 MEDIUM SEVERITY (7 Bugs)

These bugs cause functional failures under specific environments (Windows paths), resource leakage (disk files), and API serialization bugs.

#### 🐜 BUG-05: Malformed URI Construction for Spaces and Unicode in Classpath
- **File:** [NeuroMapPanel.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/toolwindow/NeuroMapPanel.kt#L193-L195)
- **Lines:** 193–195
- **Category:** Logic Error / Resource Leak
- **Description:** `rawJarPath` is calculated at line 193 but never used. At line 195, `java.net.URI(resource.path.substringBefore("!"))` is used. However, `URL.path` is not percent-decoded; constructing a `java.net.URI` from it will throw a `URISyntaxException` if the classpath contains spaces or Unicode characters. `URL.toURI()` must be used instead.

#### 🐜 BUG-06: Temporary Directory Disk Space Leak
- **File:** [NeuroMapPanel.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/toolwindow/NeuroMapPanel.kt#L156-L158)
- **Lines:** 156–158
- **Category:** Disk Resource Leak
- **Description:** `Files.createTempDirectory("ghostdebugger-web")` registers the directory for deletion on JVM exit using `deleteOnExit()`. However, `deleteOnExit()` only deletes empty directories; it does **not** recursively delete directories containing files. The entire extracted web assets folder (HTML, JS, CSS) remains permanently on disk, leaking megabytes of space across IDE restarts.

#### 🐜 BUG-09: Fragile Hardcoded Tool Window ID Lookup
- **File:** [GhostDebuggerToolWindowFactory.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/toolwindow/GhostDebuggerToolWindowFactory.kt#L35)
- **Lines:** 35
- **Category:** Logic Error / Hardcoding
- **Description:** `toolWindowManager.getToolWindow("GhostDebugger")` resolves the tool window using the hardcoded string `"GhostDebugger"`. However, the tool window display title is set to `"Aegis Debug"` at line 25. If the registered ID in `plugin.xml` changes, the lookup returns `null`, and the automated cancellation of active analysis on panel hide will silently fail.

#### 🐜 BUG-12: JS Boolean Serialization Bug (`isComplete` Serialized as String)
- **File:** [JcefBridge.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/bridge/JcefBridge.kt#L149-L153)
- **Lines:** 149–153, 209–212
- **Category:** Serialization Error / Type Mismatch
- **Description:** In `sendIssueExplanationChunk` and `sendSystemExplanationChunk`, the `isComplete` parameter is serialized as a string (`"true"`/`"false"`) instead of a JSON boolean. This results in the payload `{"isComplete": "true"}`. In TypeScript, a strict boolean check like `payload.isComplete === true` will evaluate to `false`, causing the UI to wait indefinitely for chunks.

#### 🐜 BUG-18: Windows Path Compatibility Bug in `StackTraceParser`
- **File:** [StackTraceParser.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/store/StackTraceParser.kt#L37)
- **Lines:** 37
- **Category:** Windows Compatibility
- **Description:** `substringAfterLast('/')` is used to extract the simple filename from parsed stacktrace frames. If the stacktrace contains Windows absolute paths (e.g. `at (C:\project\src\File.ts:12:4)`), it fails to find `/`, leaving the full path with backslashes as the filename. This subsequently fails to match against slash-normalized file paths in the `TestRunObserver` cross-checker.

#### 🐜 BUG-24: State Desynchronization in Local Inspection Quick-Fix
- **File:** [AegisLocalInspection.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/inspections/AegisLocalInspection.kt#L25-L27)
- **Lines:** 25–27
- **Category:** Logic Error / State Desynchronization
- **Description:** `AegisLocalQuickFix.applyFix` successfully updates the source code in the editor, but fails to call `AnalysisOrchestrator.reanalyzeFile()`. Consequently, even though the user has corrected the code, the issue remains active and visible in `GhostDebuggerService.currentIssues` and the JCEF NeuroMap UI until they manually trigger a full analysis.

#### 🐜 BUG-26: UI Component Memory Leak in `GhostDebuggerConfigurable`
- **File:** [GhostDebuggerConfigurable.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt#L13)
- **Lines:** 13, 15–37
- **Category:** Memory Leak / JComponent Leak
- **Description:** `GhostDebuggerConfigurable` holds strong references to complex JComponents (like `suppressionTable`, `maxFilesSpinner`, `panel`) in its member variables. However, it does not override `disposeUIResources()` to null them out. This prevents the settings Swing hierarchy from being cleaned up when the settings dialog closes, leaking significant UI resources.

---

### 🟢 LOW SEVERITY (2 Bugs)

These represent harmless cosmetic or categorization errors that do not affect correctness.

#### 🐜 BUG-07: Dead Code in `NeuroMapPanel.verifyExtraction()`
- **File:** [NeuroMapPanel.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/toolwindow/NeuroMapPanel.kt#L237-L243)
- **Lines:** 237–243
- **Category:** Logic Error / Dead Code
- **Description:** `verifyExtraction` is implemented to log extracted assets but is never called in production, serving only as unused residual development code.

#### 🐜 BUG-21 & 🐜 BUG-22: Wrong Categorization for Casting and Type Mismatch Errors
- **Files:** [KotlinUnsafeCastAnalyzer.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/analysis/analyzers/KotlinUnsafeCastAnalyzer.kt#L62) & [KotlinTypeMismatchAnalyzer.kt](file:///home/javier/Desktop/debugger-project/src/main/kotlin/com/ghostdebugger/analysis/analyzers/KotlinTypeMismatchAnalyzer.kt#L57)
- **Lines:** 62 (UnsafeCast) & 57 (TypeMismatch)
- **Category:** Logic Error / Categorization Mismatch
- **Description:** Both analyzers categorize their findings under `IssueType.NULL_SAFETY` instead of `IssueType.COMPILATION_ERROR`. This causes compiler casting and assignment errors to be grouped under null-safety categories in UI listings.

---

## 3. Recommended Remediation Strategy

1. **PCE Swallows:** Update the catch blocks in `NeuroMapPanel.kt` (line 74), `JcefBridge.kt` (lines 49, 252), and `UIEvent.kt` (line 101) to explicitly check and rethrow `ProcessCanceledException`:
   ```kotlin
   if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
   ```
2. **PSI Read Actions:** Wrap all `fromPsi` invocations inside `KotlinPsiSymbolExtractor` and `JavaPsiSymbolExtractor` inside `ApplicationManager.getApplication().runReadAction { ... }`.
3. **Disposal Lifecycle:**
   - Null out Swing references in `GhostDebuggerConfigurable.disposeUIResources()`.
   - Properly register `RuntimeEvidenceStore` listener cleanups upon `parentDisposable` disposal.
   - Register the `UIEventRouter` scope with the project Disposer so it is cancelled on close.
4. **Local Quick-Fix Sync:** Ensure `AegisLocalQuickFix.applyFix` invokes `AnalysisOrchestrator.getInstance(project).reanalyzeFile(...)` upon successful application.
