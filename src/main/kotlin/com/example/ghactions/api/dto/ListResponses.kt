package com.example.ghactions.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GitHub's list-runs envelope: `{ total_count, workflow_runs: [...] }`. */
@Serializable
data class ListRunsResponse(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("workflow_runs") val workflowRuns: List<RunDto>
)

// ListJobsResponse is added in Task 6 alongside JobDto.
