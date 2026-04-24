package com.ghostdebugger

import com.ghostdebugger.bridge.BridgeChannel
import com.ghostdebugger.model.*
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.ConcurrentHashMap

class GhostDebuggerServiceReanalyzeDependentsTest : BasePlatformTestCase() {

    private class RecordingBridge : BridgeChannel {
        val nodeUpdates = ConcurrentHashMap<String, NodeStatus>()
        val issueUpdates = ConcurrentHashMap<String, List<Issue>>()
        override fun sendNodeUpdate(nodeId: String, status: NodeStatus) {
            nodeUpdates[nodeId] = status
        }
        override fun sendIssuesForFile(filePath: String, issues: List<Issue>) {
            issueUpdates[filePath] = issues
        }
    }

    fun testCascadeFiresForImmediateDependents() {
        val svc = GhostDebuggerService.getInstance(project)
        val bridge = RecordingBridge()
        svc.setBridgeForTest(bridge)
        svc.installTestGraph(graphWithDependencies("/B.kt", listOf("/A.kt", "/C.kt")))

        svc.cascadeDependentsForTest(changedFilePath = "/B.kt", cap = 20)

        assertTrue(
            "Expected node update for A.kt; got ${bridge.nodeUpdates.keys}",
            bridge.nodeUpdates.containsKey("A.kt") || bridge.nodeUpdates.containsKey("/A.kt")
        )
        assertTrue(
            "Expected node update for C.kt; got ${bridge.nodeUpdates.keys}",
            bridge.nodeUpdates.containsKey("C.kt") || bridge.nodeUpdates.containsKey("/C.kt")
        )
    }

    fun testCascadeIsDisabledWhenCapIsZero() {
        val svc = GhostDebuggerService.getInstance(project)
        val bridge = RecordingBridge()
        svc.setBridgeForTest(bridge)
        svc.installTestGraph(graphWithDependencies("/B.kt", listOf("/A.kt", "/C.kt")))

        svc.cascadeDependentsForTest(changedFilePath = "/B.kt", cap = 0)

        assertTrue(
            "No dependent updates should fire when cap is 0",
            bridge.nodeUpdates.isEmpty()
        )
    }

    fun testCascadeRespectsCapLimit() {
        val svc = GhostDebuggerService.getInstance(project)
        val bridge = RecordingBridge()
        svc.setBridgeForTest(bridge)
        svc.installTestGraph(
            graphWithDependencies("/B.kt", listOf("/A.kt", "/C.kt", "/D.kt", "/E.kt", "/F.kt"))
        )

        svc.cascadeDependentsForTest(changedFilePath = "/B.kt", cap = 2)

        assertEquals("Cap=2 should update exactly 2 dependents", 2, bridge.nodeUpdates.size)
    }

    fun testCascadeNoopWhenNoDependents() {
        val svc = GhostDebuggerService.getInstance(project)
        val bridge = RecordingBridge()
        svc.setBridgeForTest(bridge)
        svc.installTestGraph(graphWithDependencies("/B.kt", emptyList()))

        svc.cascadeDependentsForTest(changedFilePath = "/B.kt", cap = 20)

        assertTrue(bridge.nodeUpdates.isEmpty())
    }

    private fun graphWithDependencies(
        target: String,
        dependents: List<String>
    ): com.ghostdebugger.graph.InMemoryGraph {
        val graph = com.ghostdebugger.graph.InMemoryGraph()
        val normalize = { p: String -> p.trimStart('/') }
        graph.addNode(
            GraphNode(
                id = normalize(target),
                name = target.substringAfterLast('/'),
                filePath = target,
                type = NodeType.FILE
            )
        )
        dependents.forEach { dep ->
            graph.addNode(
                GraphNode(
                    id = normalize(dep),
                    name = dep.substringAfterLast('/'),
                    filePath = dep,
                    type = NodeType.FILE
                )
            )
            graph.addEdge(
                GraphEdge(
                    id = "$dep->$target",
                    source = normalize(dep),
                    target = normalize(target),
                    type = EdgeType.IMPORT
                )
            )
        }
        return graph
    }
}
