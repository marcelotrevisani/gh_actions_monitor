package com.example.ghactions.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class GhActionsStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = GhActionsStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "GitHub Actions Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = GhActionsStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: com.intellij.openapi.wm.StatusBar): Boolean = true
}
