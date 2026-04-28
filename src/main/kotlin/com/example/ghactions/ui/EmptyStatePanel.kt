package com.example.ghactions.ui

import com.example.ghactions.auth.BundledGithubAccountSource
import com.example.ghactions.auth.GitHubAccountResolver
import com.example.ghactions.auth.PatLookup
import com.example.ghactions.auth.PatStorage
import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.events.AuthChangedListener
import com.example.ghactions.events.BoundRepo
import com.example.ghactions.events.RepoBindingChangedListener
import com.example.ghactions.events.Topics
import com.example.ghactions.repo.RepoBinding
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * The only UI panel in Plan 1. Renders one of three empty states:
 *  1. No GitHub remote in this project's git config.
 *  2. Git remote is GitHub-shaped but no credentials are configured.
 *  3. Both present — show "Connected to <owner>/<repo>".
 *  4. (Future plans) Will replace state 3 with the actual run list.
 *
 * Responds to both [Topics.REPO_BINDING_CHANGED] and [Topics.AUTH_CHANGED].
 */
class EmptyStatePanel(private val project: Project) : JPanel() {

    private val title = JBLabel().apply { font = font.deriveFont(font.size + 4f) }
    private val detail = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val actionLink = ActionLink("Open Settings…") {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "GitHub Actions Monitor")
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(20)
        add(title)
        add(Box.createVerticalStrut(8))
        add(detail)
        add(Box.createVerticalStrut(12))
        add(actionLink)

        refresh()

        val conn = project.messageBus.connect()
        conn.subscribe(Topics.REPO_BINDING_CHANGED, RepoBindingChangedListener { refresh() })
        conn.subscribe(Topics.AUTH_CHANGED, AuthChangedListener { refresh() })
    }

    private fun refresh() {
        val binding = project.getService(RepoBinding::class.java).current
        when {
            binding == null -> renderNoRepo()
            !hasCredentials(binding) -> renderNoCreds(binding)
            else -> renderConnected(binding)
        }
        revalidate()
        repaint()
    }

    private fun renderNoRepo() {
        title.text = "No GitHub repo detected"
        detail.text = "<html>This project's git remote isn't a GitHub repo (or the project has no git remotes).</html>"
        actionLink.isVisible = false
    }

    private fun renderNoCreds(b: BoundRepo) {
        title.text = "Connect to ${b.owner}/${b.repo}"
        detail.text = "<html>No credentials configured for ${b.host}.</html>"
        actionLink.isVisible = true
    }

    private fun renderConnected(b: BoundRepo) {
        title.text = "Connected to ${b.owner}/${b.repo}"
        detail.text = "<html>Run data will appear here once Plan 2 lands.</html>"
        actionLink.isVisible = false
    }

    private fun hasCredentials(b: BoundRepo): Boolean {
        val settings = PluginSettings.getInstance().state
        val resolver = GitHubAccountResolver(
            ideSource = BundledGithubAccountSource(),
            patLookup = object : PatLookup {
                override fun getToken(host: String) = PatStorage().getToken(host)
            },
            preferredAccountId = settings.preferredAccountId
        )
        return resolver.resolve(b.host) != null
    }
}
