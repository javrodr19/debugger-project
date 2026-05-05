package com.ghostdebugger.fix

import com.ghostdebugger.model.CodeFix
import com.ghostdebugger.model.Issue
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression
import java.util.UUID

/**
 * Rewrites `x as Foo` to `x as? Foo ?: <fallback>` where:
 *   - fallback is `return null` when the enclosing function returns a nullable type
 *   - fallback is `throw IllegalStateException("Cast failed: …")` when the enclosing
 *     function returns Unit or a non-nullable type
 *
 * Scope is restricted to casts that appear inside a `return` statement in a named
 * function. Variable initialisers and other non-return positions are declined
 * (return null) — those rewrites are not safely derivable without changing program
 * semantics.
 */
class KotlinUnsafeCastFixer : Fixer {

    private val log = logger<KotlinUnsafeCastFixer>()

    override val ruleId = "AEG-CAST-KT-001"
    override val description =
        "Replaces `x as Foo` with `x as? Foo ?: return null` (or `?: throw IllegalStateException(...)`) inside `return` expressions."

    override fun generateFix(issue: Issue, fileContent: String): CodeFix? = null

    override fun generateFixFromPsi(issue: Issue, file: PsiFile): CodeFix? {
        val ktFile = file as? KtFile ?: return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(ktFile) ?: return null

        val cast = PsiTreeUtil.findChildrenOfType(ktFile, KtBinaryExpressionWithTypeRHS::class.java)
            .firstOrNull {
                document.getLineNumber(it.textOffset) + 1 == issue.line &&
                it.operationReference.getReferencedNameElementType() == KtTokens.AS_KEYWORD
            } ?: return null

        val returnExpr = PsiTreeUtil.getParentOfType(cast, KtReturnExpression::class.java) ?: return null
        val function = PsiTreeUtil.getParentOfType(returnExpr, KtNamedFunction::class.java) ?: return null

        val targetText = cast.right?.text ?: return null
        val receiverText = cast.left.text

        val fallback = buildFallback(function)
        val rewritten = "$receiverText as? $targetText $fallback"

        val lineStart = document.getLineStartOffset(issue.line - 1)
        val lineEnd = document.getLineEndOffset(issue.line - 1)
        val originalLine = document.getText(TextRange(lineStart, lineEnd))
        val fixedLine = originalLine.replace(cast.text, rewritten)
        if (fixedLine == originalLine) {
            log.warn("KotlinUnsafeCastFixer: no replacement produced for issue ${issue.id}")
            return null
        }

        return CodeFix(
            id = UUID.randomUUID().toString(),
            issueId = issue.id,
            description = "Replace unsafe cast with safe cast and Elvis fallback",
            originalCode = originalLine,
            fixedCode = fixedLine,
            filePath = issue.filePath,
            lineStart = issue.line,
            lineEnd = issue.line,
            isDeterministic = true,
            confidence = 0.95
        )
    }

    private fun buildFallback(function: KtNamedFunction): String {
        val returnRef = function.typeReference?.text?.trim()
        return when {
            returnRef == null -> "?: throw IllegalStateException(\"Cast failed in ${function.name}\")"
            returnRef.endsWith("?") -> "?: return null"
            returnRef == "Unit" -> "?: return"
            else -> "?: throw IllegalStateException(\"Cast failed in ${function.name}\")"
        }
    }
}
