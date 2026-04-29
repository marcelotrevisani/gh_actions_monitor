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
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.content.Content
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

        val coordinator = project.getService(com.example.ghactions.polling.PollingCoordinator::class.java)
        coordinator.setToolWindowVisible(toolWindow.isVisible)
        coordinator.start()

        val listenerBus = project.messageBus.connect(toolWindow.disposable)
        listenerBus.subscribe(
            com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
            object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
                override fun toolWindowShown(window: com.intellij.openapi.wm.ToolWindow) {
                    if (window.id == ID) coordinator.setToolWindowVisible(true)
                }

                override fun stateChanged(manager: com.intellij.openapi.wm.ToolWindowManager) {
                    // Hidden state is reported here, not via toolWindowShown — sample isVisible
                    // for our window each transition.
                    val tw = manager.getToolWindow(ID) ?: return
                    coordinator.setToolWindowVisible(tw.isVisible)
                }
            }
        )
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

        // `removeAllContents(true)` disposes each Content and any Disposables registered as
        // children of that Content's disposer. Our panels (RunListPanel, RunDetailPanel) are
        // hooked up via `Disposer.register(content, panel)` below — that's the chain that
        // cancels their coroutine scopes and releases editors. Without this wiring, every
        // refresh leaks a copy of those panels and the IDE shutdown logs flag the leak.
        toolWindow.contentManager.removeAllContents(true)

        val pair: Pair<JComponent, List<com.intellij.openapi.Disposable>> = when {
            binding == null || !hasCreds -> EmptyStatePanel(project) to emptyList()
            else -> buildRunView()
        }
        val panel = pair.first
        val disposables = pair.second
        val content: Content = ContentFactory.getInstance().createContent(panel, "", false)
        disposables.forEach { Disposer.register(content, it) }
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Returns the run-view root component plus the Disposable instances that need to be released
     * when the tool window content is removed. The list is consumed in [refresh].
     */
    private fun buildRunView(): Pair<JComponent, List<com.intellij.openapi.Disposable>> {
        val detail = RunDetailPanel(project)
        val list = PullRequestPanel(project) { run -> detail.showRun(run) }
        val splitter = OnePixelSplitter(true, 0.35f).apply {
            firstComponent = list
            secondComponent = detail
        }
        return splitter to listOf(list, detail)
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
