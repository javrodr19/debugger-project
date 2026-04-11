package com.ghostdebugger.bridge

import com.ghostdebugger.model.*
import com.ghostdebugger.model.DebugVariable
import com.intellij.openapi.Disposable
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
) : Disposable {
    private val log = logger<JcefBridge>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var query: JBCefJSQuery? = null

    fun initialize() {
        val q = JBCefJSQuery.create(browser as JBCefBrowserBase)
        query = q
        q.addHandler { message ->
            handleIncomingQuery(message)
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectBridgeScript()
                }
            }
        }, browser.cefBrowser)
    }

    private fun handleIncomingQuery(message: String): JBCefJSQuery.Response {
        try {
            val event = UIEventParser.parse(message)
            log.info("JcefBridge received event: ${event::class.simpleName}")
            onEvent(event)
        } catch (e: Exception) {
            log.error("Failed to parse UI event: $message", e)
        }
        return JBCefJSQuery.Response("ok")
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
            })();
        """.trimIndent()

        browser.cefBrowser.executeJavaScript(injectScript, browser.cefBrowser.url ?: "about:blank", 0)
    }

    fun sendGraphData(graph: ProjectGraph) {
        val graphJson = json.encodeToString(graph)
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onGraphUpdate($graphJson)")
    }

    fun sendIssueExplanation(issueId: String, explanation: String) {
        val payload = json.encodeToString(IssueExplanationPayload(issueId, explanation))
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onExplanation($payload)")
    }

    fun sendFixSuggestion(fix: CodeFix) {
        val fixJson = json.encodeToString(fix)
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onFixSuggestion($fixJson)")
    }

    fun sendNodeUpdate(nodeId: String, status: NodeStatus) {
        val payload = json.encodeToString(NodeUpdatePayload(nodeId, status.name))
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onNodeUpdate($payload)")
    }

    fun sendAnalysisStart() {
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onAnalysisStart()")
    }

    fun sendAnalysisComplete(errorCount: Int, warningCount: Int, healthScore: Double) {
        val payload = json.encodeToString(AnalysisCompletePayload(
            errorCount = errorCount,
            warningCount = warningCount,
            healthScore = healthScore
        ))
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onAnalysisComplete($payload)")
    }

    fun sendError(message: String) {
        val payload = json.encodeToString(message)
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onError($payload)")
    }

    fun sendSystemExplanation(explanation: String) {
        val payload = json.encodeToString(explanation)
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onSystemExplanation($payload)")
    }

    fun sendImpactAnalysis(nodeId: String, affectedNodes: List<String>) {
        val payload = json.encodeToString(ImpactAnalysisPayload(nodeId, affectedNodes))
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onImpactAnalysis($payload)")
    }

    fun sendDebugFrame(nodeId: String, filePath: String, line: Int, variables: List<DebugVariable>) {
        val payload = json.encodeToString(DebugFramePayload(
            nodeId = nodeId,
            filePath = filePath,
            line = line,
            variables = variables
        ))
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onDebugFrame($payload)")
    }

    fun sendDebugSessionEnded() {
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onDebugSessionEnded()")
    }

    fun sendDebugStateChanged(state: String) {
        val payload = json.encodeToString(state)
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onDebugStateChanged($payload)")
    }

    fun sendAutoRefreshStart() {
        executeJS("window.__ghostdebugger__ && window.__ghostdebugger__.onAutoRefreshStart()")
    }

    private fun executeJS(script: String) {
        try {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url ?: "about:blank", 0)
        } catch (e: Exception) {
            log.warn("Failed to execute JavaScript", e)
        }
    }

    override fun dispose() {
        query?.dispose()
        query = null
    }
}
