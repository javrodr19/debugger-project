package com.ghostdebugger.fix.engine

import kotlinx.serialization.Serializable

@Serializable
enum class DiffLineType { UNCHANGED, ADDED, DELETED }

@Serializable
data class DiffLine(
    val type: DiffLineType,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val text: String
)

@Serializable
data class DiffHunk(
    val header: String,
    val lines: List<DiffLine>
)

@Serializable
data class FileFixDiff(
    val filePath: String,
    val issueId: String,
    val description: String,
    val isVerified: Boolean,
    val hunks: List<DiffHunk>
)

object FixDiffGenerator {

    fun generateDiff(
        filePath: String,
        issueId: String,
        description: String,
        originalContent: String,
        fixedContent: String,
        isVerified: Boolean = true
    ): FileFixDiff {
        val origLines = originalContent.lines()
        val fixedLines = fixedContent.lines()

        val diffLines = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        var origLineNum = 1
        var fixedLineNum = 1

        while (i < origLines.size || j < fixedLines.size) {
            if (i < origLines.size && j < fixedLines.size && origLines[i] == fixedLines[j]) {
                diffLines.add(DiffLine(DiffLineType.UNCHANGED, origLineNum, fixedLineNum, origLines[i]))
                i++
                j++
                origLineNum++
                fixedLineNum++
            } else if (j < fixedLines.size && (i >= origLines.size || !origLines.drop(i).contains(fixedLines[j]))) {
                diffLines.add(DiffLine(DiffLineType.ADDED, null, fixedLineNum, fixedLines[j]))
                j++
                fixedLineNum++
            } else {
                diffLines.add(DiffLine(DiffLineType.DELETED, origLineNum, null, origLines[i]))
                i++
                origLineNum++
            }
        }

        val hunks = mutableListOf<DiffHunk>()
        var currentHunkLines = mutableListOf<DiffLine>()
        var contextCount = 0

        for (line in diffLines) {
            if (line.type == DiffLineType.UNCHANGED) {
                if (currentHunkLines.any { it.type != DiffLineType.UNCHANGED }) {
                    currentHunkLines.add(line)
                    contextCount++
                    if (contextCount >= 3) {
                        val firstChanged = currentHunkLines.firstOrNull()
                        val header = "@@ -${firstChanged?.oldLineNumber ?: 1} +${firstChanged?.newLineNumber ?: 1} @@"
                        hunks.add(DiffHunk(header, currentHunkLines.toList()))
                        currentHunkLines = mutableListOf()
                        contextCount = 0
                    }
                }
            } else {
                contextCount = 0
                currentHunkLines.add(line)
            }
        }

        if (currentHunkLines.any { it.type != DiffLineType.UNCHANGED }) {
            val firstChanged = currentHunkLines.firstOrNull()
            val header = "@@ -${firstChanged?.oldLineNumber ?: 1} +${firstChanged?.newLineNumber ?: 1} @@"
            hunks.add(DiffHunk(header, currentHunkLines))
        }

        return FileFixDiff(filePath, issueId, description, isVerified, hunks)
    }
}
