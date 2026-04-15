package com.ghostdebugger.parser

import com.ghostdebugger.model.ParsedFile
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

class FileScanner(private val project: Project) {

    private val log = logger<FileScanner>()

    val supportedExtensions: Set<String> get() = SUPPORTED_EXTENSIONS
    
    private val ignoredDirs = setOf(
        "node_modules", ".git", "build", "dist", "out", ".gradle",
        "__pycache__", ".idea", "target", ".cache", "coverage"
    )

    companion object {
        internal val SUPPORTED_EXTENSIONS = setOf(
            "kt", "java",                       // JVM
            "ts", "tsx", "js", "jsx"            // Web
        )
    }

    fun scanFiles(): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots
        val fileIndex = ProjectFileIndex.getInstance(project)

        for (root in contentRoots) {
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    // Optimization: Fast-skip common heavy directories by name
                    if (file.isDirectory && file.name in ignoredDirs) {
                        return false
                    }

                    // Respect .gitignore and other IDE ignore settings
                    if (fileIndex.isExcluded(file)) {
                        return false
                    }
                    
                    if (file.isDirectory) {
                        return true
                    }
                    
                    if (file.extension in supportedExtensions) {
                        files.add(file)
                    }
                    return true
                }
            })
        }

        log.info("FileScanner: found ${files.size} files in project")
        return files
    }

    fun parsedFiles(virtualFiles: List<VirtualFile>): List<ParsedFile> {
        return virtualFiles.mapNotNull { vf ->
            try {
                val content = String(vf.contentsToByteArray(), Charsets.UTF_8)
                ParsedFile(
                    virtualFile = vf,
                    path = vf.path,
                    extension = vf.extension ?: "",
                    content = content
                )
            } catch (e: Exception) {
                log.warn("Could not read file: ${vf.path}", e)
                null
            }
        }
    }
}
