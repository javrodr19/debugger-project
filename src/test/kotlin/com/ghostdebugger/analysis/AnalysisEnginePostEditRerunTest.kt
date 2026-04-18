package com.ghostdebugger.analysis

import com.ghostdebugger.graph.InMemoryGraph
import com.ghostdebugger.model.*
import com.ghostdebugger.settings.AIProvider
import com.ghostdebugger.settings.GhostDebuggerSettings
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisEnginePostEditRerunTest : BasePlatformTestCase() {

    fun testReAnalysisClearsNullSafetyIssueOnUnsavedEdit() = runBlocking {
        val initialContent = """
            function App() {
              let x = null;
              return <div>{x.foo}</div>;
            }
        """.trimIndent()
        val psiFile = myFixture.configureByText("Test.tsx", initialContent)
        val document = myFixture.getDocument(psiFile)

        val settings = GhostDebuggerSettings.State().apply { aiProvider = AIProvider.NONE }
        val engine = AnalysisEngine(settingsProvider = { settings })

        val context1 = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(
                ParsedFile(psiFile.virtualFile, psiFile.virtualFile.path, "tsx", psiFile.text)
            )
        )
        val result1 = engine.analyze(context1)
        assertTrue(
            result1.issues.any { it.ruleId == "AEG-NULL-001" },
            "Should find NULL_SAFETY issue on initial run, got: ${result1.issues.map { it.ruleId }}"
        )

        val fixedContent = """
            function App() {
              let x = { foo: "bar" };
              return <div>{x.foo}</div>;
            }
        """.trimIndent()
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(fixedContent)
        }

        val scanner = com.ghostdebugger.parser.FileScanner(project)
        val parsedFiles2 = runReadAction {
            scanner.parsedFiles(listOf(psiFile.virtualFile))
        }

        val context2 = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = parsedFiles2
        )
        val result2 = engine.analyze(context2)

        assertFalse(
            result2.issues.any { it.ruleId == "AEG-NULL-001" },
            "AEG-NULL-001 should be cleared after unsaved edit, got: ${result2.issues.map { it.ruleId }}"
        )
    }

    fun testReAnalysisClearsStateIssueOnUnsavedEdit() = runBlocking {
        val initialContent = """
            import { useState } from 'react';
            function App() {
              const [items, setItems] = useState();
              return <div>{items.map(i => i)}</div>;
            }
        """.trimIndent()
        val psiFile = myFixture.configureByText("App.tsx", initialContent)
        val document = myFixture.getDocument(psiFile)

        val settings = GhostDebuggerSettings.State().apply { aiProvider = AIProvider.NONE }
        val engine = AnalysisEngine(settingsProvider = { settings })

        val context1 = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = listOf(
                ParsedFile(psiFile.virtualFile, psiFile.virtualFile.path, "tsx", psiFile.text)
            )
        )
        val result1 = engine.analyze(context1)
        assertTrue(
            result1.issues.any { it.ruleId == "AEG-STATE-001" },
            "Should find STATE_BEFORE_INIT issue on initial run, got: ${result1.issues.map { it.ruleId }}"
        )

        val fixedContent = """
            import { useState } from 'react';
            function App() {
              const [items, setItems] = useState([]);
              return <div>{items.map(i => i)}</div>;
            }
        """.trimIndent()
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(fixedContent)
        }

        val scanner = com.ghostdebugger.parser.FileScanner(project)
        val parsedFiles2 = runReadAction {
            scanner.parsedFiles(listOf(psiFile.virtualFile))
        }

        val context2 = AnalysisContext(
            graph = InMemoryGraph(),
            project = project,
            parsedFiles = parsedFiles2
        )
        val result2 = engine.analyze(context2)

        assertFalse(
            result2.issues.any { it.ruleId == "AEG-STATE-001" },
            "AEG-STATE-001 should be cleared after unsaved edit, got: ${result2.issues.map { it.ruleId }}"
        )
    }
}
