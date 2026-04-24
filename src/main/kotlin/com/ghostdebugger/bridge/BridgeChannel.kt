package com.ghostdebugger.bridge

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.NodeStatus

/**
 * Minimal abstraction over the UI bridge for code paths that only need to push
 * per-node status and per-file issue updates. Lets the dependent-cascade tests
 * use a recording stub without instantiating a real JCEF browser.
 */
interface BridgeChannel {
    fun sendNodeUpdate(nodeId: String, status: NodeStatus)
    fun sendIssuesForFile(filePath: String, issues: List<Issue>)
}
