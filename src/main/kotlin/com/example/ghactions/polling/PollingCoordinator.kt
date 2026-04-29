package com.example.ghactions.polling

import com.example.ghactions.api.RateLimitTracker
import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.repo.RunListState
import com.example.ghactions.repo.RunRepository
import com.example.ghactions.domain.RunStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Project-scoped poll loop. Started by [start] (called from `GhActionsToolWindowFactory`
 * on the first tool-window open) and stopped by [Disposable.dispose] (registered as a
 * project service, so the platform disposes it on project close).
 *
 * Visibility is fed in by [setToolWindowVisible] from `ToolWindowManagerListener`. The
 * coordinator never asks the IDE about visibility itself — keeps the loop deterministic
 * and IDE-test-friendly.
 */
@Service(Service.Level.PROJECT)
class PollingCoordinator(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PollingCoordinator::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val visibility = MutableStateFlow(false)
    private var loopJob: Job? = null

    fun start() {
        if (loopJob != null) return
        val runRepo = project.getService(RunRepository::class.java)
        val tracker = project.getService(RateLimitTracker::class.java)

        val snapshot: StateFlow<PollerSnapshot> = combineSnapshot(runRepo, tracker)
        loopJob = scope.launch {
            BackoffPoller(snapshot) {
                try {
                    runRepo.refreshRuns().join()
                } catch (t: Throwable) {
                    log.warn("Poll tick failed", t)
                }
            }.loop()
        }
    }

    fun setToolWindowVisible(visible: Boolean) {
        visibility.value = visible
    }

    private fun combineSnapshot(
        runRepo: RunRepository,
        tracker: RateLimitTracker
    ): StateFlow<PollerSnapshot> {
        val out = MutableStateFlow(initialSnapshot())
        // Recompute on any input change. Settings is read each combine — there's no
        // observable State for it on the platform, so we sample it per emission.
        combine(runRepo.runsState, tracker.state, visibility) { runs, rl, vis ->
            PollerSnapshot(
                livePollingEnabled = PluginSettings.getInstance().state.livePollingEnabled,
                toolWindowVisible = vis,
                anyRunActive = anyActive(runs),
                rateLimit = rl,
                nowEpochSeconds = System.currentTimeMillis() / 1000L
            )
        }.onEach { out.value = it }.launchIn(scope)
        return out
    }

    private fun initialSnapshot(): PollerSnapshot = PollerSnapshot(
        livePollingEnabled = PluginSettings.getInstance().state.livePollingEnabled,
        toolWindowVisible = false,
        anyRunActive = false,
        rateLimit = com.example.ghactions.api.RateLimitInfo.NONE,
        nowEpochSeconds = System.currentTimeMillis() / 1000L
    )

    private fun anyActive(runs: RunListState): Boolean = when (runs) {
        is RunListState.Loaded -> runs.runs.any {
            it.status == RunStatus.IN_PROGRESS || it.status == RunStatus.QUEUED
        }
        else -> false
    }

    override fun dispose() {
        scope.cancel()
        loopJob = null
    }
}
