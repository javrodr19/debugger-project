package com.ghostdebugger.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginActionsTest : BasePlatformTestCase() {

    fun testActionManagerRegistersBatch1Actions() {
        val am = ActionManager.getInstance()
        assertNotNull(am.getAction("GhostDebugger.ReanalyzeFile"))
        assertNotNull(am.getAction("GhostDebugger.ApplyAllFixes"))
        assertNotNull(am.getAction("GhostDebugger.NextFinding"))
        assertNotNull(am.getAction("GhostDebugger.PrevFinding"))
        assertNotNull(am.getAction("GhostDebugger.SuppressFinding"))
        assertNotNull(am.getAction("GhostDebugger.ConfigureApiKey"))
    }
}
