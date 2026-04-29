package com.example.ghactions.api

import com.intellij.openapi.components.Service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Project-scoped holder for the most recent [RateLimitInfo] observed on any HTTP
 * response. The poller consults [state] before every tick; if `isHardLimited` is true,
 * the tick is skipped until the reset window passes.
 *
 * Updated by the Ktor `ResponseObserver` installed in `GitHubHttp.create`.
 */
@Service(Service.Level.PROJECT)
class RateLimitTracker {
    private val _state = MutableStateFlow(RateLimitInfo.NONE)
    val state: StateFlow<RateLimitInfo> = _state.asStateFlow()

    fun update(info: RateLimitInfo) {
        // Only overwrite when the response actually carried headers — a 200 from
        // a non-rate-limited endpoint without RL headers (rare for GitHub but possible
        // for redirects) shouldn't blow away our last-known good state.
        if (info != RateLimitInfo.NONE) {
            _state.value = info
        }
    }
}
