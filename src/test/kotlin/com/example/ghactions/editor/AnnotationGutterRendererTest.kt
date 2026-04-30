package com.example.ghactions.editor

import com.example.ghactions.domain.Annotation
import com.example.ghactions.domain.AnnotationLevel
import com.intellij.icons.AllIcons
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AnnotationGutterRendererTest {

    private fun ann(level: AnnotationLevel, message: String, title: String? = null): Annotation =
        Annotation(
            path = "src/main.kt",
            startLine = 10, endLine = 10,
            level = level, title = title, message = message
        )

    @Test
    fun `icon picked from severity`() {
        assertEquals(AllIcons.General.Error, AnnotationGutterRenderer(ann(AnnotationLevel.FAILURE, "boom")).icon)
        assertEquals(AllIcons.General.Warning, AnnotationGutterRenderer(ann(AnnotationLevel.WARNING, "x")).icon)
        assertEquals(AllIcons.General.Information, AnnotationGutterRenderer(ann(AnnotationLevel.NOTICE, "x")).icon)
    }

    @Test
    fun `tooltip prepends title when present`() {
        val tooltip = AnnotationGutterRenderer(ann(AnnotationLevel.FAILURE, "the message", title = "Build")).tooltipText
        assertTrue(tooltip.contains("Build"), "tooltip missing title: $tooltip")
        assertTrue(tooltip.contains("the message"), "tooltip missing message: $tooltip")
    }

    @Test
    fun `equals respects underlying annotation`() {
        val a = AnnotationGutterRenderer(ann(AnnotationLevel.WARNING, "foo"))
        val b = AnnotationGutterRenderer(ann(AnnotationLevel.WARNING, "foo"))
        val c = AnnotationGutterRenderer(ann(AnnotationLevel.WARNING, "bar"))
        assertEquals(a, b, "same annotation -> equal renderers")
        assertNotEquals(a, c, "different message -> different renderers")
    }
}
