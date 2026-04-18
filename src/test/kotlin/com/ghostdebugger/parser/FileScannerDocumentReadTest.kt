package com.ghostdebugger.parser

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals

class FileScannerDocumentReadTest : BasePlatformTestCase() {

    fun testParsedFilesPrefersLiveDocumentText() {
        val initialText = "val x = 1"
        val psiFile = myFixture.configureByText("Test.kt", initialText)
        val virtualFile = psiFile.virtualFile
        val document = myFixture.getDocument(psiFile)

        val updatedText = "val x = 2"
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(updatedText)
        }

        val scanner = FileScanner(project)
        val parsedFiles = runReadAction {
            scanner.parsedFiles(listOf(virtualFile))
        }

        assertEquals(1, parsedFiles.size)
        assertEquals(updatedText, parsedFiles[0].content)
    }

    fun testParsedFilesFallsBackToVfsWhenNoDocument() {
        val vf = myFixture.tempDirFixture.createFile("Unopened.kt", "val y = 10")

        val scanner = FileScanner(project)
        val parsedFiles = runReadAction {
            scanner.parsedFiles(listOf(vf))
        }

        assertEquals(1, parsedFiles.size)
        assertEquals("val y = 10", parsedFiles[0].content)
    }
}
