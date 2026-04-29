package com.example.ghactions.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Read-only viewer for log text. Plan 3: no section parsing — the [RunRepository] now
 * hands us pre-extracted per-step text via the run-logs zip archive, so [setText] just
 * renders it. The only header control is the *Show timestamps* toggle.
 *
 * (ANSI color rendering is Plan 5.)
 */
class LogViewerPanel : JPanel(BorderLayout()) {

    private var rawText: String = ""

    private val textArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        margin = JBUI.insets(4, 8)
    }

    private val timestampToggle = JBCheckBox("Show timestamps", false).apply {
        addItemListener { renderText() }
    }

    private val statusLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        border = JBUI.Borders.empty(2, 6)
        add(timestampToggle)
        add(statusLabel)
    }

    private val scrollPane = JBScrollPane(textArea).apply {
        border = JBUI.Borders.empty()
    }

    init {
        border = JBUI.Borders.empty()
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /** Replace the displayed text. Safe from any thread; marshals to EDT. */
    fun setText(text: String) {
        rawText = text
        statusLabel.text = ""
        renderText()
    }

    /** Set a status hint shown next to the timestamps toggle (e.g., 'Step 3 · Run swift test'). */
    fun setStatus(text: String) {
        statusLabel.text = text
    }

    fun clear() {
        setText("")
        statusLabel.text = ""
    }

    private fun renderText() {
        ApplicationManager.getApplication().invokeLater {
            val visible = if (timestampToggle.isSelected) {
                rawText
            } else {
                rawText.lineSequence().joinToString("\n") { stripTimestamp(it) }
            }
            textArea.text = visible
            textArea.caretPosition = 0
        }
    }

    private fun stripTimestamp(line: String): String =
        TIMESTAMP_RE.replaceFirst(line, "")

    private companion object {
        private val TIMESTAMP_RE = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z\s""")
    }
}
