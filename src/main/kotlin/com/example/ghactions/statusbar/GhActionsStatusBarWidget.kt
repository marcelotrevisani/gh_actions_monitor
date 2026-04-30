package com.example.ghactions.statusbar

import com.example.ghactions.repo.RepoBinding
import com.example.ghactions.repo.RunListState
import com.example.ghactions.repo.RunRepository
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Status-bar icon summarising the active branch's CI state. Click → focuses the
 * GitHub Actions tool window. Updates on every emission of `RunRepository.runsState`.
 */
class GhActionsStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.IconPresentation {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusBar: StatusBar? = null
    private var observerJob: CJob? = null
    private var summary: StatusSummary = StatusSummary(StatusState.IDLE, "No runs yet")

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val runRepo = project.getService(RunRepository::class.java)
        observerJob = scope.launch {
            runRepo.runsState.collect { state -> recompute(state) }
        }
    }

    override fun dispose() {
        observerJob?.cancel()
        scope.cancel()
        statusBar = null
    }

    private fun recompute(state: RunListState) {
        val all = (state as? RunListState.Loaded)?.runs.orEmpty()
        val branch = project.getService(RepoBinding::class.java).currentBranch
        val matching = if (branch == null) emptyList() else all.filter { it.headBranch == branch }
        // Take the latest run per workflow so the aggregate doesn't double-count old retries.
        val latestPerWorkflow = matching
            .groupBy { it.workflowName }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.updatedAt } }
        summary = StatusAggregator.summarize(latestPerWorkflow)
        statusBar?.updateWidget(WIDGET_ID)
    }

    override fun getIcon(): Icon = when (summary.state) {
        StatusState.IDLE -> AllIcons.RunConfigurations.TestNotRan
        StatusState.RUNNING -> AllIcons.Actions.Play_forward
        StatusState.SUCCESS -> AllIcons.RunConfigurations.TestPassed
        StatusState.FAILURE -> AllIcons.RunConfigurations.TestFailed
    }

    override fun getTooltipText(): String = "GitHub Actions: ${summary.tooltip}"

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent> =
        com.intellij.util.Consumer {
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        }

    companion object {
        const val WIDGET_ID = "GhActionsStatusBarWidget"
        const val TOOL_WINDOW_ID = "GitHubActions"
    }
}
