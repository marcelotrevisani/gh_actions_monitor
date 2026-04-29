package com.example.ghactions.domain

import java.time.Instant

/**
 * One workflow run on a GitHub repo. Roughly mirrors the slice of GitHub's
 * "workflow run" object we surface in the UI. [conclusion] is null while in flight.
 */
data class Run(
    val id: RunId,
    val workflowName: String,
    val status: RunStatus,
    val conclusion: RunConclusion?,
    val headBranch: String?,
    val headSha: String,
    val event: String,
    val actorLogin: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val htmlUrl: String,
    val runNumber: Int,
    val displayTitle: String?
)
