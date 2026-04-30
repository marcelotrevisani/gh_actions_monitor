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
