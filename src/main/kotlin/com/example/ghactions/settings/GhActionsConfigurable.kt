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
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
