package com.example.ghactions.repo

import com.example.ghactions.events.BoundRepo
import com.example.ghactions.events.Topics
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * Resolves the open project's bound GitHub repo from its `origin` git remote and republishes
 * changes via [Topics.REPO_BINDING_CHANGED]. Listens for git remote/repository changes via
 * the platform's [VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED] topic.
 */
@Service(Service.Level.PROJECT)
class RepoBinding(private val project: Project) : Disposable {

    private val log = Logger.getInstance(RepoBinding::class.java)

    @Volatile
    var current: BoundRepo? = null
        private set

    init {
        recompute()
        project.messageBus.connect(this).subscribe(
            VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED,
            VcsRepositoryMappingListener { recompute() }
        )
    }

    fun recompute() {
        val previous = current
        val next = computeBoundRepo()
        current = next
        if (previous != next) {
            log.info("Repo binding changed: $previous -> $next")
            project.messageBus.syncPublisher(Topics.REPO_BINDING_CHANGED).onRepoBindingChanged(next)
        }
    }

    private fun computeBoundRepo(): BoundRepo? {
        val mgr = GitRepositoryManager.getInstance(project)
        val repo = mgr.repositories.firstOrNull() ?: return null
        val origin = repo.remotes.firstOrNull { it.name == "origin" }
            ?: repo.remotes.firstOrNull()
            ?: return null
        val firstUrl = origin.urls.firstOrNull() ?: return null
        return parseRemote(firstUrl)
    }

    override fun dispose() = Unit

    companion object {
        // Matches: https://host/owner/repo[.git] or git@host:owner/repo[.git]
        private val HTTPS_RE = Regex("^https?://([^/]+)/([^/]+)/([^/.]+?)(?:\\.git)?/?$")
        private val SSH_RE = Regex("^git@([^:]+):([^/]+)/([^/.]+?)(?:\\.git)?/?$")

        /**
         * Parses a git remote URL and returns the bound repo, or null if the URL is not GitHub-shaped
         * or is malformed. Public for testability.
         */
        fun parseRemote(url: String): BoundRepo? {
            val (host, owner, repo) = parseToTriple(url) ?: return null
            val apiHost = when {
                host.equals("github.com", ignoreCase = true) -> "https://api.github.com"
                else -> "https://$host/api/v3"
            }
            // Reject hosts that obviously aren't GitHub. Conservative deny-list; users with custom
            // GitHub Enterprise hostnames will still work because their hosts won't be on this list.
            if (host.equals("gitlab.com", ignoreCase = true)) return null
            if (host.equals("bitbucket.org", ignoreCase = true)) return null
            return BoundRepo(host = apiHost, owner = owner, repo = repo)
        }

        private fun parseToTriple(url: String): Triple<String, String, String>? {
            HTTPS_RE.matchEntire(url)?.let { m ->
                return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            }
            SSH_RE.matchEntire(url)?.let { m ->
                return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            }
            return null
        }
    }
}
