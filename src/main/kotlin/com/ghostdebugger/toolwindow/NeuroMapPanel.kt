package com.ghostdebugger.toolwindow

import com.ghostdebugger.GhostDebuggerService
import com.ghostdebugger.bridge.JcefBridge
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import java.io.File
import java.net.URL
import java.nio.file.Files
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class NeuroMapPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = logger<NeuroMapPanel>()
    private var browser: JBCefBrowser? = null

    init {
        if (JBCefApp.isSupported()) {
            initJcef()
        } else {
            add(JLabel("JCEF is not supported in this environment.", SwingConstants.CENTER), BorderLayout.CENTER)
        }
    }

    private fun initJcef() {
        try {
            val indexUrl = findWebIndexUrl()
            val jbBrowser = JBCefBrowser(indexUrl)
            browser = jbBrowser

            val service = GhostDebuggerService.getInstance(project)
            val bridge = JcefBridge(jbBrowser) { event ->
                service.handleUIEvent(event)
            }
            service.setBridge(bridge)

            add(jbBrowser.component, BorderLayout.CENTER)
            log.info("NeuroMapPanel: JCEF browser initialized with URL: $indexUrl")
        } catch (e: Exception) {
            log.error("Failed to initialize JCEF browser", e)
            add(
                JLabel("<html><center>Failed to load NeuroMap.<br>${e.message}</center></html>", SwingConstants.CENTER),
                BorderLayout.CENTER
            )
        }
    }

    private fun findWebIndexUrl(): String {
        // 1. Try sandbox/production plugin path
        val pluginDir = getPluginWebDir()
        if (pluginDir != null) {
            val indexFile = File(pluginDir, "index.html")
            if (indexFile.exists()) {
                log.info("Found web index at: ${indexFile.absolutePath}")
                return indexFile.toURI().toString()
            }
        }

        // 2. Try development path (when running from source)
        val devPath = File(project.basePath ?: "", "../src/main/resources/web/index.html")
        if (devPath.exists()) {
            return devPath.canonicalFile.toURI().toString()
        }

        val devPath2 = File("src/main/resources/web/index.html")
        if (devPath2.exists()) {
            return devPath2.canonicalFile.toURI().toString()
        }

        // 3. Extract from classpath (JAR)
        val resource = javaClass.classLoader.getResource("web/index.html")
        if (resource != null) {
            if (resource.protocol == "file") {
                return resource.toExternalForm()
            }
            // Extract from JAR to temp dir
            return extractWebResourcesToTemp(resource)
        }

        throw IllegalStateException(
            "Cannot find web/index.html. " +
            "Please build the webview first: cd webview && npm run build"
        )
    }

    private fun getPluginWebDir(): File? {
        try {
            val pluginId = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("com.ghostdebugger")
            ) ?: return null

            val pluginPath = pluginId.pluginPath

            // Check common locations in the plugin directory
            for (subPath in listOf("classes/web", "web", "lib/classes/web")) {
                val candidate = pluginPath.resolve(subPath).toFile()
                if (candidate.exists() && candidate.isDirectory) {
                    return candidate
                }
            }
        } catch (e: Exception) {
            log.warn("Could not determine plugin path", e)
        }
        return null
    }

    private fun extractWebResourcesToTemp(indexResource: URL): String {
        val tempDir = Files.createTempDirectory("ghostdebugger-web").toFile()
        tempDir.deleteOnExit()

        // Extract key files from classpath
        val filesToExtract = listOf("index.html", "assets")
        for (fileName in filesToExtract) {
            val res = javaClass.classLoader.getResource("web/$fileName")
            if (res != null) {
                val target = File(tempDir, fileName)
                try {
                    res.openStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    log.warn("Could not extract $fileName", e)
                }
            }
        }

        return File(tempDir, "index.html").toURI().toString()
    }
}
