package com.example.ghactions.polling

import com.example.ghactions.api.RateLimitInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pure inputs to [BackoffPoller.computeNextDelay]. The poller never reads the IDE platform
 * directly — `PollingCoordinator` assembles a snapshot per tick and feeds it in.
 */
data class PollerSnapshot(
    val livePollingEnabled: Boolean,
    val toolWindowVisible: Boolean,
    val anyRunActive: Boolean,
    val rateLimit: RateLimitInfo,
    val nowEpochSeconds: Long
)

/**
 * Adaptive cadence policy + suspend driver loop.
 *
 * **Policy ([computeNextDelay]):**
 * - Polling disabled or tool window hidden → `PAUSE_INTERVAL` (the loop wakes periodically
 *   and re-checks state; this is *also* how the user's "live polling enabled" toggle is
 *   honoured live without restart).
 * - Hard rate-limited → wait until the reset timestamp + small jitter floor.
 * - Any run in `in_progress` / `queued` → `ACTIVE_INTERVAL` (5 s).
 * - Otherwise → `IDLE_INTERVAL` (60 s).
 *
 * **Driver ([loop]):** simple "delay then tick" — *not* "tick then delay" — so a paused
 * poller doesn't fire a stale request the moment the user toggles back on. The first
 * legitimate tick happens after the initial computed delay.
 */
class BackoffPoller(
    private val state: StateFlow<PollerSnapshot>,
    private val tick: suspend () -> Unit
) {
    suspend fun loop() {
        while (true) {
            val snap = state.value
            val delayMs = computeNextDelay(snap).inWholeMilliseconds
            delay(delayMs)
            val freshSnap = state.value
            if (shouldFire(freshSnap)) {
                tick()
            }
        }
    }

    companion object {
        val ACTIVE_INTERVAL: Duration = 5.seconds
        val IDLE_INTERVAL: Duration = 60.seconds
        val PAUSE_INTERVAL: Duration = 10.seconds
        private const val RATE_LIMIT_FLOOR_SECONDS = 5L
        private const val RATE_LIMIT_CEILING_SECONDS = 30L

        fun computeNextDelay(snap: PollerSnapshot): Duration {
            if (!snap.livePollingEnabled || !snap.toolWindowVisible) return PAUSE_INTERVAL
            if (snap.rateLimit.isHardLimited) return rateLimitWait(snap)
            return if (snap.anyRunActive) ACTIVE_INTERVAL else IDLE_INTERVAL
        }

        private fun shouldFire(snap: PollerSnapshot): Boolean {
            if (!snap.livePollingEnabled || !snap.toolWindowVisible) return false
            if (snap.rateLimit.isHardLimited) return false
            return true
        }

        private fun rateLimitWait(snap: PollerSnapshot): Duration {
            val retryAfter = snap.rateLimit.retryAfterSeconds
            val resetEpoch = snap.rateLimit.resetEpochSeconds
            val raw = when {
                retryAfter != null -> retryAfter.toLong()
                resetEpoch != null -> (resetEpoch - snap.nowEpochSeconds).coerceAtLeast(0)
                else -> RATE_LIMIT_FLOOR_SECONDS
            }
            val clamped = raw.coerceIn(RATE_LIMIT_FLOOR_SECONDS, RATE_LIMIT_CEILING_SECONDS)
            return clamped.seconds
        }
    }
}
