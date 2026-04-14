package com.ghostdebugger.analysis

import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.ParsedFile
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.vcs.changes.ChangeListManager

fun AnalysisContext.limitTo(cap: Int): AnalysisContext {
    if (cap <= 0) return this
    if (parsedFiles.size <= cap) return this
    val prioritized = prioritizeFiles(
        files = parsedFiles,
        cap = cap,
        currentFilePath = {
            runCatching {
                FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path
            }.getOrNull()
        },
        changedFilePaths = {
            runCatching {
                ChangeListManager.getInstance(project).changeLists
                    .flatMap { it.changes }
                    .mapNotNull { it.virtualFile?.path }
            }.getOrElse { emptyList() }
        },
        recentFilePaths = {
            runCatching {
                EditorHistoryManager.getInstance(project).fileList.map { it.path }
            }.getOrElse { emptyList() }
        },
        hotspotFilePaths = {
            graph.getAllNodes()
                .sortedByDescending { it.complexity }
                .map { it.filePath }
        }
    )
    return copy(parsedFiles = prioritized)
}

fun AnalysisContext.limitAiFilesTo(cap: Int): AnalysisContext {
    if (cap <= 0) return copy(parsedFiles = emptyList())
    if (parsedFiles.size <= cap) return this
    val prioritized = prioritizeFiles(
        files = parsedFiles,
        cap = cap,
        currentFilePath = {
            runCatching {
                FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path
            }.getOrNull()
        },
        changedFilePaths = {
            runCatching {
                ChangeListManager.getInstance(project).changeLists
                    .flatMap { it.changes }
                    .mapNotNull { it.virtualFile?.path }
            }.getOrElse { emptyList() }
        },
        recentFilePaths = {
            runCatching {
                EditorHistoryManager.getInstance(project).fileList.map { it.path }
            }.getOrElse { emptyList() }
        },
        hotspotFilePaths = {
            graph.getAllNodes()
                .sortedByDescending { it.complexity }
                .map { it.filePath }
        }
    )
    return copy(parsedFiles = prioritized)
}

internal fun prioritizeFiles(
    files: List<ParsedFile>,
    cap: Int,
    currentFilePath: () -> String?,
    changedFilePaths: () -> List<String>,
    recentFilePaths: () -> List<String>,
    hotspotFilePaths: () -> List<String>
): List<ParsedFile> {
    if (cap <= 0) return emptyList()
    if (files.size <= cap) return files
    val byPath = files.associateBy { it.path }
    val ordered = linkedSetOf<ParsedFile>()

    // 1) current file
    currentFilePath()?.let { byPath[it]?.let(ordered::add) }

    // 2) changed files
    for (p in changedFilePaths()) byPath[p]?.let(ordered::add)

    // 3) recently opened files
    for (p in recentFilePaths()) byPath[p]?.let(ordered::add)

    // 4) hotspots
    for (p in hotspotFilePaths()) byPath[p]?.let(ordered::add)

    // 5) remaining, preserving source order
    for (f in files) ordered.add(f)

    return ordered.take(cap)
}
