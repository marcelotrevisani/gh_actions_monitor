package com.example.ghactions.api.dto

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class RunDto(
    val id: Long,
    val name: String,
    @SerialName("head_branch") val headBranch: String? = null,
    @SerialName("head_sha") val headSha: String,
    val path: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("run_number") val runNumber: Int,
    val event: String,
    val status: String,
    val conclusion: String? = null,
    @SerialName("workflow_id") val workflowId: Long,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val actor: UserDto? = null
) {
    fun toDomain(): Run = Run(
        id = RunId(id),
        workflowName = name,
        status = RunStatus.fromWire(status),
        conclusion = RunConclusion.fromWire(conclusion),
        headBranch = headBranch,
        headSha = headSha,
        event = event,
        actorLogin = actor?.login,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
        htmlUrl = htmlUrl,
        runNumber = runNumber,
        displayTitle = displayTitle
    )
}
