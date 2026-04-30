package com.example.ghactions.ui

import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.domain.Artifact
import com.example.ghactions.domain.RunId
import com.example.ghactions.repo.ArtifactsState
import com.example.ghactions.repo.DownloadResult
import com.example.ghactions.repo.RunRepository
import com.example.ghactions.repo.friendlyApiError
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Desktop
import java.io.File
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel

/**
 * Artifacts sub-tab inside [RunDetailPanel]. Lists the artifacts uploaded by the currently
 * selected run; *Download…* writes the zip into the user's chosen directory.
 */
class ArtifactsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(RunRepository::class.java)

    private var currentRunId: RunId? = null
    private var lastDownload: File? = null
    private var observerJob: CJob? = null

    private val tableModel = ArtifactsTableModel()
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        autoCreateRowSorter = true
    }

    private val statusLabel = JBLabel("").apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        foreground = UIUtil.getContextHelpForeground()
    }

    private val cardLayout = CardLayout()
    private val cardsPanel = JPanel(cardLayout).apply {
        add(JBScrollPane(table), CARD_TABLE)
        add(statusLabel, CARD_STATUS)
    }

    init {
        border = JBUI.Borders.empty()
        add(buildToolbar().component, BorderLayout.NORTH)
        add(cardsPanel, BorderLayout.CENTER)
        showStatus("Select a run to see its artifacts.")
    }

    fun showRun(runId: RunId) {
        currentRunId = runId
        observerJob?.cancel()
        observerJob = scope.launch {
            repository.artifactsState(runId).collect { render(it) }
        }
        repository.refreshArtifacts(runId)
    }

    fun clear() {
        currentRunId = null
        lastDownload = null
        observerJob?.cancel()
        observerJob = null
        tableModel.setArtifacts(emptyList())
        showStatus("Select a run to see its artifacts.")
    }

    private fun buildToolbar() = ActionManager.getInstance().createActionToolbar(
        ActionPlaces.TOOLWINDOW_CONTENT,
        DefaultActionGroup().apply {
            add(refreshAction())
            add(downloadAction())
            add(revealAction())
        },
        true
    ).also { it.targetComponent = this }

    private fun refreshAction() = object : AnAction("Refresh", "Reload artifacts", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentRunId != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            currentRunId?.let { repository.refreshArtifacts(it) }
        }
    }

    private fun downloadAction() = object : AnAction("Download…", "Download the selected artifact zip", AllIcons.Actions.Download) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedArtifact()?.expired == false
        }
        override fun actionPerformed(e: AnActionEvent) {
            val artifact = selectedArtifact() ?: return
            doDownload(artifact)
        }
    }

    private fun revealAction() = object : AnAction("Reveal in Files", "Open the last downloaded zip in the OS file browser", AllIcons.Actions.MenuOpen) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = lastDownload?.exists() == true
        }
        override fun actionPerformed(e: AnActionEvent) {
            lastDownload?.let { revealInFiles(it) }
        }
    }

    private fun selectedArtifact(): Artifact? {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return null
        val modelRow = table.convertRowIndexToModel(row)
        return tableModel.artifactAt(modelRow)
    }

    private fun doDownload(artifact: Artifact) {
        val dir = chooseTargetDir() ?: return
        val target = File(dir, "${artifact.name}-${artifact.id.value}.zip")
        statusLabel.text = "Downloading ${artifact.name}…"
        scope.launch {
            val result = repository.downloadArtifactToFile(artifact.id, target)
            ApplicationManager.getApplication().invokeLater {
                when (result) {
                    is DownloadResult.Success -> {
                        lastDownload = result.file
                        Messages.showInfoMessage(
                            project,
                            "Saved to ${result.file.absolutePath}",
                            "Artifact downloaded"
                        )
                    }
                    is DownloadResult.Error -> Messages.showErrorDialog(
                        project,
                        "Failed${result.httpStatus?.let { " ($it)" } ?: ""}: ${result.message}",
                        "Download failed"
                    )
                }
            }
        }
    }

    private fun chooseTargetDir(): File? {
        val configured = PluginSettings.getInstance().state.defaultDownloadDir
        if (!configured.isNullOrBlank() && File(configured).isDirectory) return File(configured)
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle("Choose Download Directory")
        var picked: File? = null
        FileChooserFactory.getInstance()
            .createPathChooser(descriptor, project, this)
            .choose(null) { files -> picked = files.firstOrNull()?.let { vf -> File(vf.path) } }
        return picked
    }

    private fun revealInFiles(file: File) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file.parentFile)
            }
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, "Could not open file browser: ${t.message}", "Reveal failed")
        }
    }

    private fun render(state: ArtifactsState) {
        when (state) {
            is ArtifactsState.Idle -> showStatus("Select a run to see its artifacts.")
            is ArtifactsState.Loading -> showStatus("Loading artifacts…")
            is ArtifactsState.Loaded -> {
                tableModel.setArtifacts(state.artifacts)
                if (state.artifacts.isEmpty()) showStatus("No artifacts uploaded by this run.")
                else cardLayout.show(cardsPanel, CARD_TABLE)
            }
            is ArtifactsState.Error -> showStatus(friendlyApiError(state.httpStatus, state.message))
        }
    }

    private fun showStatus(text: String) {
        statusLabel.text = text
        cardLayout.show(cardsPanel, CARD_STATUS)
    }

    override fun dispose() {
        observerJob?.cancel()
        scope.cancel()
    }

    private companion object {
        const val CARD_TABLE = "table"
        const val CARD_STATUS = "status"
    }
}

private class ArtifactsTableModel : AbstractTableModel() {
    private var rows: List<Artifact> = emptyList()

    fun setArtifacts(artifacts: List<Artifact>) {
        rows = artifacts
        fireTableDataChanged()
    }

    fun artifactAt(modelRow: Int): Artifact? = rows.getOrNull(modelRow)

    override fun getRowCount() = rows.size
    override fun getColumnCount() = 3
    override fun getColumnName(column: Int) = when (column) {
        0 -> "Name"
        1 -> "Size"
        2 -> "Status"
        else -> ""
    }

    override fun getValueAt(row: Int, column: Int): Any {
        val a = rows[row]
        return when (column) {
            0 -> a.name
            1 -> humanSize(a.sizeBytes)
            2 -> if (a.expired) "expired" else "available"
            else -> ""
        }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}
