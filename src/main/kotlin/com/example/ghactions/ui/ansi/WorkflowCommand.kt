package com.example.ghactions.ui.ansi

/** Severity of a workflow command marker. Independent of [AnsiColor]. */
enum class CommandLevel { ERROR, WARNING, NOTICE }

/** Parsed marker. [file] and [line] are optional location hints. */
data class WorkflowCommand(
    val level: CommandLevel,
    val message: String,
    val file: String? = null,
    val line: Int? = null
)

/**
 * Group marker — `##[group]name` opens, `##[endgroup]` closes (also legacy `::group::name`
 * / `::endgroup::`). Rendered visually but not actually folded; the GitHub UI's
 * collapsing behaviour is out of scope for v1.
 */
sealed class GroupMarker {
    data class Open(val name: String) : GroupMarker()
    data object Close : GroupMarker()
}
