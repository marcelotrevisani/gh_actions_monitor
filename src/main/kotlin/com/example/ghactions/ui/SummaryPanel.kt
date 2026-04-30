package com.example.ghactions.ui

import com.example.ghactions.domain.RunId
import com.example.ghactions.repo.RunRepository
import com.example.ghactions.repo.SummaryState
import com.example.ghactions.repo.friendlyApiError
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BorderLayout
import java.awt.Desktop
import java.net.URI
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/**
 * Renders each job's check-run summary (markdown) as HTML in a JEditorPane.
 *
 * GitHub Actions step summaries (`$GITHUB_STEP_SUMMARY`) and check-run output summaries
 * are GitHub-Flavoured Markdown. We parse with `org.jetbrains:markdown` (GFM flavour) and
 * render via JEditorPane's built-in HTML support — sufficient for headers, lists, code,
 * bold/italic, links, tables. Hyperlinks open in the OS browser via Desktop.browse().
 *
 * One `<h2>` per job section; a `<hr>` separator between sections.
 */
class SummaryPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(RunRepository::class.java)
    private var observerJob: CJob? = null

    private val flavour = GFMFlavourDescriptor()

    private val editorPane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        // Make hyperlinks clickable.
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED && e.url != null) {
                runCatching {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI(e.url.toString()))
                    }
                }
            }
        }
        margin = JBUI.insets(8)
        background = UIUtil.getPanelBackground()
    }

    init {
        border = JBUI.Borders.empty()
        add(JBScrollPane(editorPane), BorderLayout.CENTER)
        setHtml(emptyMessageHtml("Select a run to see its summary."))
    }

    fun showRun(runId: RunId) {
        observerJob?.cancel()
        observerJob = scope.launch {
            repository.summaryState(runId).collect { render(it) }
        }
        repository.refreshSummary(runId)
    }

    fun clear() {
        observerJob?.cancel()
        observerJob = null
        ApplicationManager.getApplication().invokeLater {
            setHtml(emptyMessageHtml("Select a run to see its summary."))
        }
    }

    private fun render(state: SummaryState) {
        val html = when (state) {
            is SummaryState.Idle -> emptyMessageHtml("Select a run to see its summary.")
            is SummaryState.Loading -> emptyMessageHtml("Loading summary…")
            is SummaryState.Loaded -> if (state.sections.isEmpty()) {
                emptyMessageHtml("No check-run summaries for this run.")
            } else {
                buildSectionsHtml(state.sections)
            }
            is SummaryState.Error -> emptyMessageHtml(friendlyApiError(state.httpStatus, state.message))
        }
        ApplicationManager.getApplication().invokeLater { setHtml(html) }
    }

    private fun setHtml(body: String) {
        editorPane.text = wrap(body)
        editorPane.caretPosition = 0
    }

    private fun wrap(body: String): String {
        // JEditorPane's HTMLEditorKit supports a small subset of CSS. Lean on default
        // styling and only nudge font sizing + spacing to match the IDE.
        val fg = colorHex(UIUtil.getLabelForeground())
        return """
            <html><head><style>
              body { font-family: sans-serif; font-size: 12pt; color: $fg; padding: 0 4px; }
              h1, h2, h3 { margin-top: 12px; margin-bottom: 6px; }
              h1 { font-size: 16pt; }
              h2 { font-size: 14pt; border-bottom: 1px solid #888; padding-bottom: 2px; }
              h3 { font-size: 12pt; }
              code { font-family: monospace; }
              pre { font-family: monospace; padding: 6px; background: ${codeBg()}; }
              hr { border: 0; border-top: 1px solid #888; margin: 12px 0; }
              table { border-collapse: collapse; }
              th, td { border: 1px solid #888; padding: 2px 6px; }
            </style></head><body>$body</body></html>
        """.trimIndent()
    }

    private fun colorHex(c: java.awt.Color): String =
        "#%02x%02x%02x".format(c.red, c.green, c.blue)

    private fun codeBg(): String =
        if (!com.intellij.ui.JBColor.isBright()) "#2b2b2b" else "#f4f4f4"

    private fun emptyMessageHtml(text: String): String =
        """<div style="text-align:center; color:#888; padding:32px;">${escape(text)}</div>"""

    private fun buildSectionsHtml(sections: List<SummaryState.Section>): String =
        sections.joinToString(separator = "<hr/>") { section ->
            val header = "<h2>${escape(section.jobName)}</h2>"
            val body = section.output.summary?.takeIf { it.isNotBlank() }?.let { renderMarkdown(it) }
                ?: "<p><i>(no summary)</i></p>"
            header + body
        }

    private fun renderMarkdown(src: String): String {
        // GFM flavour gives us tables, task lists, autolinks, strikethrough, fenced code.
        val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(src)
        return HtmlGenerator(src, parsedTree, flavour).generateHtml()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun dispose() {
        observerJob?.cancel()
        scope.cancel()
    }
}
