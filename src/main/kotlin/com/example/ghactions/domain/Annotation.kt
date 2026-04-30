package com.example.ghactions.domain

/**
 * GitHub annotation on a check run: a severity-tagged message anchored at a file/line range.
 * Maps to a row in the Annotations tab.
 */
data class Annotation(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val level: AnnotationLevel,
    val title: String?,
    val message: String,
    val rawDetails: String? = null
)

enum class AnnotationLevel {
    NOTICE, WARNING, FAILURE, UNKNOWN;

    companion object {
        fun fromWire(value: String?): AnnotationLevel = when (value?.lowercase()) {
            "notice" -> NOTICE
            "warning" -> WARNING
            "failure" -> FAILURE
            else -> UNKNOWN
        }
    }
}
