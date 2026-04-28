package com.example.ghactions.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuthSourceTest {
    @Test
    fun `pat source carries token and host`() {
        val src = AuthSource.Pat(host = "https://api.github.com", token = "ghp_xxx")
        assertEquals("https://api.github.com", src.host)
        assertEquals("ghp_xxx", src.token)
    }

    @Test
    fun `ide account source carries account id and host`() {
        val src = AuthSource.IdeAccount(host = "https://api.github.com", accountId = "abc-123")
        assertEquals("https://api.github.com", src.host)
        assertEquals("abc-123", src.accountId)
    }

    @Test
    fun `pat redacts token in toString`() {
        val src = AuthSource.Pat(host = "https://api.github.com", token = "ghp_supersecret123")
        assertNotEquals(true, src.toString().contains("supersecret"))
    }
}
