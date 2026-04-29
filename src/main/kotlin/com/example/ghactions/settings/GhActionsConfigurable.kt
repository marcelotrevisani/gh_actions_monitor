package com.example.ghactions.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class GhActionsConfigurable : Configurable {
    private var panel: GhActionsSettingsPanel? = null

    override fun getDisplayName(): String = "GitHub Actions Monitor"

    override fun createComponent(): JComponent {
        val p = GhActionsSettingsPanel()
        panel = p
        return p.component
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
        // Notify subscribers (RunRepository, EmptyStatePanel, tool window factory)
        // that credentials may have changed.
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .syncPublisher(com.example.ghactions.events.Topics.AUTH_CHANGED)
            .onAuthChanged()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
