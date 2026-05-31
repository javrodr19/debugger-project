package com.ghostdebugger.fix.engine

import com.ghostdebugger.model.CodeFix

/** Offset of the start of each 0-based line in [content] (assumed `\n`-normalized, matching the Document). */
internal fun lineStartOffsets(content: String): IntArray {
    val starts = ArrayList<Int>()
    starts.add(0)
    content.forEachIndexed { i, c -> if (c == '\n') starts.add(i + 1) }
    return starts.toIntArray()
}

/**
 * Wraps a deterministic [CodeFix] (whole-line replacement) into a single-op [FixPlan]. Mirrors
 * FixApplicator's line math: replace [lineStart..lineEnd] content (excluding the trailing newline of
 * lineEnd) with fixedCode. Returns null if the line range is out of bounds for [content].
 */
fun CodeFix.toFixPlan(content: String): FixPlan? {
    val starts = lineStartOffsets(content)
    val startIdx = lineStart - 1
    val endIdx = lineEnd - 1
    if (startIdx < 0 || endIdx >= starts.size || startIdx > endIdx) return null
    val startOffset = starts[startIdx]
    val endOffset = if (endIdx + 1 < starts.size) starts[endIdx + 1] - 1 else content.length
    return FixPlan(issueId, listOf(ReplaceRange(startOffset, endOffset, fixedCode)))
}
