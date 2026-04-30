package com.example.ghactions.auth

/** Anything that can list IDE-configured GitHub accounts and look up their tokens. */
interface IdeGithubAccountSource {
    fun listAccounts(): List<IdeAccountInfo>

    /**
     * Returns the persistent token for the account identified by [accountId], or null if
     * the account doesn't exist, has no credentials stored, or the lookup fails.
     *
     * Default returns null — convenient for tests that only need the listing surface
     * (the resolver still works in PAT-only mode when this returns null).
     */
    suspend fun findToken(accountId: String): String? = null

    /**
     * Live stream of configured accounts. Emits at least once with the current set, then
     * re-emits whenever the underlying source changes (the bundled GitHub plugin updates
     * its `accountsState` when the user adds/removes an account). Used by the settings
     * panel to refresh its dropdown without requiring a Settings dialog reopen.
     *
     * Default returns a one-shot flow of the current [listAccounts] — fine for tests.
     */
    fun accountsFlow(): kotlinx.coroutines.flow.Flow<List<IdeAccountInfo>> =
        kotlinx.coroutines.flow.flowOf(listAccounts())
}

data class IdeAccountInfo(val id: String, val host: String)

/** Anything that can look up a PAT for a host. Pulled out for testability. */
interface PatLookup {
    fun getToken(host: String): String?
}

/** A successfully-resolved authentication: the source identity and its real token. */
data class ResolvedAuth(val auth: AuthSource, val token: String)

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

    /**
     * Async sibling of [resolve] that also returns the actual token to use. For PAT auth
     * the token is the one already stored in `PasswordSafe`; for IDE-account auth it's
     * fetched via [IdeGithubAccountSource.findToken] (a suspend call into the bundled
     * GitHub plugin's `GHAccountManager`).
     *
     * If the IDE-account lookup fails (no token stored for the matched account), we fall
     * through to the PAT path rather than returning a half-resolved result.
     */
    suspend fun resolveAuth(host: String): ResolvedAuth? {
        val normalizedTarget = normalize(host)

        val matchingIdeAccounts = ideSource.listAccounts()
            .filter { normalize(it.host) == normalizedTarget }

        if (matchingIdeAccounts.isNotEmpty()) {
            val chosen = matchingIdeAccounts.firstOrNull { it.id == preferredAccountId }
                ?: matchingIdeAccounts.first()
            val ideToken = ideSource.findToken(chosen.id)
            if (ideToken != null) {
                return ResolvedAuth(
                    auth = AuthSource.IdeAccount(host = host, accountId = chosen.id),
                    token = ideToken
                )
            }
            // Fall through to PAT — IDE account exists but credential lookup failed.
        }

        patLookup.getToken(host)?.let {
            return ResolvedAuth(auth = AuthSource.Pat(host = host, token = it), token = it)
        }
        return null
    }

    private fun normalize(url: String): String =
        url.trim().lowercase().trimEnd('/')
}

/**
 * Adapter that reads accounts from the bundled `org.jetbrains.plugins.github` plugin.
 *
 * The bundled plugin's account API has changed across IDE versions; this adapter is
 * deliberately defensive — if the call throws (API mismatch, plugin disabled), it logs
 * and returns an empty list so the user can still fall back to PAT auth.
 *
 * Verified against IDE 2024.3: `GHAccountManager` is an application service registered
 * by the bundled plugin and extends `AccountManagerBase<GithubAccount, String>`. The
 * inherited `accountsState: StateFlow<Set<GithubAccount>>` exposes the configured accounts.
 */
class BundledGithubAccountSource : IdeGithubAccountSource {
    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(BundledGithubAccountSource::class.java)

    override fun listAccounts(): List<IdeAccountInfo> = try {
        val mgr = com.intellij.openapi.components.service<
            org.jetbrains.plugins.github.authentication.accounts.GHAccountManager>()
        mgr.accountsState.value.map { acct ->
            IdeAccountInfo(
                id = acct.id,
                host = acct.server.toApiUrl().removeSuffix("/")
            )
        }
    } catch (e: Throwable) {
        log.warn("Failed to read IDE GitHub accounts; falling back to empty list", e)
        emptyList()
    }

    override suspend fun findToken(accountId: String): String? = try {
        val mgr = com.intellij.openapi.components.service<
            org.jetbrains.plugins.github.authentication.accounts.GHAccountManager>()
        val account = mgr.accountsState.value.firstOrNull { it.id == accountId }
        if (account == null) {
            log.warn("findToken: no IDE GitHub account with id=$accountId")
            null
        } else {
            mgr.findCredentials(account)
        }
    } catch (e: Throwable) {
        log.warn("findToken: lookup failed for id=$accountId", e)
        null
    }

    override fun accountsFlow(): kotlinx.coroutines.flow.Flow<List<IdeAccountInfo>> =
        kotlinx.coroutines.flow.flow {
            try {
                val mgr = com.intellij.openapi.components.service<
                    org.jetbrains.plugins.github.authentication.accounts.GHAccountManager>()
                mgr.accountsState.collect { accounts ->
                    emit(accounts.map { IdeAccountInfo(it.id, it.server.toApiUrl().removeSuffix("/")) })
                }
            } catch (e: Throwable) {
                log.warn("accountsFlow: failed", e)
                emit(emptyList())
            }
        }
}
