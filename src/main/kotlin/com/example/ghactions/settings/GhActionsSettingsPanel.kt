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
import com.intellij.openapi.Disposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JPasswordField

/**
 * The Swing form. Only contains UI; persistence wiring happens in [GhActionsConfigurable].
 */
class GhActionsSettingsPanel : Disposable {

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
    private var accountChoices: List<AccountChoice> = buildAccountChoices(ideAccountSource.listAccounts())
    private val accountCombo: JComboBox<AccountChoice> = JComboBox(DefaultComboBoxModel(accountChoices.toTypedArray())).apply {
        selectedIndex = accountChoices.indexOfFirst { it.id == state.preferredAccountId }
            .takeIf { it >= 0 } ?: 0
    }

    // Segmented button references for manual reset
    private var notificationLevelButton: SegmentedButton<String>? = null

    // Lifetime-bound scope for live subscriptions (account list, auth status). Cancelled
    // when the IntelliJ Configurable disposes its component.
    private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        refreshAuthStatus()
        observeIdeAccountChanges()
    }

    private fun buildAccountChoices(accounts: List<IdeAccountInfo>): List<AccountChoice> = buildList {
        add(AccountChoice(id = null, label = "(none — use token below)"))
        accounts.forEach { acct ->
            add(AccountChoice(id = acct.id, label = "${acct.name} (${acct.host})"))
        }
    }

    /**
     * Subscribes to live account changes published by the bundled GitHub plugin's
     * `GHAccountManager.accountsState`. When the user adds or removes an IDE account
     * (e.g. via our *Add / manage GitHub accounts…* button), the dropdown updates
     * without requiring the Settings dialog to be closed and reopened.
     */
    private fun observeIdeAccountChanges() {
        panelScope.launch {
            ideAccountSource.accountsFlow().collect { accounts ->
                rebuildAccountCombo(accounts)
            }
        }
    }

    private fun rebuildAccountCombo(accounts: List<IdeAccountInfo>) {
        val previouslySelectedId = (accountCombo.selectedItem as? AccountChoice)?.id
        accountChoices = buildAccountChoices(accounts)
        accountCombo.model = DefaultComboBoxModel(accountChoices.toTypedArray())
        // Restore selection: prefer what the user had picked, fall back to the persisted setting.
        val targetId = previouslySelectedId ?: state.preferredAccountId
        accountCombo.selectedIndex = accountChoices.indexOfFirst { it.id == targetId }
            .takeIf { it >= 0 } ?: 0
    }

    override fun dispose() {
        panelScope.cancel()
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
        notificationLevelButton?.selectedItem?.let { state.notificationLevel = it }
        if (state != saved) return true
        if (pendingTokenChanged()) return true
        return false
    }

    fun apply() {
        state.preferredAccountId = (accountCombo.selectedItem as? AccountChoice)?.id
        notificationLevelButton?.selectedItem?.let { state.notificationLevel = it }
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
        state.defaultDownloadDir = saved.defaultDownloadDir

        accountCombo.selectedIndex = accountChoices.indexOfFirst { it.id == state.preferredAccountId }
            .takeIf { it >= 0 } ?: 0
        notificationLevelButton?.selectedItem = state.notificationLevel
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
        // Sync the dropdown selection too — the user's *current* pick (not just the persisted
        // value) drives credential resolution, otherwise selecting an IDE account and clicking
        // Test still uses the previously-saved PAT.
        state.preferredAccountId = (accountCombo.selectedItem as? AccountChoice)?.id

        val typed = String(tokenField.password)
        val baseUrl = state.baseUrl
        val preferredAccountId = state.preferredAccountId
        statusLabel.text = "Testing…"
        // Capture modality so the result lands on the EDT *while the settings dialog is open*.
        val modality = ModalityState.stateForComponent(component)

        panelScope.launch(Dispatchers.IO) {
            val token = if (typed.isNotEmpty()) {
                // User typed a fresh token in the field — test that one explicitly,
                // even if an IDE account is also selected.
                typed
            } else {
                val resolver = com.example.ghactions.auth.GitHubAccountResolver(
                    ideSource = ideAccountSource,
                    patLookup = object : com.example.ghactions.auth.PatLookup {
                        override fun getToken(host: String) = patStorage.getToken(host)
                    },
                    preferredAccountId = preferredAccountId
                )
                resolver.resolveAuth(baseUrl)?.token.orEmpty()
            }

            if (token.isEmpty()) {
                ApplicationManager.getApplication().invokeLater(
                    { statusLabel.text = "No credentials found for $baseUrl. Pick an account or enter a token." },
                    modality
                )
                return@launch
            }

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
        }
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
                is com.example.ghactions.auth.AuthSource.IdeAccount -> {
                    val displayName = ideAccountSource.listAccounts()
                        .firstOrNull { it.id == auth.accountId }?.name
                        ?: auth.accountId
                    "IDE account $displayName ($baseUrl)"
                }
            }
            // ModalityState.any() so the EDT update lands while the Settings dialog is
            // modal. Without it, the runnable is queued but never runs — same bug as Test
            // connection in Plan 1.
            ApplicationManager.getApplication().invokeLater(
                { authStatusLabel.text = text },
                ModalityState.any()
            )
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
