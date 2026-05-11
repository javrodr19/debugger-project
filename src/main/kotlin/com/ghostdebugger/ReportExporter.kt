package com.ghostdebugger

import com.ghostdebugger.model.ProjectGraph
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders the HTML analysis report and walks the user through saving + opening it.
 * Plain class (not @Service) — instantiated by UIEventRouter on demand. Lifted out of
 * GhostDebuggerService in V1.5.
 */
internal class ReportExporter(private val project: Project) {

    private val log = logger<ReportExporter>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun export(graph: ProjectGraph?) {
        val service = GhostDebuggerService.getInstance(project)
        if (graph == null) {
            scope.launch(Dispatchers.Swing) {
                service.jcefBridge()?.sendError("No analysis data available. Run 'Analyze Project' first.")
            }
            return
        }

        scope.launch(Dispatchers.Swing) {
            val descriptor = FileSaverDescriptor(
                "Export Aegis Debug Report",
                "Choose where to save the analysis report",
                "html"
            )
            val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            val defaultName = "aegis-debug-${sanitizeFilename(project.name)}-${System.currentTimeMillis()}.html"
            val saved = dialog.save(null as VirtualFile?, defaultName) ?: return@launch

            scope.launch {
                try {
                    val html = ReportGenerator().generateHTMLReport(graph)
                    val outFile = saved.file
                    outFile.writeText(html)
                    withContext(Dispatchers.Swing) {
                        notifyReportExported(outFile)
                    }
                } catch (e: Exception) {
                    if (e is ProcessCanceledException) throw e
                    log.error("Failed to write report", e)
                    withContext(Dispatchers.Swing) {
                        service.jcefBridge()?.sendError("Export failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun notifyReportExported(file: File) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("GhostDebugger")
            .createNotification(
                "Aegis Debug report exported",
                file.name,
                NotificationType.INFORMATION
            )
        notification.addAction(NotificationAction.create("Open in browser") { _, _ ->
            try {
                BrowserUtil.browse(file)
            } catch (e: Exception) {
                log.warn("Could not open browser for ${file.absolutePath}", e)
            }
        })
        notification.addAction(NotificationAction.create("Show in Files") { _, _ ->
            try {
                RevealFileAction.openFile(file)
            } catch (e: Exception) {
                log.warn("Could not reveal file ${file.absolutePath}", e)
            }
        })
        notification.notify(project)
    }

    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
