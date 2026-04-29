package com.example.ghactions.polling

import com.example.ghactions.api.RateLimitInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BackoffPollerTest {

    private fun snapshot(
        enabled: Boolean = true,
        visible: Boolean = true,
        anyActive: Boolean = false,
        rateLimit: RateLimitInfo = RateLimitInfo.NONE,
        nowEpochSeconds: Long = 0L
    ) = PollerSnapshot(
        livePollingEnabled = enabled,
        toolWindowVisible = visible,
        anyRunActive = anyActive,
        rateLimit = rateLimit,
        nowEpochSeconds = nowEpochSeconds
    )

    @Test
    fun `computeNextDelay returns ACTIVE interval when a run is in progress`() {
        val d = BackoffPoller.computeNextDelay(snapshot(anyActive = true))
        assertEquals(BackoffPoller.ACTIVE_INTERVAL, d)
    }

    @Test
    fun `computeNextDelay returns IDLE interval when no run is active`() {
        val d = BackoffPoller.computeNextDelay(snapshot(anyActive = false))
        assertEquals(BackoffPoller.IDLE_INTERVAL, d)
    }

    @Test
    fun `computeNextDelay returns PAUSE_INTERVAL when polling disabled or hidden`() {
        val disabled = BackoffPoller.computeNextDelay(snapshot(enabled = false))
        val hidden = BackoffPoller.computeNextDelay(snapshot(visible = false))
        assertEquals(BackoffPoller.PAUSE_INTERVAL, disabled)
        assertEquals(BackoffPoller.PAUSE_INTERVAL, hidden)
    }

    @Test
    fun `computeNextDelay waits until reset when hard rate-limited`() {
        val rl = RateLimitInfo(remaining = 0, resetEpochSeconds = 100L)
        val d = BackoffPoller.computeNextDelay(snapshot(rateLimit = rl, nowEpochSeconds = 90L))
        // 100 - 90 = 10s, plus a small jitter floor (we just check >= 10s and reasonable upper bound).
        assertTrue(d >= 10.seconds, "expected at least 10s, got $d")
        assertTrue(d <= 30.seconds, "expected at most 30s, got $d")
    }

    @Test
    fun `loop invokes tick on cadence and respects state changes`() = runTest {
        val ticks = mutableListOf<Long>()
        val state: MutableStateFlow<PollerSnapshot> = MutableStateFlow(snapshot(anyActive = true))
        val testScope = this
        val poller = BackoffPoller(state) {
            ticks += testScope.currentTime
        }

        val job = launch { poller.loop() }

        // First tick should fire after the first ACTIVE_INTERVAL delay.
        advanceTimeBy(BackoffPoller.ACTIVE_INTERVAL.inWholeMilliseconds + 50)
        // Now flip to IDLE.
        state.value = snapshot(anyActive = false)
        advanceTimeBy(BackoffPoller.IDLE_INTERVAL.inWholeMilliseconds + 50)
        // Now disable polling — no further ticks should occur.
        state.value = snapshot(enabled = false)
        val ticksBeforePause = ticks.size
        advanceTimeBy(BackoffPoller.IDLE_INTERVAL.inWholeMilliseconds * 5)
        assertEquals(ticksBeforePause, ticks.size, "no ticks should fire while paused")

        job.cancel()
    }
}
