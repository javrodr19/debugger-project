package com.ghostdebugger.actions

import com.ghostdebugger.AegisCapability
import com.ghostdebugger.AegisCapabilityGate
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GatedActionsTest : BasePlatformTestCase() {

    override fun tearDown() {
        AegisCapabilityGate.resetForTest()
        super.tearDown()
    }

    private fun capabilityShownBy(actionId: String, event: AnActionEvent): AegisCapability? {
        var seen: AegisCapability? = null
        AegisCapabilityGate.setPresenterForTest { _, capability -> seen = capability }
        ActionManager.getInstance().getAction(actionId).actionPerformed(event)
        return seen
    }

    fun `test Apply All Fixes reports the fix capability`() {
        val file = myFixture.configureByText("Sample.ts", "let a = null\na.b\n").virtualFile
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .build()
        val shown = capabilityShownBy(
            "GhostDebugger.ApplyAllFixes",
            TestActionEvent.createTestEvent(context)
        )
        assertEquals(AegisCapability.FIX_APPLICATION, shown)
    }

    fun `test Explain System reports the explanation capability`() {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .build()
        val shown = capabilityShownBy(
            "GhostDebugger.ExplainSystem",
            TestActionEvent.createTestEvent(context)
        )
        assertEquals(AegisCapability.AI_EXPLANATION, shown)
    }
}
