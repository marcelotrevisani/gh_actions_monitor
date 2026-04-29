package com.example.ghactions.ui

import com.example.ghactions.auth.BundledGithubAccountSource
import com.example.ghactions.auth.GitHubAccountResolver
import com.example.ghactions.auth.PatLookup
import com.example.ghactions.auth.PatStorage
import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.events.AuthChangedListener
import com.example.ghactions.events.RepoBindingChangedListener
import com.example.ghactions.events.Topics
import com.example.ghactions.repo.RepoBinding
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.content.ContentFactory
import javax.swing.JComponent

class GhActionsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val controller = ToolWindowController(project, toolWindow)
        controller.refresh()

        val appBus = ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable)
        appBus.subscribe(Topics.AUTH_CHANGED, AuthChangedListener { controller.refresh() })

        val projectBus = project.messageBus.connect(toolWindow.disposable)
        projectBus.subscribe(Topics.REPO_BINDING_CHANGED, RepoBindingChangedListener { controller.refresh() })
    }

    companion object {
        const val ID = "GitHubActions"
    }
}

private class ToolWindowController(
    private val project: Project,
    private val toolWindow: ToolWindow
) {
    fun refresh() {
        val binding = project.getService(RepoBinding::class.java).current
        val hasCreds = binding != null && hasCredentialsFor(binding.host)

        toolWindow.contentManager.removeAllContents(true)

        val panel: JComponent = when {
            binding == null || !hasCreds -> EmptyStatePanel(project)
            else -> buildRunView()
        }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun buildRunView(): JComponent {
        val detail = RunDetailPanel(project)
        val list = RunListPanel(project) { run -> detail.showRun(run) }
        return OnePixelSplitter(true, 0.35f).apply {
            firstComponent = list
            secondComponent = detail
        }
    }

    private fun hasCredentialsFor(host: String): Boolean {
        val settings = PluginSettings.getInstance().state
        val resolver = GitHubAccountResolver(
            ideSource = BundledGithubAccountSource(),
            patLookup = object : PatLookup {
                override fun getToken(host: String) = PatStorage().getToken(host)
            },
            preferredAccountId = settings.preferredAccountId
        )
        return resolver.resolve(host) != null
    }
}
