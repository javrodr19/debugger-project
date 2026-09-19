package com.ghostdebugger.settings

import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Every control the settings panel shows must change behaviour somewhere. Five controls shipped
 * through 3.0.0-pre with no read site at all; this test stops them coming back.
 *
 * `showUnreached` is deliberately NOT in this list: it is read by `JcefBridge` and plumbed into
 * the webview store (`appStore.ts`), so it has a real read site even though the leaf UI does not
 * yet consume it. That is a different defect from having zero read sites, and is out of scope
 * here.
 */
class NoDeadSettingsTest {

    private val removedFields = listOf(
        "autoAnalyzeOnOpen",
        "showInfoIssues",
        "analyzeOnlyChangedFiles",
        "coverageMode",
        "nudgeShownOnce",
    )

    @Test
    fun `removed settings fields are absent from the state class`() {
        val source = java.io.File("src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerSettings.kt")
            .readText()
        removedFields.forEach { field ->
            assertFalse(source.contains(field), "$field was removed as dead; do not reintroduce it")
        }
    }

    @Test
    fun `removed settings controls are absent from the configurable`() {
        val source = java.io.File("src/main/kotlin/com/ghostdebugger/settings/GhostDebuggerConfigurable.kt")
            .readText()
        removedFields.forEach { field ->
            assertFalse(source.contains(field), "$field was removed as dead; do not reintroduce it")
        }
    }
}
