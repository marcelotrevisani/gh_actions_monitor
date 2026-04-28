package com.example.ghactions.auth

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PatStorageTest : BasePlatformTestCase() {

    fun testSetAndGet() {
        val storage = PatStorage()
        storage.setToken("https://api.github.com", "ghp_test_value")

        assertEquals("ghp_test_value", storage.getToken("https://api.github.com"))
    }

    fun testGetUnknownHostReturnsNull() {
        val storage = PatStorage()
        assertNull(storage.getToken("https://unknown.example.com/api"))
    }

    fun testClearRemovesToken() {
        val storage = PatStorage()
        storage.setToken("https://api.github.com", "ghp_temp")
        storage.clearToken("https://api.github.com")

        assertNull(storage.getToken("https://api.github.com"))
    }

    fun testTokensIsolatedPerHost() {
        val storage = PatStorage()
        storage.setToken("https://api.github.com", "ghp_dotcom")
        storage.setToken("https://ghe.example.com/api/v3", "ghp_ghes")

        assertEquals("ghp_dotcom", storage.getToken("https://api.github.com"))
        assertEquals("ghp_ghes", storage.getToken("https://ghe.example.com/api/v3"))
    }

    override fun tearDown() {
        try {
            val storage = PatStorage()
            storage.clearToken("https://api.github.com")
            storage.clearToken("https://ghe.example.com/api/v3")
        } finally {
            super.tearDown()
        }
    }
}
