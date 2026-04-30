package com.example.ghactions.domain

import java.time.Instant

data class Job(
    val id: JobId,
    val runId: RunId,
    val name: String,
    val status: RunStatus,
    val conclusion: RunConclusion?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val htmlUrl: String,
    val steps: List<Step>,
    val checkRunId: CheckRunId? = null
)
