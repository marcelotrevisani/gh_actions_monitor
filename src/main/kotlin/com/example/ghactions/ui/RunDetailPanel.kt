package com.example.ghactions.ui

import com.example.ghactions.domain.Job
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.domain.Step
import com.example.ghactions.repo.JobsState
import com.example.ghactions.repo.LogState
import com.example.ghactions.repo.RunRepository
import com.example.ghactions.repo.friendlyApiError
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Bottom half of the tool window: jobs/steps tree on top, log viewer below.
 * Plan 2 implements the *Logs* sub-view only — annotations/summary/artifacts tabs
 * are added in later plans.
 */
class RunDetailPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(RunRepository::class.java)

    private val rootNode = DefaultMutableTreeNode("(no run selected)")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        cellRenderer = JobStepCellRenderer()
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        addTreeSelectionListener { e ->
            val node = e.path.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val payload = node.userObject) {
                is Job -> showJobLogs(payload)
                is Step -> {
                    val parentJob = (node.parent as? DefaultMutableTreeNode)?.userObject as? Job
                        ?: return@addTreeSelectionListener
                    val run = rootNode.userObject as? Run ?: return@addTreeSelectionListener
                    showStepLogs(run, parentJob, payload)
                }
                else -> Unit
            }
        }
        isRootVisible = false
    }

    private val logViewer = LogViewerPanel()
    private val summaryPanel = SummaryPanel(project)
    private val annotationsPanel = AnnotationsPanel(project)
    private val artifactsPanel = ArtifactsPanel(project)

    private val detailTabs = com.intellij.ui.components.JBTabbedPane().apply {
        addTab("Logs", logViewer)
        addTab("Summary", summaryPanel)
        addTab("Annotations", annotationsPanel)
        addTab("Artifacts", artifactsPanel)
    }

    private val emptyMessage = JBLabel("Select a run to see its jobs.").apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = UIUtil.getContextHelpForeground()
    }

    private val splitter = OnePixelSplitter(true, 0.4f).apply {
        firstComponent = JPanel(BorderLayout()).apply {
            add(buildTreeToolbar().component, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        secondComponent = detailTabs
    }

    private var currentRunFlowJob: CJob? = null
    private var currentLogFlowJob: CJob? = null

    init {
        border = JBUI.Borders.empty()
        add(splitter, BorderLayout.CENTER)
        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    tree.selectionPath = path
                    openSelectionInNewWindow()
                    e.consume()
                }
            }
        })
    }

    fun showRun(run: Run) {
        rootNode.userObject = run
        rootNode.removeAllChildren()
        treeModel.reload()
        // Clear the log viewer too — otherwise the previously-selected step's content
        // bleeds across run switches until the user clicks a step in the new run.
        currentLogFlowJob?.cancel()
        currentLogFlowJob = null
        logViewer.clear()
        repository.refreshJobs(run.id)
        currentRunFlowJob?.cancel()
        currentRunFlowJob = scope.launch {
            repository.jobsState(run.id).collect { state -> renderJobs(state) }
        }
        summaryPanel.showRun(run.id)
        annotationsPanel.showRun(run.id)
        artifactsPanel.showRun(run.id)
    }

    /**
     * Reset the bottom pane to its empty state. Called when the user selects a PR row
     * (no run yet) or otherwise clears the selection in the upper tree.
     */
    fun clear() {
        currentRunFlowJob?.cancel()
        currentRunFlowJob = null
        currentLogFlowJob?.cancel()
        currentLogFlowJob = null
        rootNode.userObject = "(no run selected)"
        rootNode.removeAllChildren()
        treeModel.reload()
        logViewer.clear()
        summaryPanel.clear()
        annotationsPanel.clear()
        artifactsPanel.clear()
    }

    private fun renderJobs(state: JobsState) {
        when (state) {
            is JobsState.Idle, is JobsState.Loading -> {
                rootNode.removeAllChildren()
                rootNode.add(DefaultMutableTreeNode("(loading jobs…)"))
                treeModel.reload()
            }
            is JobsState.Loaded -> {
                rootNode.removeAllChildren()
                // Sort jobs alphabetically by name — matrix runs (e.g. "build (linux)",
                // "build (mac)", "build (windows)") group together, easier to scan than
                // GitHub's wire order which depends on scheduling.
                state.jobs.sortedBy { it.name }.forEach { job ->
                    val jobNode = DefaultMutableTreeNode(job)
                    job.steps.forEach { step -> jobNode.add(DefaultMutableTreeNode(step)) }
                    rootNode.add(jobNode)
                }
                treeModel.reload()
                expandAllJobs()
            }
            is JobsState.Error -> {
                rootNode.removeAllChildren()
                rootNode.add(DefaultMutableTreeNode(friendlyApiError(state.httpStatus, state.message)))
                treeModel.reload()
            }
        }
    }

    private fun showJobLogs(job: Job) {
        repository.refreshLogs(job.id)
        currentLogFlowJob?.cancel()
        currentLogFlowJob = scope.launch {
            repository.logsState(job.id).collect { state ->
                renderLogState(state, statusHint = "")
            }
        }
    }

    private fun showStepLogs(run: Run, job: Job, step: Step) {
        repository.refreshStepLog(
            runId = run.id,
            jobId = job.id,
            jobName = job.name,
            stepNumber = step.number,
            stepName = step.name
        )
        currentLogFlowJob?.cancel()
        currentLogFlowJob = scope.launch {
            repository.logsState(job.id, stepNumber = step.number).collect { state ->
                renderLogState(state, statusHint = "Step ${step.number} · ${step.name}")
            }
        }
    }

    private fun renderLogState(state: LogState, statusHint: String) {
        when (state) {
            is LogState.Idle -> logViewer.clear()
            is LogState.Loading -> { logViewer.setText("(loading logs…)"); logViewer.setStatus(statusHint) }
            is LogState.Loaded -> { logViewer.setText(state.text); logViewer.setStatus(statusHint) }
            is LogState.Error -> {
                logViewer.setText(friendlyApiError(state.httpStatus, state.message))
                logViewer.setStatus("")
            }
        }
    }

    private fun expandAllJobs() {
        for (i in 0 until rootNode.childCount) {
            tree.expandPath(TreePath((rootNode.getChildAt(i) as DefaultMutableTreeNode).path))
        }
    }

    private fun openSelectionInNewWindow() {
        val node = tree.lastSelectedPathComponent as? javax.swing.tree.DefaultMutableTreeNode ?: return
        val payload = node.userObject
        val run = rootNode.userObject as? com.example.ghactions.domain.Run ?: return
        when (payload) {
            is com.example.ghactions.domain.Job -> openJobInNewWindow(run, payload)
            is com.example.ghactions.domain.Step -> {
                val parentJob = (node.parent as? javax.swing.tree.DefaultMutableTreeNode)?.userObject
                    as? com.example.ghactions.domain.Job ?: return
                openStepInNewWindow(run, parentJob, payload)
            }
            else -> Unit
        }
    }

    private fun openJobInNewWindow(run: com.example.ghactions.domain.Run, job: com.example.ghactions.domain.Job) {
        repository.refreshLogs(job.id)
        val window = LogWindow(
            project = project,
            windowTitle = "${run.workflowName} · ${job.name} (run #${run.id})",
            statusHint = "Job · ${job.name}",
            source = repository.logsState(job.id)
        )
        window.show()
    }

    private fun openStepInNewWindow(
        run: com.example.ghactions.domain.Run,
        job: com.example.ghactions.domain.Job,
        step: com.example.ghactions.domain.Step
    ) {
        repository.refreshStepLog(
            runId = run.id,
            jobId = job.id,
            jobName = job.name,
            stepNumber = step.number,
            stepName = step.name
        )
        val window = LogWindow(
            project = project,
            windowTitle = "${run.workflowName} · ${job.name} · ${step.name} (run #${run.id})",
            statusHint = "Step ${step.number} · ${step.name}",
            source = repository.logsState(job.id, stepNumber = step.number)
        )
        window.show()
    }

    private fun handleResult(result: com.example.ghactions.repo.ActionResult, success: String) {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        when (result) {
            is com.example.ghactions.repo.ActionResult.Success -> app.invokeLater {
                com.intellij.openapi.ui.Messages.showInfoMessage(project, success, "GitHub Actions")
            }
            is com.example.ghactions.repo.ActionResult.Error -> app.invokeLater {
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    com.example.ghactions.repo.friendlyApiError(result.httpStatus, result.message),
                    "GitHub Actions"
                )
            }
        }
    }

    private fun buildTreeToolbar(): com.intellij.openapi.actionSystem.ActionToolbar {
        val group = com.intellij.openapi.actionSystem.DefaultActionGroup().apply {
            add(object : com.intellij.openapi.actionSystem.AnAction(
                "Open in New Window",
                "Pop the selected job's or step's log into a separate window",
                com.intellij.icons.AllIcons.Actions.MoveToWindow
            ) {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val payload = (tree.lastSelectedPathComponent as? javax.swing.tree.DefaultMutableTreeNode)?.userObject
                    e.presentation.isEnabled = payload is com.example.ghactions.domain.Job
                        || payload is com.example.ghactions.domain.Step
                }
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    openSelectionInNewWindow()
                }
            })
            add(object : com.intellij.openapi.actionSystem.AnAction(
                "Expand All",
                "Expand every job in the tree",
                com.intellij.icons.AllIcons.Actions.Expandall
            ) {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    e.presentation.isEnabled = rootNode.childCount > 0
                }
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    expandAllJobs()
                }
            })
            add(object : com.intellij.openapi.actionSystem.AnAction(
                "Collapse All",
                "Collapse every job in the tree",
                com.intellij.icons.AllIcons.Actions.Collapseall
            ) {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    e.presentation.isEnabled = rootNode.childCount > 0
                }
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    collapseAllJobs()
                }
            })
            addSeparator()
            add(object : com.intellij.openapi.actionSystem.AnAction(
                "Cancel Run",
                "Cancel the currently running workflow",
                com.intellij.icons.AllIcons.Actions.Suspend
            ) {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val run = rootNode.userObject as? com.example.ghactions.domain.Run
                    e.presentation.isEnabled = run != null
                        && (run.status == com.example.ghactions.domain.RunStatus.IN_PROGRESS
                            || run.status == com.example.ghactions.domain.RunStatus.QUEUED)
                }
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val run = rootNode.userObject as? com.example.ghactions.domain.Run ?: return
                    val ok = com.intellij.openapi.ui.Messages.showYesNoDialog(
                        project,
                        "Cancel run #${run.runNumber} (${run.workflowName})?",
                        "Cancel run",
                        com.intellij.openapi.ui.Messages.getQuestionIcon()
                    )
                    if (ok != com.intellij.openapi.ui.Messages.YES) return
                    scope.launch {
                        val result = repository.cancelRun(run.id)
                        handleResult(result, success = "Cancellation requested.")
                    }
                }
            })
            add(object : com.intellij.openapi.actionSystem.AnAction(
                "Re-run All Jobs",
                "Re-run every job in this workflow run",
                com.intellij.icons.AllIcons.Actions.Restart
            ) {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val run = rootNode.userObject as? com.example.ghactions.domain.Run
                    e.presentation.isEnabled = run != null
                        && run.status == com.example.ghactions.domain.RunStatus.COMPLETED
                }
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val run = rootNode.userObject as? com.example.ghactions.domain.Run ?: return
                    scope.launch {
                        val result = repository.rerunRun(run.id)
                        handleResult(result, success = "Re-run requested.")
                    }
                }
            })
            add(object : com.intellij.openapi.actionSystem.AnAction(
                "Re-run Failed Jobs",
                "Re-run only the jobs whose conclusion wasn't success",
                com.intellij.icons.AllIcons.Actions.Rerun
            ) {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val run = rootNode.userObject as? com.example.ghactions.domain.Run
                    e.presentation.isEnabled = run != null
                        && run.status == com.example.ghactions.domain.RunStatus.COMPLETED
                        && run.conclusion != null
                        && run.conclusion != com.example.ghactions.domain.RunConclusion.SUCCESS
                }
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val run = rootNode.userObject as? com.example.ghactions.domain.Run ?: return
                    scope.launch {
                        val result = repository.rerunFailedJobs(run.id)
                        handleResult(result, success = "Re-run of failed jobs requested.")
                    }
                }
            })
        }
        val tb = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar(com.intellij.openapi.actionSystem.ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        tb.targetComponent = this
        return tb
    }

    private fun collapseAllJobs() {
        for (i in 0 until rootNode.childCount) {
            tree.collapsePath(TreePath((rootNode.getChildAt(i) as DefaultMutableTreeNode).path))
        }
    }

    override fun dispose() {
        currentRunFlowJob?.cancel()
        currentLogFlowJob?.cancel()
        summaryPanel.dispose()
        annotationsPanel.dispose()
        artifactsPanel.dispose()
        scope.cancel()
    }
}

private class JobStepCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode
        when (val payload = node?.userObject) {
            is Job -> {
                text = payload.name
                icon = iconFor(payload.status, payload.conclusion)
            }
            is Step -> {
                text = "${payload.number}. ${payload.name}"
                icon = iconFor(payload.status, payload.conclusion)
            }
            else -> {
                text = node?.userObject?.toString() ?: ""
                icon = null
            }
        }
        return this
    }

    private fun iconFor(status: RunStatus, conclusion: RunConclusion?) = when {
        status == RunStatus.IN_PROGRESS || status == RunStatus.QUEUED -> AllIcons.Actions.Play_forward
        conclusion == RunConclusion.SUCCESS -> AllIcons.RunConfigurations.TestPassed
        conclusion == RunConclusion.FAILURE -> AllIcons.RunConfigurations.TestFailed
        conclusion == RunConclusion.CANCELLED -> AllIcons.Actions.Cancel
        else -> AllIcons.RunConfigurations.TestNotRan
    }
}
