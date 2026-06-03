package com.ghostdebugger.fix

import com.ghostdebugger.fix.engine.FixContext
import com.ghostdebugger.fix.engine.FixPlan
import com.ghostdebugger.fix.engine.LineLocator
import com.ghostdebugger.fix.engine.WrapInSafeCall
import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile

/**
 * Deterministic fixer for Kotlin nullable access (`AEG-NULL-KT-001`): rewrites the flagged
 * `receiver.member` to a safe call `receiver?.member` via [WrapInSafeCall]. Declines (null) when it
 * cannot pin exactly one dot-qualified access on the issue line — the AI planner then takes over.
 */
class KotlinNullSafetyFixer : Fixer {
    override val ruleId = "AEG-NULL-KT-001"
    override val description = "Rewrites a flagged nullable `receiver.member` access to a safe call `receiver?.member`."

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? = null

    override fun generatePlan(issue: Issue, ctx: FixContext): FixPlan? {
        val ktFile = ctx.psiFile as? KtFile ?: return null
        val onLine = PsiTreeUtil.findChildrenOfType(ktFile, KtDotQualifiedExpression::class.java).filter {
            LineLocator.lineAt(ctx.content, it.textRange.startOffset) == issue.line && it.selectorExpression != null
        }
        // Pin a single access. Prefer one whose receiver name appears in the issue title; else require exactly one.
        val access = when {
            onLine.size == 1 -> onLine[0]
            else -> onLine.firstOrNull { issue.title.contains("'${it.receiverExpression.text}'") }
        } ?: return null
        return FixPlan(issue.id, listOf(WrapInSafeCall(issue.line, access.receiverExpression.text)))
    }
}
