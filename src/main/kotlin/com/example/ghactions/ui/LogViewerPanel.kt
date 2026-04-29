package com.example.ghactions.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Read-only viewer for GitHub Actions job logs. Plan 2 displays raw text;
 * Plan 3 will add ANSI color parsing and step-section folding.
 */
class LogViewerPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    private val editorFactory = EditorFactory.getInstance()
    private val document = editorFactory.createDocument("")
    private val editor: EditorEx = editorFactory.createViewer(document, project) as EditorEx

    init {
        editor.settings.apply {
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isLineNumbersShown = false
            isCaretRowShown = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
        editor.headerComponent = null

        border = JBUI.Borders.empty()
        add(editor.component, BorderLayout.CENTER)
    }

    /** Replace the displayed text. Safe to call from any thread; marshals to EDT. */
    fun setText(text: String) {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                document.setText(text)
            }
            // Auto-scroll to end so users see the latest output.
            editor.caretModel.moveToOffset(document.textLength)
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
    }

    fun clear() = setText("")

    override fun dispose() {
        editorFactory.releaseEditor(editor)
    }
}
