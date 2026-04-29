package com.example.ghactions.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListCellRenderer

/**
 * Read-only viewer for GitHub Actions job logs.
 *
 * Header controls (left to right):
 *   * **Show timestamps** — toggles the leading `2026-04-29T…Z ` prefix.
 *   * **Section picker** — lists every `##[group]` block found in the log so you can
 *     navigate manually when the step→section heuristic doesn't match. The first entry
 *     is `(full log)`.
 *   * **Section label / "Show full log"** — appears while a section is active.
 */
class LogViewerPanel : JPanel(BorderLayout()) {

    private var rawText: String = ""
    private var sections: List<LogSection> = emptyList()
    private var sectionLineRange: IntRange? = null
    private var suppressPickerFire: Boolean = false

    private val textArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        margin = JBUI.insets(4, 8)
    }

    private val timestampToggle = JBCheckBox("Show timestamps", false).apply {
        addItemListener { renderText() }
    }

    /** Sentinel item meaning "show full log". */
    private object FullLog {
        override fun toString(): String = "(full log)"
    }

    private val sectionPicker = ComboBox<Any>().apply {
        renderer = SectionPickerRenderer()
        isVisible = false
        addActionListener {
            if (suppressPickerFire) return@addActionListener
            when (val selected = selectedItem) {
                is LogSection -> applySection(selected)
                else -> clearSectionFilter()
            }
        }
    }

    private val sectionLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private val showFullLogButton = JButton("Show full log").apply {
        isVisible = false
        addActionListener { clearSectionFilter() }
    }

    private val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        border = JBUI.Borders.empty(2, 6)
        add(timestampToggle)
        add(JBLabel("Section:"))
        add(sectionPicker)
        add(showFullLogButton)
        add(sectionLabel)
    }

    private val scrollPane = JBScrollPane(textArea).apply {
        border = JBUI.Borders.empty()
    }

    init {
        border = JBUI.Borders.empty()
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /** Replace the displayed text and reset filters. */
    fun setText(text: String) {
        rawText = text
        sections = LogSectionParser.parse(text)
        sectionLineRange = null
        sectionLabel.text = ""
        showFullLogButton.isVisible = false
        rebuildPicker()
        renderText()
    }

    fun clear() = setText("")

    /**
     * Filter the visible log to the section that corresponds to this step, by name.
     *
     * GitHub's logs typically contain more `##[group]` sections than the API's step list
     * (synthetic "Set up runner", per-action "Post <name>" cleanup, container/network init,
     * etc.). Matching by **name** rather than position avoids drifting onto the wrong block:
     *   1. Exact case-insensitive equality between step name and section name.
     *   2. Substring match either direction.
     * If nothing matches, the full log stays visible and the header notes that — the
     * section picker dropdown to the right lets you navigate manually in that case.
     */
    fun showStep(stepNumber: Int, stepName: String) {
        val match = pickSection(stepName)
        if (match == null) {
            sectionLineRange = null
            sectionLabel.text = "(step \"$stepName\" not found in log; showing full output — pick from Section dropdown if needed)"
            showFullLogButton.isVisible = false
            syncPickerSelection(null)
            renderText()
        } else {
            applySection(match)
        }
    }

    private fun pickSection(stepName: String): LogSection? {
        if (sections.isEmpty()) return null
        val needle = stepName.trim()
        sections.firstOrNull { it.name.equals(needle, ignoreCase = true) }?.let { return it }
        return sections.firstOrNull {
            it.name.contains(needle, ignoreCase = true) ||
                needle.contains(it.name, ignoreCase = true)
        }
    }

    private fun applySection(section: LogSection) {
        sectionLineRange = section.startLine until section.endLineExclusive
        sectionLabel.text = ""
        showFullLogButton.isVisible = true
        syncPickerSelection(section)
        renderText()
    }

    private fun clearSectionFilter() {
        sectionLineRange = null
        sectionLabel.text = ""
        showFullLogButton.isVisible = false
        syncPickerSelection(null)
        renderText()
    }

    private fun rebuildPicker() {
        suppressPickerFire = true
        try {
            val model = DefaultComboBoxModel<Any>()
            model.addElement(FullLog)
            sections.forEach { model.addElement(it) }
            sectionPicker.model = model
            sectionPicker.selectedIndex = 0
            sectionPicker.isVisible = sections.isNotEmpty()
        } finally {
            suppressPickerFire = false
        }
    }

    private fun syncPickerSelection(section: LogSection?) {
        suppressPickerFire = true
        try {
            sectionPicker.selectedItem = section ?: FullLog
        } finally {
            suppressPickerFire = false
        }
    }

    private fun renderText() {
        ApplicationManager.getApplication().invokeLater {
            val lines = rawText.lines()
            val visible = sectionLineRange?.let { range ->
                val from = range.first.coerceIn(0, lines.size)
                val toExcl = (range.last + 1).coerceIn(from, lines.size)
                lines.subList(from, toExcl)
            } ?: lines

            val formatted = if (timestampToggle.isSelected) {
                visible.joinToString("\n")
            } else {
                visible.joinToString("\n") { stripTimestamp(it) }
            }

            textArea.text = formatted
            textArea.caretPosition = 0
        }
    }

    private fun stripTimestamp(line: String): String =
        TIMESTAMP_RE.replaceFirst(line, "")

    /** Renderer for the section picker — show only the section name (or the FullLog sentinel). */
    private class SectionPickerRenderer : ListCellRenderer<Any> {
        private val delegate = JBLabel()
        override fun getListCellRendererComponent(
            list: JList<out Any>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): JBLabel {
            delegate.text = when (value) {
                is LogSection -> value.name.ifBlank { "(unnamed section)" }
                else -> value?.toString() ?: ""
            }
            delegate.background = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            delegate.foreground = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
            delegate.isOpaque = isSelected
            return delegate
        }
    }

    private companion object {
        private val TIMESTAMP_RE = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z\s""")
    }
}
