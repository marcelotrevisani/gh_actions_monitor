package com.example.ghactions.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Read-only viewer for GitHub Actions job logs. Plan 2 displays raw text in a plain
 * monospace [JTextArea] inside a scroll pane — no platform editor, no write-action
 * gymnastics, no disposer chain to leak. ANSI color rendering and `::group::` folding
 * land in Plan 3 alongside live streaming, where the editor's structured features
 * actually pay off.
 */
class LogViewerPanel : JPanel(BorderLayout()) {

    private val textArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        margin = JBUI.insets(4, 8)
    }

    private val scrollPane = JBScrollPane(textArea).apply {
        border = JBUI.Borders.empty()
    }

    init {
        border = JBUI.Borders.empty()
        add(scrollPane, BorderLayout.CENTER)
    }

    /** Replace the displayed text. Safe to call from any thread; marshals to EDT. */
    fun setText(text: String) {
        ApplicationManager.getApplication().invokeLater {
            textArea.text = text
            // Auto-scroll to end so users see the latest output.
            textArea.caretPosition = textArea.document.length
        }
    }

    fun clear() = setText("")
}
