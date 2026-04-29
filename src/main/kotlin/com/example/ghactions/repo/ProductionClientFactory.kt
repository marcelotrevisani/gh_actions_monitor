package com.example.ghactions.repo

import com.example.ghactions.api.GitHubClient
import com.example.ghactions.api.GitHubHttp
import com.example.ghactions.auth.AuthSource
import com.example.ghactions.auth.BundledGithubAccountSource
import com.example.ghactions.auth.GitHubAccountResolver
import com.example.ghactions.auth.PatLookup
import com.example.ghactions.auth.PatStorage
import com.example.ghactions.auth.PluginSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Builds [GitHubClient] instances from the project's bound repo + the user's stored
 * credentials. Used by the project-scoped repositories — kept out of those classes so
 * the credential-resolution logic is testable on its own (and so the repositories don't
 * have to know which auth sources exist).
 *
 * Plan 5: `suspend` so IDE-account credential lookup (via the bundled GitHub plugin's
 * `GHAccountManager.findCredentials`) can flow through.
 */
internal object ProductionClientFactory {
    private val log = Logger.getInstance(ProductionClientFactory::class.java)

    suspend fun create(project: Project): GitHubClient? {
        val binding = project.getService(com.example.ghactions.repo.RepoBinding::class.java).current ?: return null
        val settings = PluginSettings.getInstance().state

        val resolver = GitHubAccountResolver(
            ideSource = BundledGithubAccountSource(),
            patLookup = object : PatLookup {
                override fun getToken(host: String) = PatStorage().getToken(host)
            },
            preferredAccountId = settings.preferredAccountId
        )
        val resolved = resolver.resolveAuth(binding.host) ?: run {
            log.info("No credentials available for ${binding.host}")
            return null
        }
        // Always pass the actual token to GitHubHttp via a Pat-shaped AuthSource — the
        // `Authorization` header is "token <X>" regardless of whether <X> came from a PAT
        // input or an IDE-account credential lookup.
        val patAsAuth = AuthSource.Pat(host = binding.host, token = resolved.token)
        val tracker = project.getService(com.example.ghactions.api.RateLimitTracker::class.java)
        val http = GitHubHttp.create(
            baseUrl = binding.host,
            auth = patAsAuth,
            onResponse = tracker::update
        )
        return GitHubClient(http)
    }
}
