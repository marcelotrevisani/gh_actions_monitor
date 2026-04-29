package com.example.ghactions.api.dto

import com.example.ghactions.domain.PullRequest
import com.example.ghactions.domain.PullRequestId
import com.example.ghactions.domain.PullRequestState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class PullRequestDto(
    val id: Long,
    val number: Int,
    val state: String,
    val title: String,
    val user: UserDto? = null,
    val head: HeadDto,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val draft: Boolean = false
) {
    fun toDomain(): PullRequest = PullRequest(
        id = PullRequestId(id),
        number = number,
        state = PullRequestState.fromWire(state),
        title = title,
        authorLogin = user?.login,
        headRef = head.ref,
        headSha = head.sha,
        htmlUrl = htmlUrl,
        isDraft = draft,
        updatedAt = Instant.parse(updatedAt)
    )
}

/** Minimal head/base shape — we only surface ref + sha. */
@Serializable
data class HeadDto(
    val ref: String,
    val sha: String
)
