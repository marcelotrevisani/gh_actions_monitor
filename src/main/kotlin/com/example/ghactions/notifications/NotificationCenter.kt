package com.example.ghactions.notifications

import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.repo.RunListState
import com.example.ghactions.repo.RunRepository
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches `RunRepository.runsState` for terminal transitions and fires IDE balloon
 * notifications according to the user's `PluginSettings.notificationLevel`.
 *
 * Per-project; the platform disposes us on project close. Started by
 * `GhActionsToolWindowFactory` (idempotent — repeat calls are no-ops).
 */
@Service(Service.Level.PROJECT)
class NotificationCenter(private val project: Project) : Disposable {

    private val log = Logger.getInstance(NotificationCenter::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val previousStatus = ConcurrentHashMap<RunId, RunStatus>()
    private var observerJob: CJob? = null

    fun start() {
        if (observerJob != null) return
        val runRepo = project.getService(RunRepository::class.java)
        observerJob = scope.launch {
            runRepo.runsState.collect { state -> handle(state) }
        }
    }

    private fun handle(state: RunListState) {
        val runs = (state as? RunListState.Loaded)?.runs ?: return
        val level = NotificationLevel.fromSetting(
            PluginSettings.getInstance().state.notificationLevel
        )
        val snapshot = previousStatus.toMap()
        val events = NotificationDecider.decide(snapshot, runs, level)
        events.forEach(::fire)
        // Update snapshot — record the new status of every run we saw, including ones
        // that didn't transition. Keeps the map fresh as runs move QUEUED → IN_PROGRESS → COMPLETED.
        runs.forEach { previousStatus[it.id] = it.status }
    }

    private fun fire(event: NotificationEvent) {
        val run = event.run
        val title = "${run.workflowName} · run #${run.runNumber}"
        val body = when (event.kind) {
            NotificationKind.SUCCESS -> "Succeeded"
            NotificationKind.FAILURE -> "Failed: ${run.conclusion?.name?.lowercase() ?: "unknown"}"
        }
        val type = when (event.kind) {
            NotificationKind.SUCCESS -> NotificationType.INFORMATION
            NotificationKind.FAILURE -> NotificationType.ERROR
        }
        try {
            val notification: Notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, body, type)
                .addAction(object : NotificationAction("View on GitHub") {
                    override fun actionPerformed(e: AnActionEvent, n: Notification) {
                        BrowserUtil.browse(run.htmlUrl)
                        n.expire()
                    }
                })
            notification.notify(project)
        } catch (t: Throwable) {
            // Notification group may not be registered (e.g. in tests). Don't crash.
            log.warn("Failed to fire notification for run ${run.id}", t)
        }
    }

    override fun dispose() {
        observerJob?.cancel()
        scope.cancel()
        previousStatus.clear()
    }

    companion object {
        const val NOTIFICATION_GROUP_ID = "GitHub Actions Monitor"
    }
}
