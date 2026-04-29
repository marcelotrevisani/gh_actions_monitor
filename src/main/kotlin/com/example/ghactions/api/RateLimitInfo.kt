package com.example.ghactions.api

import io.ktor.http.Headers

/**
 * Snapshot of GitHub's rate-limit headers from the most recent response.
 *
 * Pure value type — no IDE deps. Parsed via [fromHeaders]; held by `RateLimitTracker`
 * (a project service) so the poller can consult it before scheduling each tick.
 *
 * `isHardLimited` is the polling-relevant predicate: true means "do not call the API
 * again before [resetEpochSeconds] (or until the [retryAfterSeconds] window has passed)".
 */
data class RateLimitInfo(
    val limit: Int? = null,
    val remaining: Int? = null,
    val resetEpochSeconds: Long? = null,
    val retryAfterSeconds: Int? = null
) {
    val isHardLimited: Boolean
        get() = retryAfterSeconds != null || remaining == 0

    companion object {
        val NONE = RateLimitInfo()

        fun fromHeaders(headers: Headers): RateLimitInfo {
            val limit = headers["X-RateLimit-Limit"]?.toIntOrNull()
            val remaining = headers["X-RateLimit-Remaining"]?.toIntOrNull()
            val reset = headers["X-RateLimit-Reset"]?.toLongOrNull()
            val retryAfter = headers["Retry-After"]?.toIntOrNull()
            if (limit == null && remaining == null && reset == null && retryAfter == null) {
                return NONE
            }
            return RateLimitInfo(
                limit = limit,
                remaining = remaining,
                resetEpochSeconds = reset,
                retryAfterSeconds = retryAfter
            )
        }
    }
}
