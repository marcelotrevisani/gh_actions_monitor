package com.example.ghactions.ui

import com.example.ghactions.domain.PullRequest
import com.example.ghactions.domain.PullRequestState
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.repo.PullRequestListState
import com.example.ghactions.repo.PullRequestRepository
import com.example.ghactions.repo.PullRequestWithRun
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Top half of the tool window: a tree of pull requests, each with its latest workflow
 * run as the only child. Selecting a run calls [onRunSelected] so the bottom panel
 * (`RunDetailPanel`) can refresh.
 */
class PullRequestPanel(
    project: Project,
    private val onRunSelected: (Run) -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(PullRequestRepository::class.java)

    private val rootNode = DefaultMutableTreeNode("(root)")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        cellRenderer = PrTreeCellRenderer()
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        isRootVisible = false
        addTreeSelectionListener { e ->
            val node = e.path.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            (node.userObject as? Run)?.let(onRunSelected)
        }
    }

    private val statusLabel = JBLabel("").apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        foreground = UIUtil.getContextHelpForeground()
    }

    private val cardLayout = CardLayout()
    private val cardsPanel = JPanel(cardLayout).apply {
        add(JBScrollPane(tree), CARD_TREE)
        add(statusLabel, CARD_STATUS)
    }

    /** UI-side filter state — applied client-side after Loaded transitions. */
    private var titleQuery: String = ""
    private var branchQuery: String = ""
    private var stateFilter: PullRequestState = PullRequestState.OPEN

    /** Most recent Loaded list, kept around so we can re-render on filter changes without re-fetching. */
    private var lastLoaded: List<PullRequestWithRun> = emptyList()

    private val titleField = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = "Filter by title…"
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            private fun onChange() {
                titleQuery = text.trim()
                applyClientFilters()
            }
        })
    }

    private val branchField = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = "Filter by branch…"
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            private fun onChange() {
                branchQuery = text.trim()
                applyClientFilters()
            }
        })
    }

    private val stateRadioOpen = javax.swing.JRadioButton("Open", true)
    private val stateRadioClosed = javax.swing.JRadioButton("Closed")
    private val stateRadioAll = javax.swing.JRadioButton("All")

    init {
        // Group the three radios so only one is selected at a time.
        val group = javax.swing.ButtonGroup()
        listOf(stateRadioOpen, stateRadioClosed, stateRadioAll).forEach { group.add(it) }
        val radioListener = java.awt.event.ActionListener { e ->
            stateFilter = when (e.source) {
                stateRadioOpen -> PullRequestState.OPEN
                stateRadioClosed -> PullRequestState.CLOSED
                stateRadioAll -> PullRequestState.ALL
                else -> stateFilter
            }
            // State changes hit the API.
            repository.refreshPullRequests(stateFilter)
        }
        stateRadioOpen.addActionListener(radioListener)
        stateRadioClosed.addActionListener(radioListener)
        stateRadioAll.addActionListener(radioListener)
    }

    private val filterPanel: JPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 8, 4, 8)
        add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)).apply {
            add(JBLabel("State:"))
            add(stateRadioOpen)
            add(stateRadioClosed)
            add(stateRadioAll)
        })
        add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)).apply {
            add(JBLabel("Title:"))
            titleField.columns = 24
            add(titleField)
            add(JBLabel("Branch:"))
            branchField.columns = 18
            add(branchField)
        })
    }

    private fun applyClientFilters() {
        val visible = lastLoaded.filter { entry ->
            val titleOk = titleQuery.isEmpty() || entry.pr.title.contains(titleQuery, ignoreCase = true)
            val branchOk = branchQuery.isEmpty() || entry.pr.headRef.contains(branchQuery, ignoreCase = true)
            titleOk && branchOk
        }
        renderEntries(visible)
    }

    private fun renderEntries(entries: List<PullRequestWithRun>) {
        rootNode.removeAllChildren()
        entries.forEach { entry ->
            val prNode = DefaultMutableTreeNode(entry.pr)
            if (entry.latestRun != null) {
                prNode.add(DefaultMutableTreeNode(entry.latestRun))
            } else {
                prNode.add(DefaultMutableTreeNode(NO_RUN_PLACEHOLDER))
            }
            rootNode.add(prNode)
        }
        treeModel.reload()
        expandAll()
        cardLayout.show(cardsPanel, CARD_TREE)
    }

    init {
        val top = JPanel(BorderLayout())
        top.add(buildToolbar().component, BorderLayout.NORTH)
        top.add(filterPanel, BorderLayout.CENTER)
        add(top, BorderLayout.NORTH)
        add(cardsPanel, BorderLayout.CENTER)
        showStatus("Click Refresh to load pull requests.")
        observeRepository()
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Refresh", "Reload pull requests from GitHub", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    repository.refreshPullRequests(stateFilter)
                }
            })
        }
        val tb = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        tb.targetComponent = this
        return tb
    }

    private fun observeRepository() {
        scope.launch {
            repository.pullRequestsState.collect { state -> render(state) }
        }
    }

    private fun render(state: PullRequestListState) {
        when (state) {
            is PullRequestListState.Idle -> showStatus("Click Refresh to load pull requests.")
            is PullRequestListState.Loading -> showStatus("Loading…")
            is PullRequestListState.Loaded -> {
                lastLoaded = state.entries
                applyClientFilters()
            }
            is PullRequestListState.Error -> showStatus(
                "Failed${state.httpStatus?.let { " ($it)" } ?: ""}: ${state.message}"
            )
        }
    }

    private fun showStatus(text: String) {
        statusLabel.text = text
        cardLayout.show(cardsPanel, CARD_STATUS)
    }

    private fun expandAll() {
        for (i in 0 until rootNode.childCount) {
            tree.expandPath(javax.swing.tree.TreePath((rootNode.getChildAt(i) as DefaultMutableTreeNode).path))
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private companion object {
        const val CARD_TREE = "tree"
        const val CARD_STATUS = "status"
        /** Tree-node payload used when a PR has no matching run. */
        const val NO_RUN_PLACEHOLDER = "(no recent runs)"
    }
}

/** Renders PR rows and run rows with status icons. */
private class PrTreeCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode
        when (val payload = node?.userObject) {
            is PullRequest -> {
                val draftBadge = if (payload.isDraft) " [draft]" else ""
                val branch = "[${payload.headRef}]"
                text = "#${payload.number} ${payload.title}$draftBadge $branch"
                icon = AllIcons.Vcs.Vendors.Github
            }
            is Run -> {
                text = "${payload.workflowName} · ${payload.event} · ${humanAge(payload)}"
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

    private fun humanAge(run: Run): String {
        val d = java.time.Duration.between(run.updatedAt, java.time.Instant.now())
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
            d.toHours() < 24 -> "${d.toHours()}h ago"
            else -> "${d.toDays()}d ago"
        }
    }
}
