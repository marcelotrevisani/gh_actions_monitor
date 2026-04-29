package com.example.ghactions.domain

import java.time.Instant

@JvmInline
value class PullRequestId(val value: Long) {
    override fun toString(): String = value.toString()
}

/**
 * One pull request on a GitHub repo. Slimmed to the slice we display in the PR tree.
 *
 * [headRef] is the branch the PR was opened from (i.e., what the local checkout matches when
 * working on the PR). [authorLogin] is null only if GitHub omits the user (deleted account).
 */
data class PullRequest(
    val id: PullRequestId,
    val number: Int,
    val state: PullRequestState,
    val title: String,
    val authorLogin: String?,
    val headRef: String,
    val headSha: String,
    val htmlUrl: String,
    val isDraft: Boolean,
    val updatedAt: Instant
)
