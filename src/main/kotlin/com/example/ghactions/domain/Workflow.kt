package com.example.ghactions.domain

@JvmInline
value class WorkflowId(val value: Long) {
    override fun toString(): String = value.toString()
}

/**
 * One workflow definition (a [.github/workflows] yml file). [state] is GitHub's
 * lifecycle string ("active", "disabled_manually", etc.). v1 surfaces only "active"
 * workflows in the picker, but the DTO carries the full value for completeness.
 */
data class Workflow(
    val id: WorkflowId,
    val name: String,
    val path: String,
    val state: String
)
