package com.ghostdebugger.rules

import com.ghostdebugger.parser.withKtAnalysis
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

private val log = logger<RuleMatcherMarker>()
private object RuleMatcherMarker

object RuleMatcher {

    fun matches(element: PsiElement, match: RuleMatch): Boolean {
        try {
            if (!matchElementType(element, match.element)) return false
            if (!matchName(element, match.`name-matches`)) return false
            if (!matchText(element, match.`text-matches`, match.`contains-text`)) return false
            if (!matchParamType(element, match.`parameter-type`)) return false

            match.unless?.let { unlessMatch ->
                if (matches(element, unlessMatch)) return false
            }

            return true
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.debug("RuleMatcher exception", e)
            return false
        }
    }

    private fun matchElementType(element: PsiElement, elementTypeStr: String): Boolean {
        return when (elementTypeStr.lowercase()) {
            "catch-clause", "catchclause", "ktcatchclause" -> element is KtCatchClause
            "function", "ktnamedfunction" -> element is KtNamedFunction
            "parameter", "ktparameter" -> element is KtParameter
            "property", "ktproperty" -> element is KtProperty
            else -> element.javaClass.simpleName.lowercase().contains(elementTypeStr.lowercase())
        }
    }

    private fun matchName(element: PsiElement, regexStr: String?): Boolean {
        if (regexStr == null) return true
        val name = getElementName(element) ?: return false
        return Regex(regexStr).containsMatchIn(name)
    }

    private fun matchText(element: PsiElement, textRegexStr: String?, containsText: String?): Boolean {
        if (textRegexStr != null && !Regex(textRegexStr).containsMatchIn(element.text)) return false
        if (containsText != null && !element.text.contains(containsText)) return false
        return true
    }

    private fun matchParamType(element: PsiElement, expectedType: String?): Boolean {
        if (expectedType == null) return true
        val paramType = getParameterTypeString(element) ?: return false
        if (paramType == "KaErrorType" || paramType.contains("ERROR")) return false
        return paramType.contains(expectedType)
    }

    private fun getElementName(element: PsiElement): String? {
        return when (element) {
            is KtNamedFunction -> element.name
            is KtParameter -> element.name
            is KtProperty -> element.name
            else -> null
        }
    }

    private fun getParameterTypeString(element: PsiElement): String? {
        if (element is KtCatchClause) {
            val param = element.catchParameter ?: return null
            val ktFile = element.containingKtFile
            return withKtAnalysis(ktFile) {
                val symbol = param.symbol
                val type: KaType = symbol.returnType
                if (type is KaErrorType) null else type.toString()
            }
        }
        return null
    }
}
