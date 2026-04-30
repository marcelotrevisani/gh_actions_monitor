package com.example.ghactions.api.dto

import com.example.ghactions.domain.Annotation
import com.example.ghactions.domain.AnnotationLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnnotationDto(
    val path: String,
    @SerialName("start_line") val startLine: Int,
    @SerialName("end_line") val endLine: Int,
    @SerialName("annotation_level") val annotationLevel: String? = null,
    val title: String? = null,
    val message: String,
    @SerialName("raw_details") val rawDetails: String? = null
) {
    fun toDomain(): Annotation = Annotation(
        path = path,
        startLine = startLine,
        endLine = endLine,
        level = AnnotationLevel.fromWire(annotationLevel),
        title = title,
        message = message,
        rawDetails = rawDetails
    )
}
