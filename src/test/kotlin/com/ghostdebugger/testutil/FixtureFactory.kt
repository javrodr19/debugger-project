package com.ghostdebugger.testutil

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.ParsedFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.mockk

object FixtureFactory {

    fun parsedFile(path: String, ext: String, content: String): ParsedFile =
        ParsedFile(
            virtualFile = mockk<VirtualFile>(relaxed = true),
            path = path,
            extension = ext,
            content = content
        )

    fun context(
        files: List<ParsedFile>,
        graph: InMemoryGraph = InMemoryGraph(),
        project: Project = mockk(relaxed = true)
    ): AnalysisContext = AnalysisContext(graph = graph, project = project, parsedFiles = files)
}
