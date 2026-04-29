package com.example.ghactions.settings

import com.example.ghactions.events.AuthChangedListener
import com.example.ghactions.events.Topics
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AuthChangedPublishTest : BasePlatformTestCase() {

    fun testApplyPublishesAuthChanged() {
        var received = false
        val conn = ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
        conn.subscribe(Topics.AUTH_CHANGED, AuthChangedListener { received = true })

        // Simulate the configurable's apply() — instantiate and call.
        val configurable = GhActionsConfigurable()
        configurable.createComponent()
        configurable.apply()
        configurable.disposeUIResources()

        assertTrue("Expected AUTH_CHANGED to fire when settings.apply() is called", received)
    }
}
