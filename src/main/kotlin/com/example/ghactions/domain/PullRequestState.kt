package com.example.ghactions.domain

/**
 * State of a pull request, from GitHub's perspective.
 *
 * The wire values are GitHub's; [ALL] is plugin-only and exists for the filter UI —
 * it tells the API client to omit the `state` parameter (or pass `state=all`).
 */
enum class PullRequestState(val wireValue: String) {
    OPEN("open"),
    CLOSED("closed"),
    ALL("all"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String?): PullRequestState =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}
