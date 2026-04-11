package com.ghostdebugger.parser

import com.ghostdebugger.model.ParsedFile
import java.io.File

data class DependencyRelation(
    val fromPath: String,
    val toPath: String,
    val importSource: String
)

class DependencyResolver(private val projectBasePath: String) {

    fun resolve(parsedFiles: List<ParsedFile>): List<DependencyRelation> {
        val relations = mutableListOf<DependencyRelation>()
        val pathIndex = buildPathIndex(parsedFiles)

        for (file in parsedFiles) {
            for (import in file.imports) {
                if (isRelativeImport(import.source)) {
                    val resolvedPath = resolveRelativePath(file.path, import.source)
                    val targetFile = findTargetFile(resolvedPath, pathIndex)
                    if (targetFile != null) {
                        relations.add(
                            DependencyRelation(
                                fromPath = file.path,
                                toPath = targetFile,
                                importSource = import.source
                            )
                        )
                    }
                }
            }
        }

        return relations
    }

    private fun buildPathIndex(files: List<ParsedFile>): Map<String, String> {
        return files.associate { f ->
            val normalized = f.path.replace("\\", "/")
            val withoutExt = normalized.substringBeforeLast(".")
            withoutExt to f.path
        }
    }

    private fun isRelativeImport(source: String): Boolean {
        return source.startsWith("./") || source.startsWith("../")
    }

    private fun resolveRelativePath(fromPath: String, importSource: String): String {
        val fromDir = File(fromPath).parent ?: ""
        val resolved = File(fromDir, importSource).canonicalPath
        return resolved.replace("\\", "/")
    }

    private fun findTargetFile(resolvedPath: String, pathIndex: Map<String, String>): String? {
        val normalized = resolvedPath.replace("\\", "/")
        // Try with and without extension
        return pathIndex[normalized]
            ?: pathIndex["$normalized/index"]
            ?: pathIndex.entries.firstOrNull { (k, _) ->
                k.endsWith(normalized.substringAfterLast("/"))
            }?.value
    }
}
