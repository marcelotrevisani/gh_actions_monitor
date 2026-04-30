package com.example.ghactions.domain

/**
 * Subset of GitHub's check-run object we surface in the UI. The summary and text
 * fields are markdown; v1 displays them as plain text.
 */
data class CheckRunOutput(
    val title: String?,
    val summary: String?,
    val text: String?,
    val annotationsCount: Int
)
