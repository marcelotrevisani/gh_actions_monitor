package com.example.ghactions.editor

import com.example.ghactions.domain.Annotation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Key

/**
 * Applies annotation gutter icons to an [Editor]. Tracks the highlighters we added in
 * [Editor.getUserData] so each subsequent call cleans up the previous batch before adding
 * a new one — otherwise repeated state-flow emissions would stack identical icons.
 *
 * Pure non-static state lives only on the [Editor] instance; the singleton is stateless.
 */
object EditorAnnotationsApplier {

    private val OUR_HIGHLIGHTERS = Key.create<MutableList<RangeHighlighter>>("ghactions.gutterHighlighters")

    fun applyToEditor(editor: Editor, annotations: List<Annotation>) {
        clearFor(editor)
        if (annotations.isEmpty()) return
        val markup = editor.markupModel
        val document = editor.document
        val list = mutableListOf<RangeHighlighter>()
        for (a in annotations) {
            val rangeOk = a.startLine >= 1 && a.startLine <= document.lineCount
            if (!rangeOk) continue
            val highlighter = addHighlighter(markup, document, a)
            list += highlighter
        }
        editor.putUserData(OUR_HIGHLIGHTERS, list)
    }

    fun clearFor(editor: Editor) {
        val existing = editor.getUserData(OUR_HIGHLIGHTERS) ?: return
        existing.forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(OUR_HIGHLIGHTERS, null)
    }

    private fun addHighlighter(
        markup: MarkupModel,
        document: Document,
        a: Annotation
    ): RangeHighlighter {
        // GitHub annotations are 1-based; Document.getLineStartOffset is 0-based.
        val line = (a.startLine - 1).coerceIn(0, document.lineCount - 1)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val highlighter = markup.addRangeHighlighter(
            start, end,
            HighlighterLayer.WARNING,            // above syntax, below errors
            null,                                 // no text attributes — gutter only
            HighlighterTargetArea.LINES_IN_RANGE
        )
        highlighter.gutterIconRenderer = AnnotationGutterRenderer(a)
        return highlighter
    }
}
