package com.example.ghactions.ui

import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ToolWindowFactoryTest : BasePlatformTestCase() {

    fun testToolWindowRegistered() {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(GhActionsToolWindowFactory.ID)
        assertNotNull("Tool window 'GitHubActions' must be registered by plugin.xml", tw)
    }

    fun testToolWindowFactoryCreatesContentWithoutThrowing() {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(GhActionsToolWindowFactory.ID)!!
        GhActionsToolWindowFactory().createToolWindowContent(project, tw)
        assertTrue(tw.contentManager.contentCount >= 1)
    }
}
