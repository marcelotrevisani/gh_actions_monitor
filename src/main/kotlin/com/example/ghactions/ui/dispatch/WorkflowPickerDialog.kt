package com.example.ghactions.ui.dispatch

import com.example.ghactions.domain.Workflow
import com.example.ghactions.domain.WorkflowId
import com.example.ghactions.repo.RepoBinding
import com.example.ghactions.repo.RunRepository
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JComboBox

/**
 * Modal picker for workflow_dispatch. Caller awaits `showAndGet()`; on OK, query
 * [chosenWorkflowId] and [chosenRef] and pass to `RunRepository.dispatchWorkflow`.
 *
 * Loads workflows asynchronously after `init()`; the OK button is disabled until at
 * least one is picked. If the load returns nothing (no creds, network error), the
 * combo shows "(no active workflows)" and OK stays disabled.
 */
class WorkflowPickerDialog(private val project: Project) : DialogWrapper(project, true) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val combo = JComboBox<WorkflowChoice>(DefaultComboBoxModel<WorkflowChoice>())
    private val refField = JBTextField()
    private val statusLabel = JBLabel(" ")

    /** The workflow id selected when the user clicked OK, or null if the dialog was cancelled. */
    val chosenWorkflowId: WorkflowId?
        get() = (combo.selectedItem as? WorkflowChoice)?.id

    /** The ref string entered when the user clicked OK. */
    val chosenRef: String
        get() = refField.text.trim()

    init {
        title = "Run Workflow"
        refField.text = project.getService(RepoBinding::class.java).currentBranch ?: "main"
        init()
        scope.launch { loadWorkflows() }
    }

    override fun createCenterPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Workflow:", combo)
            .addLabeledComponent("Ref:", refField)
            .addComponent(statusLabel)
            .panel
        panel.border = JBUI.Borders.empty(8, 12)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val choice = combo.selectedItem as? WorkflowChoice
        if (choice == null || choice.id == null) {
            return ValidationInfo("Pick a workflow.", combo)
        }
        if (refField.text.isBlank()) return ValidationInfo("Ref is required.", refField)
        return null
    }

    private suspend fun loadWorkflows() {
        statusLabel.text = "Loading workflows…"
        val workflows = project.getService(RunRepository::class.java).listWorkflows()
            .filter { it.state == "active" }
        ApplicationManager.getApplication().invokeLater {
            renderChoices(workflows)
        }
    }

    private fun renderChoices(workflows: List<Workflow>) {
        val model = combo.model as DefaultComboBoxModel<WorkflowChoice>
        model.removeAllElements()
        if (workflows.isEmpty()) {
            model.addElement(WorkflowChoice(null, "(no active workflows)"))
            statusLabel.text = "No active workflows in this repo."
        } else {
            workflows.forEach { model.addElement(WorkflowChoice(it.id, "${it.name} (${it.path})")) }
            statusLabel.text = " "
        }
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }

    private data class WorkflowChoice(val id: WorkflowId?, val label: String) {
        override fun toString(): String = label
    }
}
