package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import java.util.UUID

/**
 * Rewrites `x?.let { it.foo() }` (single-statement, smart-cast-confirmed non-null)
 * to `x.foo()`. Operates on the body verbatim, replacing whole-word `it` with the
 * receiver name.
 */
class KotlinRedundantLetFixer : Fixer {

    private val log = logger<KotlinRedundantLetFixer>()

    override val ruleId = "AEG-REDUNDANT-LET-KT-001"
    override val description = "Removes redundant `?.let { it.* }` and inlines the call on the receiver."

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? = null

    override fun generateFixFromPsi(issue: Issue, file: PsiFile): CodeFix? {
        val ktFile = file as? KtFile ?: return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(ktFile) ?: return null

        val safe = PsiTreeUtil.findChildrenOfType(ktFile, KtSafeQualifiedExpression::class.java)
            .firstOrNull { document.getLineNumber(it.textOffset) + 1 == issue.line }
            ?: return null

        val call = safe.selectorExpression as? KtCallExpression ?: return null
        if ((call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != "let") return null

        val lambda = call.lambdaArguments.singleOrNull()?.getLambdaExpression() ?: return null
        val statement = singleStatement(lambda) ?: return null

        val receiverText = safe.receiverExpression.text
        val unwrapped = statement.text.replace(Regex("\\bit\\b"), receiverText)

        val lineStart = document.getLineStartOffset(issue.line - 1)
        val lineEnd = document.getLineEndOffset(issue.line - 1)
        val originalLine = document.getText(TextRange(lineStart, lineEnd))
        val fixedLine = originalLine.replace(safe.text, unwrapped)
        if (fixedLine == originalLine) {
            log.warn("KotlinRedundantLetFixer: no replacement produced for issue ${issue.id}")
            return null
        }

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = "Remove redundant `?.let` and inline call on the receiver",
            originalCode = originalLine,
            fixedCode = fixedLine,
            filePath = issue.filePath,
            lineStart = issue.line,
            lineEnd = issue.line,
            isDeterministic = true,
            confidence = 0.95
        )
    }

    private fun singleStatement(lambda: KtLambdaExpression): PsiElement? {
        val body = lambda.bodyExpression as? KtBlockExpression ?: return lambda.bodyExpression
        return body.statements.singleOrNull()
    }
}
