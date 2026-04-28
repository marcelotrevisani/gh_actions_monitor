package com.example.ghactions.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * `BasePlatformTestCase` uses a *light* test fixture that does NOT load this project's
 * `plugin.xml` extensions, so we cannot directly verify the `<toolWindow>` registration
 * here — that's Task 14's manual `runIde` smoke test.
 *
 * What we *can* verify:
 *  - The factory's published ID constant matches what `plugin.xml` registers.
 *  - The panel the factory builds (`EmptyStatePanel`) constructs without throwing,
 *    which exercises its `RepoBinding` service lookup, `MessageBus` subscriptions,
 *    and `PluginSettings` access — historically the failure-prone bits.
 */
class ToolWindowFactoryTest : BasePlatformTestCase() {

    fun testIdConstant() {
        assertEquals("GitHubActions", GhActionsToolWindowFactory.ID)
    }

    fun testEmptyStatePanelConstructsWithoutThrowing() {
        val panel = EmptyStatePanel(project)
        assertNotNull(panel)
    }
}
