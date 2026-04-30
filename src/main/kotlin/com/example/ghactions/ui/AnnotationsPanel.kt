package com.example.ghactions.ui

import com.example.ghactions.domain.Annotation as DomainAnnotation
import com.example.ghactions.domain.AnnotationLevel
import com.example.ghactions.domain.RunId
import com.example.ghactions.repo.AnnotationsState
import com.example.ghactions.repo.RunRepository
import com.example.ghactions.repo.friendlyApiError
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class AnnotationsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(RunRepository::class.java)
    private var observerJob: CJob? = null
    private var currentRunId: RunId? = null

    private val tableModel = AnnotationsTableModel()
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        autoCreateRowSorter = true
        getColumnModel().getColumn(0).preferredWidth = 30
        getColumnModel().getColumn(0).cellRenderer = SeverityRenderer()
        getColumnModel().getColumn(1).preferredWidth = 320
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
        add(cardsPanel, BorderLayout.CENTER)
        showStatus("Select a run to see its annotations.")
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    selectedItem()?.let { openInEditor(it.annotation) }
                }
            }
        })
    }

    fun showRun(runId: RunId) {
        currentRunId = runId
        observerJob?.cancel()
        observerJob = scope.launch {
            repository.annotationsState(runId).collect { render(it) }
        }
        repository.refreshAnnotations(runId)
    }

    fun clear() {
        currentRunId = null
        observerJob?.cancel()
        observerJob = null
        tableModel.setItems(emptyList())
        showStatus("Select a run to see its annotations.")
    }

    private fun selectedItem(): AnnotationsState.Item? {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return null
        return tableModel.itemAt(table.convertRowIndexToModel(row))
    }

    private fun openInEditor(ann: DomainAnnotation) {
        // Annotation paths are repo-relative. Try `<project basePath>/<path>` first; fall
        // back to the raw path for the rare case where the user's project root differs
        // from the repo root.
        val basePath = project.basePath
        val candidate = (basePath?.let { LocalFileSystem.getInstance().findFileByPath("$it/${ann.path}") })
            ?: LocalFileSystem.getInstance().findFileByPath(ann.path)
            ?: return
        // OpenFileDescriptor uses 0-based line numbers; GitHub annotations are 1-based.
        OpenFileDescriptor(project, candidate, (ann.startLine - 1).coerceAtLeast(0), 0)
            .navigate(true)
    }

    private fun render(state: AnnotationsState) {
        when (state) {
            is AnnotationsState.Idle -> showStatus("Select a run to see its annotations.")
            is AnnotationsState.Loading -> showStatus("Loading annotations…")
            is AnnotationsState.Loaded -> {
                tableModel.setItems(state.items)
                if (state.items.isEmpty()) showStatus("No annotations for this run.")
                else cardLayout.show(cardsPanel, CARD_TABLE)
            }
            is AnnotationsState.Error -> showStatus(friendlyApiError(state.httpStatus, state.message))
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

private class AnnotationsTableModel : AbstractTableModel() {
    private var rows: List<AnnotationsState.Item> = emptyList()

    fun setItems(items: List<AnnotationsState.Item>) {
        rows = items
        fireTableDataChanged()
    }

    fun itemAt(modelRow: Int): AnnotationsState.Item? = rows.getOrNull(modelRow)

    override fun getRowCount() = rows.size
    override fun getColumnCount() = 3
    override fun getColumnName(column: Int) = when (column) {
        0 -> ""
        1 -> "Job · File:Line"
        2 -> "Message"
        else -> ""
    }

    override fun getValueAt(row: Int, column: Int): Any {
        val item = rows[row]
        return when (column) {
            0 -> item.annotation.level
            1 -> "${item.jobName} · ${item.annotation.path}:${item.annotation.startLine}"
            2 -> (item.annotation.title?.let { "$it — " } ?: "") + item.annotation.message
            else -> ""
        }
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        0 -> AnnotationLevel::class.java
        else -> String::class.java
    }
}

private class SeverityRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean,
        row: Int, column: Int
    ): Component {
        super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column)
        val level = value as? AnnotationLevel ?: AnnotationLevel.UNKNOWN
        icon = iconFor(level)
        horizontalAlignment = JLabel.CENTER
        return this
    }

    private fun iconFor(level: AnnotationLevel): Icon = when (level) {
        AnnotationLevel.FAILURE -> AllIcons.General.Error
        AnnotationLevel.WARNING -> AllIcons.General.Warning
        AnnotationLevel.NOTICE -> AllIcons.General.Information
        AnnotationLevel.UNKNOWN -> AllIcons.General.Note
    }
}
