package com.example.ghactions.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GitHub's list-runs envelope: `{ total_count, workflow_runs: [...] }`. */
@Serializable
data class ListRunsResponse(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("workflow_runs") val workflowRuns: List<RunDto>
)

/** GitHub's list-jobs envelope: `{ total_count, jobs: [...] }`. */
@Serializable
data class ListJobsResponse(
    @SerialName("total_count") val totalCount: Int,
    val jobs: List<JobDto>
)
