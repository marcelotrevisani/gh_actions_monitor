package com.example.ghactions.ui

/**
 * One top-level GitHub Actions log section. Step N (1-indexed) is conventionally
 * mapped to the Nth section returned by [LogSectionParser.parse].
 *
 * `endLineExclusive` points to the line *after* the matching `##[endgroup]`.
 */
data class LogSection(
    val name: String,
    val startLine: Int,
    val endLineExclusive: Int
)

/**
 * Parses GitHub Actions log text into top-level sections.
 *
 * GitHub wraps each step (and a few synthetic ones like "Set up job" and "Complete job")
 * in `##[group]<name>` / `##[endgroup]` markers. Inner runners can emit *nested* groups
 * within a step — those stay inside the parent. We track depth and surface only the
 * outermost-level groups; that maps cleanly to the steps the user sees in the tree.
 */
object LogSectionParser {

    private val groupRe = Regex("##\\[group](.*)")
    private val endgroupRe = Regex("##\\[endgroup]")

    fun parse(log: String): List<LogSection> {
        if (log.isEmpty()) return emptyList()

        val lines = log.lines()
        val sections = mutableListOf<LogSection>()

        var depth = 0
        var currentStart = -1
        var currentName = ""

        for ((i, line) in lines.withIndex()) {
            val groupMatch = groupRe.find(line)
            if (groupMatch != null) {
                if (depth == 0) {
                    currentStart = i
                    currentName = groupMatch.groupValues[1].trim()
                }
                depth++
                continue
            }
            if (endgroupRe.find(line) != null) {
                if (depth > 0) depth--
                if (depth == 0 && currentStart >= 0) {
                    sections.add(LogSection(currentName, currentStart, i + 1))
                    currentStart = -1
                    currentName = ""
                }
            }
        }
        return sections
    }
}
