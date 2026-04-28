package com.example.ghactions.auth

/** Anything that can list IDE-configured GitHub accounts. Pulled out for testability. */
interface IdeGithubAccountSource {
    fun listAccounts(): List<IdeAccountInfo>
}

data class IdeAccountInfo(val id: String, val host: String)

/** Anything that can look up a PAT for a host. Pulled out for testability. */
interface PatLookup {
    fun getToken(host: String): String?
}

/**
 * Resolves credentials for a target [host] in priority order:
 * 1. IDE-configured GitHub account whose host matches.
 *    If multiple match, prefer the one whose id == [preferredAccountId]; otherwise the first.
 * 2. PAT stored in PasswordSafe for that host.
 * 3. null — caller should show empty state.
 */
class GitHubAccountResolver(
    private val ideSource: IdeGithubAccountSource,
    private val patLookup: PatLookup,
    private val preferredAccountId: String?
) {

    fun resolve(host: String): AuthSource? {
        val normalizedTarget = normalize(host)

        val matchingIdeAccounts = ideSource.listAccounts()
            .filter { normalize(it.host) == normalizedTarget }

        if (matchingIdeAccounts.isNotEmpty()) {
            val chosen = matchingIdeAccounts.firstOrNull { it.id == preferredAccountId }
                ?: matchingIdeAccounts.first()
            return AuthSource.IdeAccount(host = host, accountId = chosen.id)
        }

        patLookup.getToken(host)?.let {
            return AuthSource.Pat(host = host, token = it)
        }

        return null
    }

    private fun normalize(url: String): String =
        url.trim().lowercase().trimEnd('/')
}
