package com.example.ghactions.ui

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.repo.RunListState
import com.example.ghactions.repo.RunRepository
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
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
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
import java.awt.FlowLayout
import java.time.Duration
import java.time.Instant
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/**
 * Top half of the tool window: the list of recent runs for the bound repo, with a refresh
 * action above. Selection changes call back via [onRunSelected].
 */
class RunListPanel(
    project: Project,
    private val onRunSelected: (Run) -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(RunRepository::class.java)

    private val listModel = DefaultListModel<Run>()
    private val list = JBList(listModel).apply {
        cellRenderer = RunCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                selectedValue?.let(onRunSelected)
            }
        }
    }

    private val statusLabel = JBLabel("").apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        foreground = UIUtil.getContextHelpForeground()
    }

    private val cardLayout = CardLayout()
    private val cardsPanel = JPanel(cardLayout).apply {
        add(JBScrollPane(list), CARD_LIST)
        add(statusLabel, CARD_STATUS)
    }

    init {
        add(buildToolbar().component, BorderLayout.NORTH)
        add(cardsPanel, BorderLayout.CENTER)
        showStatus("Click Refresh to load runs.")
        observeRepository()
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Refresh", "Reload runs from GitHub", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    repository.refreshRuns()
                }
            })
        }
        val tb = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        tb.targetComponent = this
        return tb
    }

    private fun observeRepository() {
        scope.launch {
            repository.runsState.collect { state -> render(state) }
        }
    }

    private fun render(state: RunListState) {
        when (state) {
            is RunListState.Idle -> showStatus("Click Refresh to load runs.")
            is RunListState.Loading -> showStatus("Loading…")
            is RunListState.Loaded -> {
                listModel.clear()
                state.runs.forEach { listModel.addElement(it) }
                cardLayout.show(cardsPanel, CARD_LIST)
            }
            is RunListState.Error -> showStatus(
                "Failed${state.httpStatus?.let { " ($it)" } ?: ""}: ${state.message}"
            )
        }
    }

    private fun showStatus(text: String) {
        statusLabel.text = text
        cardLayout.show(cardsPanel, CARD_STATUS)
    }

    override fun dispose() {
        scope.cancel()
    }

    private companion object {
        const val CARD_LIST = "list"
        const val CARD_STATUS = "status"
    }
}

/** Single-row renderer: status icon · workflow name · branch · actor · age. */
private class RunCellRenderer : ListCellRenderer<Run> {
    private val template = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        border = JBUI.Borders.empty(4, 8)
    }
    private val icon = JBLabel()
    private val workflow = JBLabel().apply { font = font.deriveFont(java.awt.Font.BOLD) }
    private val branch = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val age = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val actor = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        template.add(icon)
        template.add(workflow)
        template.add(branch)
        template.add(actor)
        template.add(age)
    }

    override fun getListCellRendererComponent(
        list: JList<out Run>?,
        value: Run?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val run = value ?: return template
        icon.icon = iconFor(run)
        workflow.text = run.workflowName + (run.displayTitle?.let { " — $it" } ?: "")
        branch.text = run.headBranch?.let { "[$it]" } ?: ""
        actor.text = run.actorLogin?.let { "@$it" } ?: ""
        age.text = humanAge(run.updatedAt)
        template.background = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
        return template
    }

    private fun iconFor(run: Run) = when {
        run.status == RunStatus.IN_PROGRESS || run.status == RunStatus.QUEUED || run.status == RunStatus.PENDING ->
            AllIcons.Actions.Play_forward
        run.conclusion == RunConclusion.SUCCESS -> AllIcons.RunConfigurations.TestPassed
        run.conclusion == RunConclusion.FAILURE -> AllIcons.RunConfigurations.TestFailed
        run.conclusion == RunConclusion.CANCELLED -> AllIcons.Actions.Cancel
        else -> AllIcons.RunConfigurations.TestNotRan
    }

    private fun humanAge(instant: Instant): String {
        val d = Duration.between(instant, Instant.now())
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
            d.toHours() < 24 -> "${d.toHours()}h ago"
            else -> "${d.toDays()}d ago"
        }
    }
}
