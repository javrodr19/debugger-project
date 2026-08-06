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
        val diffLines = computeDiffLines(originalContent.lines(), fixedContent.lines())
        val hunks = groupLinesIntoHunks(diffLines)
        return FileFixDiff(filePath, issueId, description, isVerified, hunks)
    }

    private fun computeDiffLines(origLines: List<String>, fixedLines: List<String>): List<DiffLine> {
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
        return diffLines
    }

    private fun groupLinesIntoHunks(diffLines: List<DiffLine>): List<DiffHunk> {
        val hunks = mutableListOf<DiffHunk>()
        var currentHunkLines = mutableListOf<DiffLine>()
        var contextCount = 0

        for (line in diffLines) {
            if (line.type != DiffLineType.UNCHANGED) {
                contextCount = 0
                currentHunkLines.add(line)
                continue
            }

            if (!currentHunkLines.any { it.type != DiffLineType.UNCHANGED }) {
                continue
            }

            currentHunkLines.add(line)
            contextCount++
            if (contextCount >= 3) {
                hunks.add(createHunk(currentHunkLines))
                currentHunkLines = mutableListOf()
                contextCount = 0
            }
        }

        if (currentHunkLines.any { it.type != DiffLineType.UNCHANGED }) {
            hunks.add(createHunk(currentHunkLines))
        }

        return hunks
    }

    private fun createHunk(lines: List<DiffLine>): DiffHunk {
        val firstChanged = lines.firstOrNull()
        val header = "@@ -${firstChanged?.oldLineNumber ?: 1} +${firstChanged?.newLineNumber ?: 1} @@"
        return DiffHunk(header, lines.toList())
    }
}
