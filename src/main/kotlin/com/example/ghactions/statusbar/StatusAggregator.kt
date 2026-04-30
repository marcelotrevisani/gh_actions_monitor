package com.example.ghactions.statusbar

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunStatus

/** Aggregate state shown by the status bar widget. */
enum class StatusState { IDLE, RUNNING, SUCCESS, FAILURE }

/** Composed result of [StatusAggregator.summarize]. */
data class StatusSummary(val state: StatusState, val tooltip: String)

/**
 * Pure reducer: turn a list of runs (already filtered to the active branch) into a
 * single [StatusSummary] for the status-bar widget.
 *
 * **Precedence**
 * - Empty → `IDLE`
 * - Any non-terminal (`IN_PROGRESS` or `QUEUED`) → `RUNNING`
 * - Otherwise: any non-`SUCCESS` conclusion → `FAILURE`; else `SUCCESS`.
 */
object StatusAggregator {

    fun summarize(runs: List<Run>): StatusSummary {
        if (runs.isEmpty()) return StatusSummary(StatusState.IDLE, "No runs for this branch")

        val inProgress = runs.count { it.status == RunStatus.IN_PROGRESS || it.status == RunStatus.QUEUED }
        if (inProgress > 0) {
            val total = runs.size
            return StatusSummary(
                StatusState.RUNNING,
                "$inProgress in progress · $total total"
            )
        }

        val anyFailure = runs.any { it.conclusion != RunConclusion.SUCCESS }
        return if (anyFailure) {
            StatusSummary(StatusState.FAILURE, "${runs.size} run(s); at least one failed")
        } else {
            StatusSummary(StatusState.SUCCESS, "${runs.size} run(s); all succeeded")
        }
    }
}
