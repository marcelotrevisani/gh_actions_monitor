package com.example.ghactions.settings

import com.example.ghactions.auth.BundledGithubAccountSource
import com.example.ghactions.auth.IdeAccountInfo
import com.example.ghactions.auth.PatStorage
import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.auth.TestConnection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.components.JBTextField
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JPasswordField

/**
 * The Swing form. Only contains UI; persistence wiring happens in [GhActionsConfigurable].
 */
class GhActionsSettingsPanel {

    private val state = PluginSettings.getInstance().state.copy()
    private val patStorage = PatStorage()
    private val ideAccountSource = BundledGithubAccountSource()

    // Token is held in the form only while editing; on apply() it's written to PasswordSafe and cleared from memory.
    private var pendingToken: String? = null

    private val tokenField = JPasswordField(40)
    private val statusLabel = JBLabel(" ")
    private val authStatusLabel = JBLabel(" ").apply {
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }

    // IDE accounts dropdown. The first entry is a sentinel meaning "(none — use token below)".
    private data class AccountChoice(val id: String?, val label: String) {
        override fun toString(): String = label
    }
    private val accountChoices: List<AccountChoice> = buildList {
        add(AccountChoice(id = null, label = "(none — use token below)"))
        ideAccountSource.listAccounts().forEach { acct: IdeAccountInfo ->
            add(AccountChoice(id = acct.id, label = "${acct.id} @ ${acct.host}"))
        }
    }
    private val accountCombo: JComboBox<AccountChoice> = JComboBox(DefaultComboBoxModel(accountChoices.toTypedArray())).apply {
        selectedIndex = accountChoices.indexOfFirst { it.id == state.preferredAccountId }
            .takeIf { it >= 0 } ?: 0
    }

    // Segmented button references for manual reset
    private var notificationLevelButton: SegmentedButton<String>? = null
    private var viewModeButton: SegmentedButton<String>? = null

    init {
        refreshAuthStatus()
    }

    val component: DialogPanel = panel {
        group("Connection") {
            row("Base URL:") {
                val tf: Cell<JBTextField> = textField()
                tf.columns(40)
                    .bindText(state::baseUrl)
                    .comment("Use https://api.github.com for github.com, or https://&lt;host&gt;/api/v3 for GitHub Enterprise Server.")
            }
            row("Use IDE-configured GitHub account:") {
                cell(accountCombo).comment(
                    "Reads accounts from the bundled GitHub plugin. Pick one to skip the token field below."
                )
            }
            row("Personal access token:") {
                cell(tokenField).comment(
                    "Used only when no IDE account is selected above. Stored in the IDE's secure password storage."
                )
            }
            row {
                button("Test connection") { testConnection() }
                cell(statusLabel)
            }
            row {
                button("Add / manage GitHub accounts…") { manageAccounts() }
                    .comment(
                        "Opens IntelliJ Settings → Version Control → GitHub. The bundled GitHub " +
                            "plugin handles the github.com OAuth flow there. Reopen this dialog after " +
                            "adding an account to refresh the dropdown above."
                    )
            }
            row("Currently active:") {
                cell(authStatusLabel)
            }
        }
        group("Behavior") {
            row {
                checkBox("Live polling enabled").bindSelected(state::livePollingEnabled)
            }
            row("Notification level:") {
                notificationLevelButton = segmentedButton(listOf("OFF", "FAILURES_ONLY", "ALL")) { text = it }
                    .apply { selectedItem = state.notificationLevel }
                    .also { btn ->
                        btn.whenItemSelectedFromUi(null) { state.notificationLevel = it }
                    }
            }
            row("Default view mode:") {
                viewModeButton = segmentedButton(listOf("PR_CENTRIC", "TABBED", "TREE")) { text = it }
                    .apply { selectedItem = state.viewMode }
                    .also { btn ->
                        btn.whenItemSelectedFromUi(null) { state.viewMode = it }
                    }
            }
            row("Default download directory:") {
                val tf: Cell<JBTextField> = textField()
                tf.columns(40)
                    .bindText({ state.defaultDownloadDir.orEmpty() }, { v -> state.defaultDownloadDir = v.ifBlank { null } })
            }
        }
    }

    fun isModified(): Boolean {
        val saved = PluginSettings.getInstance().state
        // Sync the combo selection into [state] before comparing.
        state.preferredAccountId = (accountCombo.selectedItem as? AccountChoice)?.id
        // Sync segmented button selections into state before comparing
        notificationLevelButton?.selectedItem?.let { state.notificationLevel = it }
        viewModeButton?.selectedItem?.let { state.viewMode = it }
        if (state != saved) return true
        if (pendingTokenChanged()) return true
        return false
    }

    fun apply() {
        state.preferredAccountId = (accountCombo.selectedItem as? AccountChoice)?.id
        // Sync segmented button selections into state before persisting
        notificationLevelButton?.selectedItem?.let { state.notificationLevel = it }
        viewModeButton?.selectedItem?.let { state.viewMode = it }
        ApplicationManager.getApplication().runWriteAction {
            PluginSettings.getInstance().loadState(state.copy())
        }
        pendingToken?.let { newToken ->
            if (newToken.isBlank()) patStorage.clearToken(state.baseUrl)
            else patStorage.setToken(state.baseUrl, newToken)
            pendingToken = null
            tokenField.text = ""
        }
        refreshAuthStatus()
    }

    fun reset() {
        val saved = PluginSettings.getInstance().state
        // Copy fields back into [state]
        state.baseUrl = saved.baseUrl
        state.preferredAccountId = saved.preferredAccountId
        state.livePollingEnabled = saved.livePollingEnabled
        state.notificationLevel = saved.notificationLevel
        state.viewMode = saved.viewMode
        state.defaultDownloadDir = saved.defaultDownloadDir

        accountCombo.selectedIndex = accountChoices.indexOfFirst { it.id == state.preferredAccountId }
            .takeIf { it >= 0 } ?: 0
        notificationLevelButton?.selectedItem = state.notificationLevel
        viewModeButton?.selectedItem = state.viewMode
        tokenField.text = ""
        pendingToken = null
        statusLabel.text = " "
        component.reset()
    }

    private fun pendingTokenChanged(): Boolean {
        val typed = String(tokenField.password)
        return typed.isNotEmpty().also { pendingToken = if (it) typed else null }
    }

    private fun testConnection() {
        // Flush typed values from the form into [state] so we probe the URL the user actually sees.
        // DialogPanel.apply() pushes UI bindings into bound state without persisting elsewhere.
        component.apply()

        val typed = String(tokenField.password)
        val token = typed.ifEmpty { patStorage.getToken(state.baseUrl) ?: "" }
        if (token.isEmpty()) {
            statusLabel.text = "Enter a token first."
            return
        }
        val baseUrl = state.baseUrl
        statusLabel.text = "Testing…"
        // Capture modality so the result lands on the EDT *while the settings dialog is open*.
        val modality = ModalityState.stateForComponent(component)
        Thread {
            val result = try {
                TestConnection.probe(baseUrl, token)
            } catch (t: Throwable) {
                LOG.warn("Test connection threw unexpectedly", t)
                TestConnection.Result.Failure(null, t.message ?: t::class.java.simpleName)
            }
            ApplicationManager.getApplication().invokeLater({
                statusLabel.text = when (result) {
                    is TestConnection.Result.Success -> "Connected as @${result.login}"
                    is TestConnection.Result.Failure -> "Failed: ${result.message}"
                }
                refreshAuthStatus()
            }, modality)
        }.apply { name = "GhActions-TestConnection"; isDaemon = true }.start()
    }

    /**
     * Resolve current credentials for the configured base URL and update [authStatusLabel].
     * Self-launching coroutine on `Dispatchers.IO`; settles within ~10ms.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun refreshAuthStatus() {
        val baseUrl = state.baseUrl
        GlobalScope.launch(Dispatchers.IO) {
            val resolver = com.example.ghactions.auth.GitHubAccountResolver(
                ideSource = ideAccountSource,
                patLookup = object : com.example.ghactions.auth.PatLookup {
                    override fun getToken(host: String) = patStorage.getToken(host)
                },
                preferredAccountId = state.preferredAccountId
            )
            val resolved = resolver.resolveAuth(baseUrl)
            val text = when (val auth = resolved?.auth) {
                null -> "nothing — configure above"
                is com.example.ghactions.auth.AuthSource.Pat -> "PAT ($baseUrl)"
                is com.example.ghactions.auth.AuthSource.IdeAccount -> "IDE account ${auth.accountId} ($baseUrl)"
            }
            ApplicationManager.getApplication().invokeLater {
                authStatusLabel.text = text
            }
        }
    }

    private fun manageAccounts() {
        // ShowSettingsUtil.showSettingsDialog(Project, String) accepts the configurable's
        // display name. "GitHub" matches the bundled plugin's display name in IDEA 2024.3.
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
            ?: com.intellij.openapi.project.ProjectManager.getInstance().defaultProject
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, "GitHub")
    }

    companion object {
        private val LOG = Logger.getInstance(GhActionsSettingsPanel::class.java)
    }
}
