package com.example.ghactions.editor

import com.example.ghactions.domain.Annotation
import com.example.ghactions.domain.AnnotationLevel
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import javax.swing.Icon

/**
 * Single-annotation gutter icon. The platform reuses identical renderers across
 * redraws (cheap when [equals]/[hashCode] return value-based results), so we delegate
 * to the underlying [Annotation]'s data class equality.
 */
class AnnotationGutterRenderer(val annotation: Annotation) : GutterIconRenderer() {

    override fun getIcon(): Icon = when (annotation.level) {
        AnnotationLevel.FAILURE -> AllIcons.General.Error
        AnnotationLevel.WARNING -> AllIcons.General.Warning
        AnnotationLevel.NOTICE -> AllIcons.General.Information
        AnnotationLevel.UNKNOWN -> AllIcons.General.Note
    }

    override fun getTooltipText(): String {
        val title = annotation.title?.takeIf { it.isNotBlank() }
        return if (title != null) "$title — ${annotation.message}" else annotation.message
    }

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun equals(other: Any?): Boolean =
        other is AnnotationGutterRenderer && other.annotation == annotation

    override fun hashCode(): Int = annotation.hashCode()
}
