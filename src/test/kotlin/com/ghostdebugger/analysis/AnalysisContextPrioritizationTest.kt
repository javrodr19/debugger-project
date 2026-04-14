package com.ghostdebugger.analysis

import com.ghostdebugger.model.ParsedFile
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisContextPrioritizationTest {

    private fun pf(path: String): ParsedFile = ParsedFile(
        virtualFile = mockk<VirtualFile>(relaxed = true),
        path = path,
        extension = "kt",
        content = ""
    )

    @Test
    fun `returns all files when under the cap`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"))
        val out = prioritizeFiles(
            files = files, cap = 5,
            currentFilePath = { null },
            changedFilePaths = { emptyList() },
            recentFilePaths = { emptyList() },
            hotspotFilePaths = { emptyList() }
        )
        assertEquals(files, out)
    }

    @Test
    fun `puts current file first`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"), pf("/c.kt"), pf("/d.kt"))
        val out = prioritizeFiles(
            files = files, cap = 2,
            currentFilePath = { "/c.kt" },
            changedFilePaths = { emptyList() },
            recentFilePaths = { emptyList() },
            hotspotFilePaths = { emptyList() }
        )
        assertEquals(listOf("/c.kt", "/a.kt"), out.map { it.path })
    }

    @Test
    fun `ordering is current then changed then recent then hotspot then remaining`() {
        val files = listOf(
            pf("/a.kt"), pf("/b.kt"), pf("/c.kt"),
            pf("/d.kt"), pf("/e.kt"), pf("/f.kt")
        )
        val out = prioritizeFiles(
            files = files, cap = 5,
            currentFilePath = { "/f.kt" },
            changedFilePaths = { listOf("/a.kt") },
            recentFilePaths = { listOf("/e.kt") },
            hotspotFilePaths = { listOf("/d.kt") }
        )
        assertEquals(
            listOf("/f.kt", "/a.kt", "/e.kt", "/d.kt", "/b.kt"),
            out.map { it.path }
        )
    }

    @Test
    fun `deduplicates when a file appears in multiple buckets`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"), pf("/c.kt"))
        val out = prioritizeFiles(
            files = files, cap = 3,
            currentFilePath = { "/a.kt" },
            changedFilePaths = { listOf("/a.kt", "/b.kt") },
            recentFilePaths = { listOf("/a.kt") },
            hotspotFilePaths = { listOf("/b.kt") }
        )
        assertEquals(listOf("/a.kt", "/b.kt", "/c.kt"), out.map { it.path })
    }

    @Test
    fun `falls back to source order when no signals are available`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"), pf("/c.kt"))
        val out = prioritizeFiles(
            files = files, cap = 2,
            currentFilePath = { null },
            changedFilePaths = { emptyList() },
            recentFilePaths = { emptyList() },
            hotspotFilePaths = { emptyList() }
        )
        assertEquals(listOf("/a.kt", "/b.kt"), out.map { it.path })
    }

    @Test
    fun `cap of zero returns empty list`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"))
        val out = prioritizeFiles(
            files = files, cap = 0,
            currentFilePath = { "/a.kt" },
            changedFilePaths = { emptyList() },
            recentFilePaths = { emptyList() },
            hotspotFilePaths = { emptyList() }
        )
        assertEquals(emptyList(), out)
    }

    @Test
    fun `unknown paths in signals are ignored`() {
        val files = listOf(pf("/a.kt"), pf("/b.kt"))
        val out = prioritizeFiles(
            files = files, cap = 1,
            currentFilePath = { "/does-not-exist.kt" },
            changedFilePaths = { listOf("/nowhere.kt") },
            recentFilePaths = { emptyList() },
            hotspotFilePaths = { emptyList() }
        )
        assertEquals(listOf("/a.kt"), out.map { it.path })
    }
}
