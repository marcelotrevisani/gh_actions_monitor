package com.example.ghactions.api.logs

import com.intellij.openapi.diagnostic.Logger
import java.io.ByteArrayInputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Parsed representation of GitHub's run-logs zip archive.
 *
 * The archive is downloaded from `GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs`
 * and contains a per-job text log plus a folder per job with one `.txt` per step:
 *
 * ```
 * build.txt                                  ← whole-job log
 * build/1_Set up job.txt                     ← step 1
 * build/2_Run actions_checkout@v4.txt        ← step 2 (note '/' sanitised to '_')
 * build/3_Run swift test.txt                 ← step 3
 * ```
 *
 * Construction parses the zip eagerly into a name→bytes map; subsequent reads are O(1).
 * Mutating the input byte array after construction is safe (we copy out the entries).
 *
 * Malformed input is logged and yields an empty archive — no exceptions to the caller.
 */
class RunLogsArchive(zipBytes: ByteArray) {

    private val log = Logger.getInstance(RunLogsArchive::class.java)

    /** Map from path inside the zip to UTF-8 decoded text content. */
    private val entries: Map<String, String> = parse(zipBytes)

    /** Returns the names (without extension) of all per-job log files in the archive. */
    fun listJobs(): List<String> =
        entries.keys
            .filter { name -> name.endsWith(".txt") && !name.contains('/') }
            .map { it.removeSuffix(".txt") }

    /** Whole-job log text (the `<jobName>.txt` file at the archive root). */
    fun jobLog(jobName: String): String? = entries["$jobName.txt"]

    /**
     * Per-step log text. We try, in order:
     *   1. Exact match against `<jobName>/<stepNumber>_<sanitisedName>.txt`.
     *   2. Any file under `<jobName>/` that starts with `<stepNumber>_`.
     *
     * Step 2 matters because GitHub sanitises the file name (replacing `/`, control chars,
     * sometimes spaces) so the API step name doesn't always reproduce the file name.
     * Falling back to "match by number under the right job folder" is reliable because
     * each step number appears at most once per job.
     */
    fun stepLog(jobName: String, stepNumber: Int, stepName: String): String? {
        val sanitised = sanitiseForFileName(stepName)
        val exactKey = "$jobName/${stepNumber}_$sanitised.txt"
        entries[exactKey]?.let { return it }

        val numberPrefix = "$jobName/${stepNumber}_"
        return entries.entries
            .firstOrNull { it.key.startsWith(numberPrefix) && it.key.endsWith(".txt") }
            ?.value
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
     * Approximate the file-name sanitisation GitHub applies. Real rules are not fully
     * documented; this covers the common cases (slashes, NULs, leading/trailing whitespace).
     * The fallback in [stepLog] catches any case this misses.
     */
    private fun sanitiseForFileName(name: String): String =
        name.replace('/', '_').replace(' ', '_').trim()
}
