package com.example.ghactions.notifications

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus

/** User's setting for how loud the notifier is. Mirrors `PluginSettings.notificationLevel`. */
enum class NotificationLevel {
    OFF, FAILURES_ONLY, ALL;

    companion object {
        fun fromSetting(value: String?): NotificationLevel = when (value?.uppercase()) {
            "OFF" -> OFF
            "ALL" -> ALL
            else -> FAILURES_ONLY  // sane default — matches the settings default
        }
    }
}

/** What kind of balloon to show for a completed run. */
enum class NotificationKind { SUCCESS, FAILURE }

/** A balloon event the [NotificationCenter] should fire. */
data class NotificationEvent(val run: Run, val kind: NotificationKind)

/**
 * Pure policy: given the previously-seen status of each run, the current run list, and
 * the user's setting, return the notification events that should fire.
 *
 * **Rules**
 * - We only fire on a *transition* we observed: the previous status must have been
 *   non-terminal AND the current status must be terminal.
 * - First-time-seen runs (unseen in [previousStatus]) never fire — we don't have evidence
 *   that the user *missed* the transition; they may have just opened the project.
 * - `OFF` → empty list always.
 * - `FAILURES_ONLY` → fire only when the conclusion isn't [RunConclusion.SUCCESS].
 * - `ALL` → fire on every observed transition.
 */
object NotificationDecider {

    fun decide(
        previousStatus: Map<RunId, RunStatus>,
        currentRuns: List<Run>,
        level: NotificationLevel
    ): List<NotificationEvent> {
        if (level == NotificationLevel.OFF) return emptyList()
        return currentRuns.mapNotNull { run ->
            val previous = previousStatus[run.id] ?: return@mapNotNull null
            if (previous.isTerminal()) return@mapNotNull null
            if (!run.status.isTerminal()) return@mapNotNull null
            val kind = if (run.conclusion == RunConclusion.SUCCESS) {
                NotificationKind.SUCCESS
            } else {
                NotificationKind.FAILURE
            }
            if (level == NotificationLevel.FAILURES_ONLY && kind == NotificationKind.SUCCESS) {
                return@mapNotNull null
            }
            NotificationEvent(run, kind)
        }
    }

    private fun RunStatus.isTerminal(): Boolean = this == RunStatus.COMPLETED
}
