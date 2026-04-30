package com.example.ghactions.ui.ansi

import java.awt.Font
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Read-only `JTextPane` that renders [AnsiSpan]s with foreground colour and bold attributes
 * applied via [StyleConstants]. Replaces the `JTextArea` previously held by `LogViewerPanel`.
 *
 * Why not `EditorEx`/`EditorTextField`: Plan 2 hit memory leaks via the IntelliJ editor
 * lifecycle; plain Swing avoids the disposal complexity entirely. The trade-off (no smart
 * gutter, no syntax editor features) is acceptable — log viewing doesn't need those.
 */
class AnsiTextPane : JTextPane() {

    init {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    /**
     * Replace the displayed content. Safe to call from the EDT only — caller's responsibility
     * to marshal (current callers already do via `ApplicationManager.invokeLater`).
     */
    fun setSpans(spans: List<AnsiSpan>) {
        val doc = styledDocument
        doc.remove(0, doc.length)
        for (span in spans) {
            val attrs = SimpleAttributeSet()
            span.style.foreground?.let { StyleConstants.setForeground(attrs, AnsiPalette.awtColor(it)) }
            if (span.style.bold) StyleConstants.setBold(attrs, true)
            doc.insertString(doc.length, span.text, attrs)
        }
        caretPosition = 0
    }
}
