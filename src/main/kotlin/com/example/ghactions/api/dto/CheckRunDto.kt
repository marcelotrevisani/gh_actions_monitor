package com.example.ghactions.api.dto

import com.example.ghactions.domain.CheckRunOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CheckRunDto(
    val id: Long,
    val output: CheckRunOutputDto = CheckRunOutputDto()
) {
    fun toDomain(): CheckRunOutput = CheckRunOutput(
        title = output.title,
        summary = output.summary,
        text = output.text,
        annotationsCount = output.annotationsCount ?: 0
    )
}

@Serializable
data class CheckRunOutputDto(
    val title: String? = null,
    val summary: String? = null,
    val text: String? = null,
    @SerialName("annotations_count") val annotationsCount: Int? = null
)
