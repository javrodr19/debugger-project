package com.ghostdebugger.fix.engine

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCatchClause

object RuleAnchorResolver {

    fun resolveAnchorOffset(element: PsiElement, anchorName: String): Int? {
        return when (anchorName.lowercase()) {
            "element-start", "start" -> element.textOffset
            "element-end", "end" -> element.textOffset + element.textLength
            "catch-block-open-brace", "block-start" -> {
                if (element is KtCatchClause) {
                    val body = element.catchBody as? KtBlockExpression
                    body?.lBrace?.textOffset?.let { it + 1 } ?: element.textOffset
                } else {
                    element.textOffset
                }
            }
            else -> element.textOffset
        }
    }
}
