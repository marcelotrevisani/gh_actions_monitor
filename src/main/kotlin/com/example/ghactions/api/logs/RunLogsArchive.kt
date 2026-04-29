package com.example.ghactions.api.logs

import com.intellij.openapi.diagnostic.Logger
import java.io.ByteArrayInputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Parsed representation of GitHub's run-logs zip archive.
 *
 * The archive is downloaded from `GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs`
 * and contains a per-job text log plus a folder per job with one `.txt` per step.
 *
 * GitHub's exact layout varies — common shapes seen in the wild:
 * ```
 * build.txt                                  ← whole-job log (root .txt)
 * build/1_Set up job.txt                     ← per-step under bare folder
 *
 * 0_build-and-test.txt                       ← whole-job log (with index prefix)
 * 0_build-and-test/1_Set up job.txt          ← per-step under prefixed folder
 * ```
 * The job folder's exact name is therefore matched **fuzzily**: we accept the requested
 * job name verbatim, with a `<index>_` prefix, or any folder that contains the job name
 * as a substring. Within the matched folder, step files are matched by `<stepNumber>_`
 * prefix (with the step name as a tiebreaker if more than one matches the prefix).
 *
 * Construction parses the zip eagerly into a name→bytes map; subsequent reads are O(1).
 * Mutating the input byte array after construction is safe.
 *
 * Malformed input is logged and yields an empty archive — no exceptions to the caller.
 */
class RunLogsArchive(zipBytes: ByteArray) {

    private val log = Logger.getInstance(RunLogsArchive::class.java)

    /** Map from path inside the zip to UTF-8 decoded text content. */
    private val entries: Map<String, String> = parse(zipBytes)

    /**
     * Returns the names of every "job-shaped" entry in the archive, useful for diagnostics.
     * Includes both root-level `<job>.txt` files (with extension stripped) and folder names
     * found in nested paths.
     */
    fun listJobs(): List<String> {
        val rootTxt = entries.keys
            .filter { name -> name.endsWith(".txt") && !name.contains('/') }
            .map { it.removeSuffix(".txt") }
        val folders = entries.keys
            .mapNotNull { key ->
                val slash = key.indexOf('/')
                if (slash > 0) key.substring(0, slash) else null
            }
            .distinct()
        return (rootTxt + folders).distinct().sorted()
    }

    /**
     * Returns the file names (without folder prefix) of every entry under any folder
     * fuzzy-matching [jobName]. Diagnostic helper — used by the repository to surface
     * the actual layout in error messages when step extraction misses.
     */
    fun listStepFiles(jobName: String): List<String> {
        val matchingFolders = entries.keys
            .mapNotNull { key ->
                val slash = key.indexOf('/')
                if (slash > 0) key.substring(0, slash) else null
            }
            .distinct()
            .filter { folder -> fuzzyJobMatch(folder, jobName) }

        return entries.keys
            .filter { key -> matchingFolders.any { folder -> key.startsWith("$folder/") } }
            .map { it.substringAfter('/') }
            .sorted()
    }

    /**
     * Whole-job log text. Tries (in order): `<jobName>.txt`, then any root-level `.txt`
     * whose base name fuzzy-matches `jobName`.
     */
    fun jobLog(jobName: String): String? {
        entries["$jobName.txt"]?.let { return it }
        return entries.entries
            .firstOrNull { (k, _) ->
                k.endsWith(".txt") && !k.contains('/') && fuzzyJobMatch(k.removeSuffix(".txt"), jobName)
            }
            ?.value
    }

    /**
     * Per-step log text. Strategy in priority order:
     *   1. Exact path: `<jobName>/<stepNumber>_<sanitisedStepName>.txt`.
     *   2. Step-number under exact `<jobName>/`.
     *   3. Step-number under any folder whose name fuzzy-matches `jobName`.
     *   4. **Fallback** when none of the above hit: parse the whole-job log (which
     *      *does* exist even when per-step files don't) and slice out the section
     *      bounded by `##[group]Run …` markers. This is necessary because GitHub
     *      sometimes emits archives with no per-step files at all — only a root
     *      `<jobName>.txt` containing every step's interleaved output.
     */
    fun stepLog(jobName: String, stepNumber: Int, stepName: String): String? {
        val sanitised = sanitiseForFileName(stepName)

        // 1. Exact path.
        entries["$jobName/${stepNumber}_$sanitised.txt"]?.let { return it }

        // 2. Step-number prefix under exact folder.
        entries.entries
            .firstOrNull { (k, _) -> k.startsWith("$jobName/${stepNumber}_") && k.endsWith(".txt") }
            ?.value
            ?.let { return it }

        // 3. Fuzzy folder match — handles "0_build-and-test/" style indices.
        val matchingFolders = entries.keys
            .mapNotNull { key ->
                val slash = key.indexOf('/')
                if (slash > 0) key.substring(0, slash) else null
            }
            .distinct()
            .filter { folder -> fuzzyJobMatch(folder, jobName) }

        for (folder in matchingFolders) {
            entries["$folder/${stepNumber}_$sanitised.txt"]?.let { return it }
            entries.entries
                .firstOrNull { (k, _) -> k.startsWith("$folder/${stepNumber}_") && k.endsWith(".txt") }
                ?.value
                ?.let { return it }
        }

        // 4. Fallback: slice the whole-job log by `##[group]Run …` markers.
        return jobLog(jobName)?.let { wholeLog -> sliceFromJobLog(wholeLog, stepNumber, stepName) }
    }

    /**
     * Find the section of [wholeLog] that corresponds to API step [stepNumber] / [stepName].
     *
     * Sections start at depth-0 lines beginning with `##[group]Run ` (the GitHub convention
     * for every user step, whether `run:` shell or `uses:` action). Sibling depth-0 groups
     * inside an action body (e.g. `##[group]Getting Git version info`) stay nested.
     *
     * Match priority:
     *   1. Exact case-insensitive name equality.
     *   2. Substring either direction.
     *   3. Positional fallback (`stepNumber − 2`, since API step 1 is "Set up job" and
     *      has no `##[group]Run …`).
     *   4. **Synthetic-step handling** for the prelude and postlude that GitHub doesn't
     *      delimit with `Run` groups:
     *        - Step 1 ("Set up job") → everything before the first `##[group]Run …`.
     *        - Step beyond `1 + sections.size` ("Complete job", post-cleanup) → everything
     *          from the `Post job cleanup.` marker onward, if present.
     */
    private fun sliceFromJobLog(wholeLog: String, stepNumber: Int, stepName: String): String? {
        val lines = wholeLog.lines()
        val starts = mutableListOf<Pair<Int, String>>()  // (line index, captured group name)
        var depth = 0
        for ((i, line) in lines.withIndex()) {
            val gm = GROUP_RE.find(line)
            if (gm != null) {
                val captured = gm.groupValues[1].trim()
                if (depth == 0 && captured.startsWith(STEP_GROUP_PREFIX)) {
                    starts.add(i to captured)
                }
                depth++
                continue
            }
            if (ENDGROUP_RE.find(line) != null && depth > 0) depth--
        }

        val needle = stepName.trim()

        // 1+2. Name-based matches (only meaningful if we have at least one Run section).
        if (starts.isNotEmpty()) {
            val byName = starts.indexOfFirst { it.second.equals(needle, ignoreCase = true) }
                .takeIf { it >= 0 }
                ?: starts.indexOfFirst { (_, n) ->
                    n.contains(needle, ignoreCase = true) || needle.contains(n, ignoreCase = true)
                }.takeIf { it >= 0 }
            if (byName != null) return sliceSection(lines, starts, byName)
        }

        // 4a. Synthetic prelude: "Set up job" is API step 1 and has no Run group.
        if (stepNumber == 1) {
            val end = starts.firstOrNull()?.first ?: lines.size
            return if (end > 0) lines.subList(0, end).joinToString("\n") else null
        }

        // 3. Positional fallback for the user-defined steps.
        val sectionIndex = stepNumber - 2
        if (sectionIndex in starts.indices) {
            return sliceSection(lines, starts, sectionIndex)
        }

        // 4b. Synthetic postlude: "Complete job" / post-cleanup is past the last Run section.
        if (stepNumber > 1 + starts.size) {
            val postJobIdx = lines.indexOfFirst { it.contains(POST_JOB_MARKER) }
            if (postJobIdx >= 0) {
                return lines.subList(postJobIdx, lines.size).joinToString("\n")
            }
        }

        return null
    }

    private fun sliceSection(
        lines: List<String>,
        starts: List<Pair<Int, String>>,
        sectionIndex: Int
    ): String {
        val (startLine, _) = starts[sectionIndex]
        val endLine = starts.getOrNull(sectionIndex + 1)?.first
            ?: lines.indexOfFirst { it.contains(POST_JOB_MARKER) }.takeIf { it > startLine }
            ?: lines.size
        return lines.subList(startLine, endLine).joinToString("\n")
    }

    /**
     * Whether [folderName] should be treated as the same job as [jobName] given that
     * GitHub sometimes prefixes job folders/files with a numeric index.
     */
    private fun fuzzyJobMatch(folderName: String, jobName: String): Boolean {
        if (folderName.equals(jobName, ignoreCase = true)) return true
        // "0_build-and-test", "12_build-and-test", etc.
        val numericPrefix = Regex("""^\d+_(.+)$""").matchEntire(folderName)?.groupValues?.get(1)
        if (numericPrefix != null && numericPrefix.equals(jobName, ignoreCase = true)) return true
        // Loose containment as a final fallback.
        if (folderName.contains(jobName, ignoreCase = true)) return true
        if (jobName.contains(folderName, ignoreCase = true)) return true
        return false
    }

    private fun parse(zipBytes: ByteArray): Map<String, String> {
        if (zipBytes.isEmpty()) return emptyMap()
        return try {
            val out = mutableMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (!entry.isDirectory) {
                        out[entry.name] = zin.readBytes().toString(Charsets.UTF_8)
                    }
                    zin.closeEntry()
                }
            }
            out
        } catch (e: ZipException) {
            log.warn("Malformed run-logs zip; treating as empty archive", e)
            emptyMap()
        } catch (e: Throwable) {
            log.warn("Failed to parse run-logs zip; treating as empty archive", e)
            emptyMap()
        }
    }

    /**
     * Approximate the file-name sanitisation GitHub applies. The fallback in [stepLog]
     * catches anything this misses.
     */
    private fun sanitiseForFileName(name: String): String =
        name.replace('/', '_').replace(' ', '_').trim()

    private companion object {
        private val GROUP_RE = Regex("##\\[group](.*)")
        private val ENDGROUP_RE = Regex("##\\[endgroup]")
        private const val STEP_GROUP_PREFIX = "Run "
        /** GitHub's reliable signal that the user-defined steps are done and post-cleanup begins. */
        private const val POST_JOB_MARKER = "Post job cleanup."
    }
}
