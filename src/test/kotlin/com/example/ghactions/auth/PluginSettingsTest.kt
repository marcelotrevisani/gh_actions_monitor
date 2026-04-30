package com.example.ghactions.auth

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class PluginSettingsTest : BasePlatformTestCase() {

    fun testDefaults() {
        val s = PluginSettings()
        assertEquals("https://api.github.com", s.state.baseUrl)
        assertNull(s.state.preferredAccountId)
        assertTrue(s.state.livePollingEnabled)
        assertEquals("FAILURES_ONLY", s.state.notificationLevel)
    }

    fun testRoundTrip() {
        val original = PluginSettings()
        original.state.apply {
            baseUrl = "https://ghe.example.com/api/v3"
            preferredAccountId = "acct-7"
            livePollingEnabled = false
            notificationLevel = "OFF"
            defaultDownloadDir = "/tmp/dl"
        }

        val element = XmlSerializer.serialize(original.state)
        val restored = PluginSettings()
        XmlSerializer.deserializeInto(restored.state, element)

        assertEquals("https://ghe.example.com/api/v3", restored.state.baseUrl)
        assertEquals("acct-7", restored.state.preferredAccountId)
        assertFalse(restored.state.livePollingEnabled)
        assertEquals("OFF", restored.state.notificationLevel)
        assertEquals("/tmp/dl", restored.state.defaultDownloadDir)
    }

    fun testTokenFieldIsAbsent() {
        // Tokens must never be in PluginSettings — they live in PasswordSafe.
        val fields = PluginSettings.State::class.java.declaredFields.map { it.name }
        assertFalse(
            "PluginSettings.State must not contain any token field; tokens belong in PasswordSafe.",
            fields.any { it.lowercase().contains("token") }
        )
    }
}
