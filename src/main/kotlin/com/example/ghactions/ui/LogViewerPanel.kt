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
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Read-only viewer for GitHub Actions job logs. Plan 2 displays plain text in a
 * monospace [JTextArea] inside a scroll pane.
 *
 * Two display options live in the header:
 *   * **Show timestamps** — toggles the leading `2026-04-29T…Z ` prefix that GitHub
 *     emits on every line. Off by default; users who want them flip the checkbox.
 *   * **Show full log** — appears only while a step section is active. Resets the
 *     filter back to the whole job log.
 *
 * ANSI color rendering and proper editor folding land in Plan 3 alongside live
 * streaming.
 */
class LogViewerPanel : JPanel(BorderLayout()) {

    private var rawText: String = ""
    private var sectionLineRange: IntRange? = null

    private val textArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        margin = JBUI.insets(4, 8)
    }

    private val timestampToggle = JBCheckBox("Show timestamps", false).apply {
        addItemListener { renderText() }
    }

    private val sectionLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private val showFullLogButton = JButton("Show full log").apply {
        isVisible = false
        addActionListener {
            sectionLineRange = null
            sectionLabel.text = ""
            isVisible = false
            renderText()
        }
    }

    private val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        border = JBUI.Borders.empty(2, 6)
        add(timestampToggle)
        add(sectionLabel)
        add(showFullLogButton)
    }

    private val scrollPane = JBScrollPane(textArea).apply {
        border = JBUI.Borders.empty()
    }

    init {
        border = JBUI.Borders.empty()
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /** Replace the displayed text and reset any active section filter. */
    fun setText(text: String) {
        rawText = text
        sectionLineRange = null
        sectionLabel.text = ""
        showFullLogButton.isVisible = false
        renderText()
    }

    /** Reset to empty text and clear filters. */
    fun clear() = setText("")

    /**
     * Filter the visible log to the section that corresponds to this step.
     *
     * GitHub's logs contain more `##[group]` sections than the steps list — synthetic
     * "Set up runner", per-action "Post <name>" cleanup groups, container/network init,
     * etc. Index-based mapping drifts as a result. We instead match by name:
     *   1. Exact case-insensitive equality between step name and section name.
     *   2. Step name substring of section name (or vice versa).
     *   3. Fallback to positional index (`stepNumber - 1`) — useful when a step has
     *      no `name:` in the workflow YAML and GitHub auto-generates one we can't
     *      align textually.
     * If nothing matches, the full log stays visible and the header notes why.
     */
    fun showStep(stepNumber: Int, stepName: String) {
        val sections = LogSectionParser.parse(rawText)
        val section = pickSection(sections, stepNumber, stepName)
        if (section == null) {
            sectionLineRange = null
            sectionLabel.text = "(step \"$stepName\" not found in log; showing full output)"
            showFullLogButton.isVisible = false
        } else {
            sectionLineRange = section.startLine until section.endLineExclusive
            sectionLabel.text = "Step $stepNumber · $stepName"
            showFullLogButton.isVisible = true
        }
        renderText()
    }

    private fun pickSection(sections: List<LogSection>, stepNumber: Int, stepName: String): LogSection? {
        if (sections.isEmpty()) return null
        val needle = stepName.trim()
        // 1. Exact case-insensitive match.
        sections.firstOrNull { it.name.equals(needle, ignoreCase = true) }?.let { return it }
        // 2. Substring match either direction (handles "Run tests" vs "Run user tests" etc.).
        sections.firstOrNull {
            it.name.contains(needle, ignoreCase = true) ||
                needle.contains(it.name, ignoreCase = true)
        }?.let { return it }
        // 3. Positional fallback. GitHub usually emits the steps in order even when names
        //    don't match cleanly, so index gets us close.
        return sections.getOrNull(stepNumber - 1)
    }

    private fun renderText() {
        ApplicationManager.getApplication().invokeLater {
            val lines = rawText.lines()
            val visible = sectionLineRange?.let { range ->
                lines.subList(range.first.coerceAtMost(lines.size), range.last.coerceAtMost(lines.size))
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

    /**
     * Strip GitHub's leading `2026-04-29T19:33:10.0000000Z ` ISO-8601 timestamp from a
     * single line, if present. We tolerate up to nanosecond precision (7 fractional digits).
     */
    private fun stripTimestamp(line: String): String =
        TIMESTAMP_RE.replaceFirst(line, "")

    private companion object {
        private val TIMESTAMP_RE = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z\s""")
    }
}
