package com.ghostdebugger.analysis.sdk

import com.ghostdebugger.analysis.Analyzer
import com.ghostdebugger.model.AnalysisContext
import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSource
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

@Service(Service.Level.PROJECT)
class ExternalAnalyzerLoader(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(ExternalAnalyzerLoader::class.java)

    @Volatile
    private var cachedAnalyzers: List<Analyzer>? = null

    private var activeClassLoader: URLClassLoader? = null

    init {
        Disposer.register(project, this)
        VirtualFileManager.getInstance().addAsyncFileListener(
            { events ->
                if (events.any { isAegisAnalyzerJarEvent(it) }) {
                    cachedAnalyzers = null
                }
                null
            },
            this
        )
    }

    private fun isAegisAnalyzerJarEvent(event: VFileEvent): Boolean {
        val path = event.path
        return path.contains(".aegis/analyzers/") && path.endsWith(".jar")
    }

    fun analyzers(): List<Analyzer> {
        val cached = cachedAnalyzers
        if (cached != null) return cached

        val loaded = loadAnalyzersFromJars()
        cachedAnalyzers = loaded
        return loaded
    }

    fun runExternalAnalyzer(analyzer: Analyzer, context: AnalysisContext): List<Issue> {
        return try {
            val rawIssues = analyzer.analyze(context)
            rawIssues.map { issue ->
                val currentSources = issue.sources
                if (!currentSources.contains(IssueSource.EXTERNAL_SDK)) {
                    issue.copy(sources = currentSources + IssueSource.EXTERNAL_SDK)
                } else {
                    issue
                }
            }
        } catch (e: Exception) {
            if (e is ProcessCanceledException) throw e
            LOG.warn("External SDK Analyzer '${analyzer.name}' (${analyzer.ruleId}) threw an exception and was isolated", e)
            emptyList()
        }
    }

    private fun loadAnalyzersFromJars(): List<Analyzer> {
        val baseDir = project.guessProjectDir() ?: return emptyList()
        val analyzersDir = baseDir.findFileByRelativePath(".aegis/analyzers") ?: return emptyList()
        if (!analyzersDir.isDirectory) return emptyList()

        val jarFiles = analyzersDir.children.filter { !it.isDirectory && it.name.endsWith(".jar") }
        if (jarFiles.isEmpty()) return emptyList()

        val urls = jarFiles.mapNotNull { vFile ->
            try {
                File(vFile.path).toURI().toURL()
            } catch (e: Exception) {
                LOG.warn("Failed to resolve URL for analyzer JAR: ${vFile.path}", e)
                null
            }
        }.toTypedArray()

        if (urls.isEmpty()) return emptyList()

        val parentClassLoader = Analyzer::class.java.classLoader
        val classLoader = URLClassLoader(urls, parentClassLoader)

        activeClassLoader?.close()
        activeClassLoader = classLoader

        val result = mutableListOf<Analyzer>()
        try {
            val loader = ServiceLoader.load(Analyzer::class.java, classLoader)
            for (analyzer in loader) {
                result.add(analyzer)
            }
        } catch (e: Exception) {
            if (e is ProcessCanceledException) throw e
            LOG.warn("Error discovering External SDK Analyzers via ServiceLoader", e)
        }

        return result
    }

    override fun dispose() {
        cachedAnalyzers = null
        try {
            activeClassLoader?.close()
        } catch (e: Exception) {
            LOG.debug("Error closing URLClassLoader on dispose", e)
        }
        activeClassLoader = null
    }

    companion object {
        fun getInstance(project: Project): ExternalAnalyzerLoader = project.service()
    }
}
