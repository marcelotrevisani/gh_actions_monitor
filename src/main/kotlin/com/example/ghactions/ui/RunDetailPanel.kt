package com.example.ghactions.ui

import com.example.ghactions.domain.Job
import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.domain.Step
import com.example.ghactions.repo.JobsState
import com.example.ghactions.repo.LogState
import com.example.ghactions.repo.RunRepository
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
class RunDetailPanel(project: Project) : JPanel(BorderLayout()), Disposable {

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
                is Job -> {
                    pendingStepFocus = null
                    showJobLogs(payload)
                }
                is Step -> {
                    val parentJob = (node.parent as? DefaultMutableTreeNode)?.userObject as? Job
                        ?: return@addTreeSelectionListener
                    pendingStepFocus = StepFocus(parentJob.id, payload.number, payload.name)
                    showJobLogs(parentJob)
                }
                else -> Unit
            }
        }
        isRootVisible = false
    }

    private data class StepFocus(val jobId: JobId, val stepNumber: Int, val stepName: String)
    private var pendingStepFocus: StepFocus? = null

    private val logViewer = LogViewerPanel()

    private val emptyMessage = JBLabel("Select a run to see its jobs.").apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = UIUtil.getContextHelpForeground()
    }

    private val splitter = OnePixelSplitter(true, 0.4f).apply {
        firstComponent = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        secondComponent = logViewer
    }

    private var currentRunFlowJob: CJob? = null
    private var currentLogFlowJob: CJob? = null

    init {
        border = JBUI.Borders.empty()
        add(splitter, BorderLayout.CENTER)
    }

    fun showRun(run: Run) {
        rootNode.userObject = run
        rootNode.removeAllChildren()
        treeModel.reload()
        repository.refreshJobs(run.id)
        currentRunFlowJob?.cancel()
        currentRunFlowJob = scope.launch {
            repository.jobsState(run.id).collect { state -> renderJobs(state) }
        }
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
                state.jobs.forEach { job ->
                    val jobNode = DefaultMutableTreeNode(job)
                    job.steps.forEach { step -> jobNode.add(DefaultMutableTreeNode(step)) }
                    rootNode.add(jobNode)
                }
                treeModel.reload()
                expandAllJobs()
            }
            is JobsState.Error -> {
                rootNode.removeAllChildren()
                rootNode.add(DefaultMutableTreeNode("Failed: ${state.message}"))
                treeModel.reload()
            }
        }
    }

    private fun showJobLogs(job: Job) {
        repository.refreshLogs(job.id)
        currentLogFlowJob?.cancel()
        currentLogFlowJob = scope.launch {
            repository.logsState(job.id).collect { state ->
                when (state) {
                    is LogState.Idle -> logViewer.clear()
                    is LogState.Loading -> logViewer.setText("(loading logs…)")
                    is LogState.Loaded -> {
                        logViewer.setText(state.text)
                        // If the user clicked a step, drill the viewer into that section
                        // once the underlying log is loaded. Skip when the focus is for
                        // a different job (the user navigated away mid-load).
                        val focus = pendingStepFocus
                        if (focus != null && focus.jobId == job.id) {
                            logViewer.showStep(focus.stepNumber, focus.stepName)
                        }
                    }
                    is LogState.Error -> logViewer.setText("Failed${state.httpStatus?.let { " ($it)" } ?: ""}: ${state.message}")
                }
            }
        }
    }

    private fun expandAllJobs() {
        for (i in 0 until rootNode.childCount) {
            tree.expandPath(TreePath((rootNode.getChildAt(i) as DefaultMutableTreeNode).path))
        }
    }

    override fun dispose() {
        currentRunFlowJob?.cancel()
        currentLogFlowJob?.cancel()
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
