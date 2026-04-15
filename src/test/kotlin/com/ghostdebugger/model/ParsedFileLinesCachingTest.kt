package com.ghostdebugger.model

import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import io.mockk.mockk

class ParsedFileLinesCachingTest {

    @Test
    fun testLinesCaching() {
        val vf = mockk<VirtualFile>()
        val content = "line1\nline2\nline3"
        val file = ParsedFile(vf, "path", "kt", content)
        
        val lines1 = file.lines
        assertEquals(3, lines1.size)
        
        val lines2 = file.lines
        assertSame(lines1, lines2, "Lines should be cached")
    }
}
