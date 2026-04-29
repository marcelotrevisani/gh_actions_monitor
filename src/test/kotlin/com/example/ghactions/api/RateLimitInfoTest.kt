package com.example.ghactions.api

import io.ktor.http.Headers
import io.ktor.http.headersOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitInfoTest {

    @Test
    fun `fromHeaders parses standard rate-limit triple`() {
        val headers = headersOf(
            "X-RateLimit-Limit" to listOf("5000"),
            "X-RateLimit-Remaining" to listOf("4321"),
            "X-RateLimit-Reset" to listOf("1714427200")
        )
        val info = RateLimitInfo.fromHeaders(headers)
        assertEquals(5000, info.limit)
        assertEquals(4321, info.remaining)
        assertEquals(1714427200L, info.resetEpochSeconds)
        assertNull(info.retryAfterSeconds)
        assertFalse(info.isHardLimited)
    }

    @Test
    fun `fromHeaders parses Retry-After (seconds)`() {
        val headers = headersOf(
            "Retry-After" to listOf("60"),
            "X-RateLimit-Remaining" to listOf("0"),
            "X-RateLimit-Reset" to listOf("1714427200")
        )
        val info = RateLimitInfo.fromHeaders(headers)
        assertEquals(60, info.retryAfterSeconds)
        assertEquals(0, info.remaining)
        assertTrue(info.isHardLimited)
    }

    @Test
    fun `fromHeaders returns NONE when no rate-limit headers present`() {
        val info = RateLimitInfo.fromHeaders(Headers.Empty)
        assertEquals(RateLimitInfo.NONE, info)
        assertFalse(info.isHardLimited)
    }

    @Test
    fun `isHardLimited true when remaining is zero, even without Retry-After`() {
        val headers = headersOf(
            "X-RateLimit-Limit" to listOf("5000"),
            "X-RateLimit-Remaining" to listOf("0"),
            "X-RateLimit-Reset" to listOf("1714427200")
        )
        val info = RateLimitInfo.fromHeaders(headers)
        assertNotNull(info.resetEpochSeconds)
        assertTrue(info.isHardLimited)
    }
}
