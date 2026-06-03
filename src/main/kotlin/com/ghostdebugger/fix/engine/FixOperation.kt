package com.ghostdebugger.fix.engine

import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * A deterministic, PSI-valid-by-construction semantic transformation. Each operation converts to a
 * [TextEdit] against the file content, or returns null if it does not apply (offset out of range,
 * pattern absent) — never an invalid edit. Serializable so a later AI planner can emit a plan as
 * JSON. Phase 1 ships only [ReplaceRange]; the catalog grows later.
 */
@Serializable
sealed class FixOperation {
    abstract fun toEdit(ctx: FixContext): TextEdit?
}

/** Replace the half-open range [startOffset, endOffset) with [text]. */
@Serializable
@SerialName("replaceRange")
data class ReplaceRange(val startOffset: Int, val endOffset: Int, val text: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val content = ctx.content
        if (startOffset < 0 || endOffset > content.length || startOffset > endOffset) return null
        return TextEdit(startOffset, endOffset, text)
    }
}

/** Insert `import [fqName]` if not already present (after the last import, else after package, else top). */
@Serializable
@SerialName("insertImport")
data class InsertImport(val fqName: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val content = ctx.content
        val lines = content.lines()
        val importLine = "import $fqName"
        if (lines.any { it.trim() == importLine }) return null

        val starts = lineStartOffsets(content)
        val lastImport = lines.indexOfLast { it.trim().startsWith("import ") }
        val pkg = lines.indexOfFirst { it.trim().startsWith("package ") }
        val anchorLine = when {
            lastImport >= 0 -> lastImport
            pkg >= 0 -> pkg
            else -> -1
        }
        val insertOffset = if (anchorLine < 0) 0
            else if (anchorLine + 1 < starts.size) starts[anchorLine + 1] - 1 else content.length
        val text = if (anchorLine < 0) "$importLine\n" else "\n$importLine"
        return TextEdit(insertOffset, insertOffset, text)
    }
}

/** Rewrite the unsafe `as` cast whose `as` keyword starts at [asOffset] into `x as? T <fallback>`. */
@Serializable
@SerialName("convertToSafeCast")
data class ConvertToSafeCast(val asOffset: Int) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val ktFile = ctx.psiFile as? KtFile ?: return null
        val cast = PsiTreeUtil.findChildrenOfType(ktFile, KtBinaryExpressionWithTypeRHS::class.java)
            .firstOrNull {
                it.operationReference.getReferencedNameElementType() == KtTokens.AS_KEYWORD &&
                it.operationReference.textRange.startOffset == asOffset
            } ?: return null
        val targetText = cast.right?.text ?: return null
        val receiverText = cast.left.text
        val function = PsiTreeUtil.getParentOfType(cast, KtNamedFunction::class.java)
        val fallback = when (val ret = function?.typeReference?.text?.trim()) {
            null -> "?: throw IllegalStateException(\"Cast failed\")"
            "Unit" -> "?: return"
            else -> if (ret.endsWith("?")) "?: return null" else "?: throw IllegalStateException(\"Cast failed\")"
        }
        return TextEdit(cast.textRange.startOffset, cast.textRange.endOffset, "$receiverText as? $targetText $fallback")
    }
}

/** `expr` → `expr ?: default` (Kotlin) / `expr ?? default` (JS/TS), at the first occurrence of [expr] on [line]. */
@Serializable
@SerialName("addElvisDefault")
data class AddElvisDefault(val line: Int, val expr: String, val default: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val at = LineLocator.indexOfOn(ctx.content, line, expr) ?: return null
        val op = if (ctx.psiFile is KtFile) "?:" else "??"
        val end = at + expr.length
        return TextEdit(at, end, "$expr $op $default")
    }
}

/** `receiver.member` → `receiver?.member` on [line]. KT rewrites the matching dot-qualified expression; TS inserts `?` before the dot. */
@Serializable
@SerialName("wrapInSafeCall")
data class WrapInSafeCall(val line: Int, val receiver: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val ktFile = ctx.psiFile as? KtFile
        if (ktFile != null) {
            val access = PsiTreeUtil.findChildrenOfType(ktFile, KtDotQualifiedExpression::class.java).firstOrNull {
                LineLocator.lineAt(ctx.content, it.textRange.startOffset) == line &&
                    it.receiverExpression.text == receiver &&
                    it.selectorExpression != null
            } ?: return null
            val selector = access.selectorExpression!!.text
            return TextEdit(access.textRange.startOffset, access.textRange.endOffset, "$receiver?.$selector")
        }
        // Content path (TS/JS): insert `?` before the receiver's dot on the line.
        val at = LineLocator.indexOfOn(ctx.content, line, "$receiver.") ?: return null
        val dot = at + receiver.length
        return TextEdit(dot, dot + 1, "?.")
    }
}

/** Wrap the single-line statement on [line] in `if ([variable] != null) { … }`, preserving leading indentation. */
@Serializable
@SerialName("surroundWithNullCheck")
data class SurroundWithNullCheck(val line: Int, val variable: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val range = LineLocator.lineRange(ctx.content, line) ?: return null
        if (range.isEmpty()) return null
        val lineText = ctx.content.substring(range.first, range.last + 1)
        if (lineText.isBlank()) return null
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        val body = lineText.trim()
        val wrapped = "${indent}if ($variable != null) {\n$indent    $body\n$indent}"
        return TextEdit(range.first, range.last + 1, wrapped)
    }
}

/** Prefix the first occurrence of [call] on [line] with `await ` (JS/TS only). Declines on Kotlin or if already awaited. */
@Serializable
@SerialName("addAwait")
data class AddAwait(val line: Int, val call: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        if (ctx.psiFile is KtFile) return null
        val at = LineLocator.indexOfOn(ctx.content, line, call) ?: return null
        val before = ctx.content.substring((at - 6).coerceAtLeast(0), at)
        if (before.endsWith("await ")) return null
        return TextEdit(at, at, "await ")
    }
}

/** Insert `.catch([handler])` before the trailing `;` of a `...);` chain on [line] (JS/TS only). */
@Serializable
@SerialName("addPromiseCatch")
data class AddPromiseCatch(val line: Int, val handler: String = "console.error") : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        if (ctx.psiFile is KtFile) return null
        val range = LineLocator.lineRange(ctx.content, line) ?: return null
        if (range.isEmpty()) return null
        val lineText = ctx.content.substring(range.first, range.last + 1)
        val trimmed = lineText.trimEnd()
        if (!trimmed.endsWith(");")) return null
        if (trimmed.contains(".catch(")) return null
        val semicolonAbs = range.first + (trimmed.length - 1)  // the ';'
        return TextEdit(semicolonAbs, semicolonAbs, ".catch($handler)")
    }
}

/**
 * Convert the first occurrence of [expr] on [line] using [conversion]: suffix form when [conversion]
 * starts with `.` (`expr` → `expr.toLong()`), else wrapper form (`expr` → `String(expr)`).
 * Language-agnostic (content-based). Returns null if [expr] is absent on [line].
 */
@Serializable
@SerialName("addExplicitConversion")
data class AddExplicitConversion(val line: Int, val expr: String, val conversion: String) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val at = LineLocator.indexOfOn(ctx.content, line, expr) ?: return null
        val end = at + expr.length
        val wrapped = if (conversion.startsWith(".")) "$expr$conversion" else "$conversion($expr)"
        return TextEdit(at, end, wrapped)
    }
}

/** Wrap the lines [startLine]..[endLine] in `try { … } catch (…) { … }`, preserving indentation. KT uses a typed catch. */
@Serializable
@SerialName("surroundWithTryCatch")
data class SurroundWithTryCatch(
    val startLine: Int,
    val endLine: Int,
    val catchBody: String? = null,
) : FixOperation() {
    override fun toEdit(ctx: FixContext): TextEdit? {
        val start = LineLocator.lineRange(ctx.content, startLine) ?: return null
        val end = LineLocator.lineRange(ctx.content, endLine) ?: return null
        if (start.first > end.last + 1) return null
        val block = ctx.content.substring(start.first, end.last + 1)
        if (block.isBlank()) return null
        val indent = block.takeWhile { it == ' ' || it == '\t' }
        val isKt = ctx.psiFile is KtFile
        val catchClause = if (isKt) "catch (e: Exception)" else "catch (e)"
        val body = catchBody ?: if (isKt) "e.printStackTrace()" else "console.error(e)"
        val inner = block.lines().joinToString("\n") { if (it.isBlank()) it else "    $it" }
        val wrapped = "${indent}try {\n$inner\n$indent} $catchClause {\n$indent    $body\n$indent}"
        return TextEdit(start.first, end.last + 1, wrapped)
    }
}
