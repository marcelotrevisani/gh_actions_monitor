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
 * Plan 5 makes this `suspend` so the IDE-account credential lookup can flow through.
 */
internal object ProductionClientFactory {
    private val log = Logger.getInstance(ProductionClientFactory::class.java)

    fun create(project: Project): GitHubClient? {
        val binding = project.getService(com.example.ghactions.repo.RepoBinding::class.java).current ?: return null
        val settings = PluginSettings.getInstance().state

        val resolver = GitHubAccountResolver(
            ideSource = BundledGithubAccountSource(),
            patLookup = object : PatLookup {
                override fun getToken(host: String) = PatStorage().getToken(host)
            },
            preferredAccountId = settings.preferredAccountId
        )
        val auth = resolver.resolve(binding.host) ?: return null

        // Plan 2-era stub: only PAT auth produces a usable client; IDE-account auth
        // is wired in Plan 5 (next task).
        val token = when (auth) {
            is AuthSource.Pat -> auth.token
            is AuthSource.IdeAccount -> {
                log.warn("IDE-account credentials not yet wired; user must use a PAT for now.")
                return null
            }
        }
        val patAsAuth = AuthSource.Pat(host = binding.host, token = token)
        val http = GitHubHttp.create(binding.host, patAsAuth)
        return GitHubClient(http)
    }
}
