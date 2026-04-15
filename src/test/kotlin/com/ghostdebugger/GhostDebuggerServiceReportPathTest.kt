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

    @Test
    fun `test report is created in tmpdir with correct name pattern`() {
        val graph = ProjectGraph(
            metadata = GraphMetadata(
                projectName = "Test Project",
                totalFiles = 1,
                totalIssues = 0,
                analysisTimestamp = System.currentTimeMillis(),
                healthScore = 100.0
            ),
            nodes = emptyList(),
            edges = emptyList()
        )
        
        val field = GhostDebuggerService::class.java.getDeclaredField("currentGraph")
        field.isAccessible = true
        field.set(service, graph)
        
        val method = GhostDebuggerService::class.java.getDeclaredMethod("handleExportReportRequested")
        method.isAccessible = true
        method.invoke(service)
        
        // Wait for the async export
        Thread.sleep(1000)
        
        val tmpDir = System.getProperty("java.io.tmpdir")
        val reportFiles = File(tmpDir).listFiles { _, name -> 
            name.startsWith("aegis-debug-Test_Project-") && name.endsWith(".html")
        }
        
        assertNotNull(reportFiles, "Report file list should not be null")
        assertTrue(reportFiles!!.isNotEmpty(), "Report file not found in $tmpDir")
        
        reportFiles.forEach { it.delete() }
    }
}
