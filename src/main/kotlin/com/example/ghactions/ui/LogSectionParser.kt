package com.example.ghactions.ui

/**
 * One section of GitHub Actions log output, corresponding to a user-visible step.
 *
 * `endLineExclusive` points to the line *after* the section ends. The section runs from
 * the opening `##[group]Run …` line up to (but not including) the next such line, or to
 * end of log if this is the last step.
 */
data class LogSection(
    val name: String,
    val startLine: Int,
    val endLineExclusive: Int
)

/**
 * Parses GitHub Actions log text into the user-visible step sections.
 *
 * GitHub emits a `##[group]Run <name>` line at the start of every user-defined step
 * (whether `run:` shell or `uses:` action). The matching `##[endgroup]` closes only the
 * banner — the step's actual stdout/stderr continues until the next `##[group]Run …`
 * opens, or until end of log.
 *
 * Other depth-0 `##[group]` markers (e.g. *"Getting Git version info"*, *"Initializing
 * the repository"*) are sub-tasks emitted *by* the action and belong to the enclosing
 * step. We use the *"Run "* prefix as the discriminator: only those lines start a new
 * section. Synthetic prelude/postlude blocks (Runner Image Provisioner, GITHUB_TOKEN
 * Permissions, post-job cleanup) are not surfaced as their own sections — they live
 * either before the first step or after the last.
 */
object LogSectionParser {

    private val groupRe = Regex("##\\[group](.*)")
    private val endgroupRe = Regex("##\\[endgroup]")

    fun parse(log: String): List<LogSection> {
        if (log.isEmpty()) return emptyList()
        val lines = log.lines()

        // Find every depth-0 `##[group]Run …` line. Track depth properly so that nested
        // `##[group]` inside an action body don't count.
        data class Start(val line: Int, val name: String)
        val starts = mutableListOf<Start>()
        var depth = 0
        for ((i, line) in lines.withIndex()) {
            val gm = groupRe.find(line)
            if (gm != null) {
                val captured = gm.groupValues[1].trim()
                if (depth == 0 && captured.startsWith(STEP_PREFIX)) {
                    starts.add(Start(i, captured))
                }
                depth++
                continue
            }
            if (endgroupRe.find(line) != null && depth > 0) {
                depth--
            }
        }

        if (starts.isEmpty()) return emptyList()

        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1)?.line ?: lines.size
            LogSection(name = start.name, startLine = start.line, endLineExclusive = end)
        }
    }

    private const val STEP_PREFIX = "Run "
}
