package com.example.ghactions.domain

import java.time.Instant

/**
 * One artifact uploaded by a workflow run. GitHub keeps these for ~90 days by default,
 * after which `expired = true` and the download endpoint returns 410.
 */
data class Artifact(
    val id: ArtifactId,
    val name: String,
    val sizeBytes: Long,
    val expired: Boolean,
    val createdAt: Instant?
)
