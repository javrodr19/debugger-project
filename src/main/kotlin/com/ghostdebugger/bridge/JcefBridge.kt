package com.ghostdebugger.bridge

import com.ghostdebugger.model.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

class JcefBridge(
    private val browser: JBCefBrowser,
    private val onEvent: (UIEvent) -> Unit
) {
    private val log = logger<JcefBridge>()
    private val json = Json { ignoreUnknownKeys = true }
    private var query: JBCefJSQuery? = null

    fun initialize() {
        query = JBCefJSQuery.create(browser as JBCefBrowserBase)
        query!!.addHandler { message ->
            try {
                val event = UIEventParser.parse(message)
                log.info("JcefBridge received event: ${event::class.simpleName}")
                onEvent(event)
            } catch (e: Exception) {
                log.error("Failed to parse UI event: $message", e)
            }
            JBCefJSQuery.Response("ok")
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectBridgeScript()
                }
            }
        }, browser.cefBrowser)
    }

    private fun injectBridgeScript() {
        val q = query ?: return
        val injectScript = """
            (function() {
                window.__jcefQuery__ = function(msg) {
                    ${q.inject("msg")}
                };
                if (window.__ghostdebugger__ && window.__ghostdebugger__.__ready__) {
                    window.__ghostdebugger__.__ready__();
                }
                console.log('[GhostDebugger] Bridge initialized');
            })();
        """.trimIndent()

        browser.cefBrowser.executeJavaScript(injectScript, browser.cefBrowser.url ?: "about:blank", 0)
    }

    fun sendGraphData(graph: ProjectGraph) {
        val graphJson = json.encodeToString(graph)
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onGraphUpdate($graphJson)")
    }

    fun sendIssueExplanation(issueId: String, explanation: String) {
        val explanationEscaped = json.encodeToString(mapOf("issueId" to issueId, "explanation" to explanation))
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onExplanation($explanationEscaped)")
    }

    fun sendFixSuggestion(fix: CodeFix) {
        val fixJson = json.encodeToString(fix)
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onFixSuggestion($fixJson)")
    }

    fun sendNodeUpdate(nodeId: String, status: NodeStatus) {
        val escapedId = nodeId.replace("\"", "\\\"")
        val payload = """{"nodeId":"$escapedId","status":"${status.name}"}"""
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onNodeUpdate($payload)")
    }

    fun sendAnalysisStart() {
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onAnalysisStart()")
    }

    fun sendAnalysisComplete(errorCount: Int, warningCount: Int, healthScore: Double) {
        val payload = """{"errorCount":$errorCount,"warningCount":$warningCount,"healthScore":$healthScore}"""
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onAnalysisComplete($payload)")
    }

    fun sendError(message: String) {
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onError(\"$escaped\")")
    }

    fun sendSystemExplanation(explanation: String) {
        val escaped = explanation.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onSystemExplanation(\"$escaped\")")
    }

    fun sendImpactAnalysis(nodeId: String, affectedNodes: List<String>) {
        val affectedJson = json.encodeToString(affectedNodes)
        val escapedId = nodeId.replace("\"", "\\\"")
        val payload = """{"nodeId":"$escapedId","affectedNodes":$affectedJson}"""
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onImpactAnalysis($payload)")
    }

    private fun executeJS(script: String) {
        try {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url ?: "about:blank", 0)
        } catch (e: Exception) {
            log.warn("Failed to execute JavaScript", e)
        }
    }
}
