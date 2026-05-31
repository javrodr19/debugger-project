package com.ghostdebugger.fix.engine

/** A single text replacement: replace [startOffset, endOffset) with [replacement]. */
data class TextEdit(val startOffset: Int, val endOffset: Int, val replacement: String)

/**
 * Applies all edits to [content]. Edits are applied in descending start-offset order so that
 * earlier offsets remain valid as later text is replaced. Assumes edits do not overlap.
 */
fun List<TextEdit>.applyTo(content: String): String {
    val sb = StringBuilder(content)
    for (edit in sortedByDescending { it.startOffset }) {
        sb.replace(edit.startOffset, edit.endOffset, edit.replacement)
    }
    return sb.toString()
}
