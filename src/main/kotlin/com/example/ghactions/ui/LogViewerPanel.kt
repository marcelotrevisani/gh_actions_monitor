package com.example.ghactions.ui

import com.example.ghactions.ui.ansi.AnsiParser
import com.example.ghactions.ui.ansi.AnsiTextPane
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * Read-only viewer for log text. Plan 7: ANSI escape codes in the input are parsed and
 * rendered with foreground color + bold via [AnsiTextPane]. Plan 3's per-step zip extraction
 * still feeds [setText]; this panel doesn't parse run structure.
 */
class LogViewerPanel : JPanel(BorderLayout()) {

    private var rawText: String = ""

    private val textPane = AnsiTextPane().apply {
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

    private val scrollPane = JBScrollPane(textPane).apply {
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
            textPane.setSpans(AnsiParser.parse(visible))
        }
    }

    private fun stripTimestamp(line: String): String =
        TIMESTAMP_RE.replaceFirst(line, "")

    private companion object {
        private val TIMESTAMP_RE = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z\s""")
    }
}
