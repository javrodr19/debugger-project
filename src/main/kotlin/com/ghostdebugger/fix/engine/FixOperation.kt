package com.ghostdebugger.fix.engine

import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
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
