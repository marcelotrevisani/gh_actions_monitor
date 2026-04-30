package com.example.ghactions.auth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Non-secret persisted plugin state. Tokens live exclusively in [PatStorage] (PasswordSafe);
 * this class must never grow a token field.
 */
@Service(Service.Level.APP)
@State(name = "GhActionsSettings", storages = [Storage("ghActionsMonitor.xml")])
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        var baseUrl: String = "https://api.github.com",
        var preferredAccountId: String? = null,
        var livePollingEnabled: Boolean = true,
        var notificationLevel: String = "FAILURES_ONLY",
        var defaultDownloadDir: String? = null
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
