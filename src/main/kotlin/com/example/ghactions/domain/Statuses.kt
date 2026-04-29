package com.example.ghactions.domain

/**
 * Workflow run lifecycle state, per GitHub Actions API. The wire values are GitHub's;
 * the enum names are ours. [UNKNOWN] is a sink for future GitHub additions so we never
 * crash on unrecognized strings.
 */
enum class RunStatus(val wireValue: String) {
    QUEUED("queued"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    WAITING("waiting"),
    REQUESTED("requested"),
    PENDING("pending"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String?): RunStatus =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * Final outcome of a completed run. `null` while a run is still in progress —
 * GitHub returns `null` (not "" or "unknown") for not-yet-concluded runs, and we
 * mirror that.
 */
enum class RunConclusion(val wireValue: String) {
    SUCCESS("success"),
    FAILURE("failure"),
    CANCELLED("cancelled"),
    SKIPPED("skipped"),
    TIMED_OUT("timed_out"),
    NEUTRAL("neutral"),
    ACTION_REQUIRED("action_required"),
    STALE("stale"),
    UNKNOWN("");

    companion object {
        /** Returns null for a null wire value (in-progress run). [UNKNOWN] for unrecognized strings. */
        fun fromWire(value: String?): RunConclusion? = when (value) {
            null -> null
            else -> entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
        }
    }
}
