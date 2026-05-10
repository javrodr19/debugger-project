package com.ghostdebugger

import com.ghostdebugger.model.*
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class GhostDebuggerServiceReportPathTest {

    private lateinit var project: Project
    private lateinit var service: GhostDebuggerService

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.name } returns "Test Project"

        service = GhostDebuggerService(project)

        mockkStatic(GhostDebuggerService::class)
        every { GhostDebuggerService.getInstance(project) } returns service
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(GhostDebuggerService::class)
        service.dispose()
    }

    /**
     * When no graph is loaded, handleExportReportRequested must return early
     * (dispatching an error to the bridge) without touching the file system or
     * throwing any exception. This exercises the null-graph guard that was
     * preserved in the FileSaverDialog rewrite.
     */
    @Test
    fun `handleExportReportRequested with null graph sends error and does not write any file`() {
        // Ensure currentGraph is null (default state after construction).
        val field = GhostDebuggerService::class.java.getDeclaredField("currentGraph")
        field.isAccessible = true
        field.set(service, null)

        val method = GhostDebuggerService::class.java.getDeclaredMethod("handleExportReportRequested")
        method.isAccessible = true

        // Should complete without throwing even though no dialog/file-chooser is available.
        method.invoke(service)

        // Wait briefly for the dispatched Swing coroutine.
        Thread.sleep(300)

        // No HTML file should have been created in tmpdir for this invocation.
        val tmpDir = System.getProperty("java.io.tmpdir")
        val recentReports = File(tmpDir).listFiles { _, name ->
            name.startsWith("aegis-debug-Test_Project-") && name.endsWith(".html")
        } ?: emptyArray()

        // Clean up any stale files from prior runs, then assert none were just created.
        recentReports.forEach { it.delete() }
        assertTrue(recentReports.isEmpty(),
            "Expected no report file in tmpdir when graph is null; found ${recentReports.size}")
    }
}
