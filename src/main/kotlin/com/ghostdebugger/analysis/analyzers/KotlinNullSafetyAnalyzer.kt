package com.ghostdebugger.analysis.analyzers

import com.ghostdebugger.model.*
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import java.util.UUID

/**
 * PSI-backed null-safety analyzer for Kotlin. Single-file scope.
 *
 * Task 5 migrates the file-resolution path to project-bound PSI; the V1.2 name-matching
 * logic is preserved verbatim. Task 8 replaces the logic with Analysis API resolution.
 *
 * Name-based matching only (no Kotlin BindingContext) — the cost of full resolution
 * isn't justified for single-file scope.
 */
class KotlinNullSafetyAnalyzer : KotlinAnalyzer() {

    override val name = "KotlinNullSafetyAnalyzer"
    override val ruleId = "AEG-NULL-KT-001"
    override val defaultSeverity = IssueSeverity.ERROR
    override val description =
        "Flags access on Kotlin nullable properties without a safe call, null guard, !!, or Elvis fallback."

    override fun analyzeKtFile(
        ktFile: KtFile,
        parsedFile: ParsedFile,
        context: AnalysisContext,
        session: KaSession
    ): List<Issue> {
        val document = PsiDocumentManager.getInstance(ktFile.project).getDocument(ktFile)
        fun lineOf(offset: Int): Int = document?.getLineNumber(offset)?.plus(1) ?: 1

        val findings = mutableListOf<Issue>()
        for (scope in collectScopes(ktFile)) {
            val nullables = collectNullableProperties(scope)
            if (nullables.isEmpty()) continue
            val scopeBody = bodyOf(scope) ?: continue

            for (access in PsiTreeUtil.findChildrenOfType(scope, KtDotQualifiedExpression::class.java)) {
                if (!isDirectlyInScope(access, scope)) continue

                val receiver = access.receiverExpression as? KtNameReferenceExpression ?: continue
                val refName = receiver.getReferencedName()
                val decl = nullables[refName] ?: continue
                if (decl.textOffset >= access.textOffset) continue
                if (isGuarded(access, scopeBody, refName)) continue
                if (isReassignedBefore(scopeBody, decl, access, refName)) continue

                val line = lineOf(access.textOffset)
                findings.add(
                    Issue(
                        id = UUID.randomUUID().toString(),
                        type = IssueType.NULL_SAFETY,
                        severity = IssueSeverity.ERROR,
                        title = "Nullable '$refName' accessed without a null check",
                        description = "Property '$refName' is declared nullable (or initialized as null) and is accessed here without ?., !!, a prior null guard, or an Elvis fallback.",
                        filePath = parsedFile.path,
                        line = line,
                        codeSnippet = extractSnippet(parsedFile.content, line),
                        affectedNodes = listOf(parsedFile.path),
                        ruleId = ruleId,
                        sources = listOf(IssueSource.STATIC),
                        providers = listOf(EngineProvider.STATIC),
                        confidence = 0.9
                    )
                )
            }
        }
        return findings
    }

    // ── Scope + declaration helpers (unchanged from V1.2) ──────────────────

    private fun collectScopes(ktFile: KtFile): List<PsiElement> {
        val scopes = mutableListOf<PsiElement>()
        scopes.addAll(PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction::class.java))
        scopes.addAll(PsiTreeUtil.findChildrenOfType(ktFile, KtLambdaExpression::class.java))
        scopes.addAll(PsiTreeUtil.findChildrenOfType(ktFile, KtClassInitializer::class.java))
        return scopes
    }

    private fun bodyOf(scope: PsiElement): PsiElement? = when (scope) {
        is KtNamedFunction -> scope.bodyBlockExpression ?: scope.bodyExpression
        is KtLambdaExpression -> scope.bodyExpression
        is KtClassInitializer -> scope.body
        else -> null
    }

    /** Returns name → declaring [KtProperty] for properties declared directly in the scope's block. */
    private fun collectNullableProperties(scope: PsiElement): Map<String, KtProperty> {
        val body = bodyOf(scope) ?: return emptyMap()
        val out = mutableMapOf<String, KtProperty>()
        for (prop in PsiTreeUtil.findChildrenOfType(body, KtProperty::class.java)) {
            if (prop.parent !is KtBlockExpression) continue
            val name = prop.name ?: continue
            val typeRef = prop.typeReference?.text?.trim()
            val initText = prop.initializer?.text?.trim()
            val isNullable = typeRef?.endsWith("?") == true || initText == "null"
            if (isNullable) out[name] = prop
        }
        return out
    }

    /** True when [element] sits inside [scope] without crossing any nested function / lambda / initializer. */
    private fun isDirectlyInScope(element: PsiElement, scope: PsiElement): Boolean {
        var cursor: PsiElement? = element.parent
        while (cursor != null && cursor !== scope) {
            if (cursor is KtNamedFunction || cursor is KtLambdaExpression || cursor is KtClassInitializer) {
                return false
            }
            cursor = cursor.parent
        }
        return cursor === scope
    }

    private fun isGuarded(
        access: KtDotQualifiedExpression,
        scopeBody: PsiElement,
        refName: String
    ): Boolean {
        var cursor: PsiElement? = access.parent
        while (cursor != null && cursor !== scopeBody.parent) {
            if (cursor is KtSafeQualifiedExpression &&
                (cursor.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() == refName
            ) return true
            if (cursor is KtIfExpression && nullCheckGuards(cursor, refName)) return true
            if (cursor is KtLambdaExpression) {
                val call = PsiTreeUtil.getParentOfType(cursor, KtCallExpression::class.java)
                val parent = call?.parent
                if (parent is KtSafeQualifiedExpression &&
                    (parent.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() == refName &&
                    (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == "let"
                ) return true
            }
            cursor = cursor.parent
        }
        if (access.receiverExpression.text == "$refName!!") return true
        for (bin in PsiTreeUtil.findChildrenOfType(scopeBody, KtBinaryExpression::class.java)) {
            if (bin.textOffset >= access.textOffset) continue
            if (bin.operationToken.toString() != "ELVIS") continue
            val left = (bin.left as? KtNameReferenceExpression)?.getReferencedName()
            val rightText = bin.right?.text?.trim() ?: ""
            if (left == refName && (rightText.startsWith("return") || rightText.startsWith("throw"))) return true
        }
        return false
    }

    private fun nullCheckGuards(ifExpr: KtIfExpression, refName: String): Boolean {
        val cond = ifExpr.condition?.text?.replace(" ", "") ?: return false
        return cond == "$refName!=null" || cond == "null!=$refName"
    }

    private fun isReassignedBefore(
        scopeBody: PsiElement,
        decl: KtProperty,
        access: KtDotQualifiedExpression,
        refName: String
    ): Boolean {
        for (bin in PsiTreeUtil.findChildrenOfType(scopeBody, KtBinaryExpression::class.java)) {
            if (bin.textOffset <= decl.textOffset) continue
            if (bin.textOffset >= access.textOffset) continue
            if (bin.operationToken.toString() != "EQ") continue
            val left = (bin.left as? KtNameReferenceExpression)?.getReferencedName()
            if (left == refName && bin.right?.text?.trim() != "null") return true
        }
        return false
    }

    private fun extractSnippet(content: String, line: Int): String {
        val lines = content.lines()
        val start = maxOf(0, line - 3)
        val end = minOf(lines.size, line + 2)
        return lines.subList(start, end).joinToString("\n")
    }
}
