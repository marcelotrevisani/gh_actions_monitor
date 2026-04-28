package com.example.ghactions.auth

/**
 * A resolved source of GitHub credentials. Returned by [GitHubAccountResolver].
 *
 * Token values are deliberately excluded from [toString] so they cannot end up in logs.
 */
sealed class AuthSource {
    abstract val host: String

    data class Pat(
        override val host: String,
        val token: String
    ) : AuthSource() {
        override fun toString(): String = "AuthSource.Pat(host=$host, token=***)"
    }

    data class IdeAccount(
        override val host: String,
        val accountId: String
    ) : AuthSource() {
        override fun toString(): String = "AuthSource.IdeAccount(host=$host, accountId=$accountId)"
    }
}
