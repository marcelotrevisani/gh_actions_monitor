package com.example.ghactions.api.dto

import com.example.ghactions.domain.Workflow
import com.example.ghactions.domain.WorkflowId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowDto(
    val id: Long,
    val name: String,
    val path: String,
    val state: String
) {
    fun toDomain(): Workflow = Workflow(
        id = WorkflowId(id),
        name = name,
        path = path,
        state = state
    )
}

@Serializable
data class ListWorkflowsResponse(
    @SerialName("total_count") val totalCount: Int,
    val workflows: List<WorkflowDto>
)

/** Request body for the dispatch endpoint. */
@Serializable
data class DispatchRequest(val ref: String)
