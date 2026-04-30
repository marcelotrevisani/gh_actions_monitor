package com.example.ghactions.ui.ansi

/**
 * Recognises GitHub Actions workflow command markers in a log line. Returns null for
 * lines that don't match one of the supported shapes. The caller decides what to do
 * with the result — this object is pure.
 *
 * **Supported**
 * - `::<level>[ <key>=<value>(,<key>=<value>)*]::<message>`
 *   where `<level>` is `error`, `warning`, or `notice`. Recognised keys: `file`, `line`.
 *   Other keys are ignored.
 * - `##[<level>]<message>` where `<level>` is `error`, `warning`, or `notice`.
 *
 * **Not supported (yet)**
 * - `::group::` / `::endgroup::` / `##[group]` — folding lives in a separate plan.
 * - `::add-mask::`, `::debug::`, `::set-output::` — low-value markers, ignored (return null).
 */
object WorkflowCommandParser {

    private val COLON_RE = Regex(
        """^\s*::(error|warning|notice)(?:\s+([^:]*))?::(.*)$"""
    )
    private val HASH_RE = Regex(
        """^\s*##\[(error|warning|notice)\](.*)$"""
    )

    fun parseLine(line: String): WorkflowCommand? {
        COLON_RE.matchEntire(line)?.let { match ->
            val level = level(match.groupValues[1]) ?: return null
            val params = parseParams(match.groupValues[2])
            return WorkflowCommand(
                level = level,
                message = match.groupValues[3],
                file = params["file"]?.takeIf { it.isNotBlank() },
                line = params["line"]?.toIntOrNull()
            )
        }
        HASH_RE.matchEntire(line)?.let { match ->
            val level = level(match.groupValues[1]) ?: return null
            return WorkflowCommand(level = level, message = match.groupValues[2])
        }
        return null
    }

    private fun level(token: String): CommandLevel? = when (token) {
        "error" -> CommandLevel.ERROR
        "warning" -> CommandLevel.WARNING
        "notice" -> CommandLevel.NOTICE
        else -> null
    }

    private fun parseParams(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(",")
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
            }
            .toMap()
    }
}
