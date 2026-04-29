package com.example.ghactions.api.logs

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunLogsArchiveTest {

    /** Builds a synthetic zip in-memory with the given entries. */
    private fun buildZip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((path, body) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `jobLog returns the per-job text file`() {
        val zip = buildZip(mapOf(
            "build.txt" to "whole job log\nline 2",
            "build/1_Set up job.txt" to "setup output"
        ))
        val archive = RunLogsArchive(zip)
        assertEquals("whole job log\nline 2", archive.jobLog("build"))
    }

    @Test
    fun `jobLog returns null when the job is not in the archive`() {
        val zip = buildZip(mapOf("other.txt" to "x"))
        assertNull(RunLogsArchive(zip).jobLog("build"))
    }

    @Test
    fun `stepLog finds an exact-name step file`() {
        val zip = buildZip(mapOf(
            "build/1_Set up job.txt" to "step 1",
            "build/2_Run actions_checkout@v4.txt" to "step 2",
            "build/3_Run swift test.txt" to "step 3 output"
        ))
        val archive = RunLogsArchive(zip)
        assertEquals("step 3 output", archive.stepLog("build", stepNumber = 3, stepName = "Run swift test"))
    }

    @Test
    fun `stepLog matches by step number when name differs from the file`() {
        // GitHub sanitizes some characters in the file name. Step name in API may be
        // 'Run tests' but file is '4_Run swift test.txt'. We fall back to step number.
        val zip = buildZip(mapOf(
            "build/4_Run swift test.txt" to "tests output"
        ))
        val archive = RunLogsArchive(zip)
        assertEquals("tests output", archive.stepLog("build", stepNumber = 4, stepName = "Run tests"))
    }

    @Test
    fun `stepLog returns null when neither name nor number matches`() {
        val zip = buildZip(mapOf(
            "build/1_Set up job.txt" to "step 1"
        ))
        val archive = RunLogsArchive(zip)
        assertNull(archive.stepLog("build", stepNumber = 99, stepName = "Run nothing"))
    }

    @Test
    fun `stepLog handles step names with slashes (sanitised in file path)`() {
        val zip = buildZip(mapOf(
            "build/2_Run actions_checkout@v4.txt" to "checkout output"
        ))
        val archive = RunLogsArchive(zip)
        assertEquals(
            "checkout output",
            RunLogsArchive(zip).stepLog("build", stepNumber = 2, stepName = "Run actions/checkout@v4")
        )
    }

    @Test
    fun `listJobs returns each per-job log file's base name`() {
        val zip = buildZip(mapOf(
            "build.txt" to "x",
            "test.txt" to "x",
            "build/1_x.txt" to "y",  // not a job-level file
            "deploy.txt" to "x"
        ))
        val archive = RunLogsArchive(zip)
        assertEquals(setOf("build", "test", "deploy"), archive.listJobs().toSet())
    }

    @Test
    fun `parsing a malformed zip returns no jobs and null logs without throwing`() {
        val archive = RunLogsArchive(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertEquals(emptyList(), archive.listJobs())
        assertNull(archive.jobLog("anything"))
        assertNull(archive.stepLog("anything", 1, "anything"))
    }

    @Test
    fun `stepLog matches prefixed job folder (e g 0_build-and-test)`() {
        // GitHub sometimes emits job folders with a numeric index prefix.
        val zip = buildZip(mapOf(
            "0_build-and-test.txt" to "whole job log",
            "0_build-and-test/1_Set up job.txt" to "setup",
            "0_build-and-test/5_Run swift test.txt" to "tests output"
        ))
        val archive = RunLogsArchive(zip)
        assertEquals("tests output", archive.stepLog("build-and-test", stepNumber = 5, stepName = "Run tests"))
    }

    @Test
    fun `jobLog finds prefixed root file via fuzzy match`() {
        val zip = buildZip(mapOf("0_build-and-test.txt" to "whole log"))
        val archive = RunLogsArchive(zip)
        assertEquals("whole log", archive.jobLog("build-and-test"))
    }

    @Test
    fun `listJobs surfaces both root txt files and folder names`() {
        val zip = buildZip(mapOf(
            "0_build-and-test.txt" to "x",
            "0_build-and-test/1_Set up job.txt" to "y",
            "1_lint.txt" to "x"
        ))
        val archive = RunLogsArchive(zip)
        val jobs = archive.listJobs().toSet()
        assertTrue(jobs.contains("0_build-and-test"), "should include the prefixed root .txt; got $jobs")
        assertTrue(jobs.contains("1_lint"), "should include the second root .txt; got $jobs")
    }

    @Test
    fun `stepLog falls back to slicing the whole-job log by Run group markers`() {
        // No per-step files — only the root job log. This is the layout the user's
        // 'build-and-test' archive was emitting.
        val wholeLog = """
            ##[group]Runner Image Provisioner
            metadata
            ##[endgroup]
            ##[group]Run actions/checkout@v4
            with: stuff
            ##[endgroup]
            Syncing repository
            ##[group]Run swift build
            swift build
            ##[endgroup]
            Build complete
            ##[group]Run swift test
            swift test
            ##[endgroup]
            Test Suite passed
        """.trimIndent()
        val zip = buildZip(mapOf(
            "0_build-and-test.txt" to wholeLog,
            "build-and-test/system.txt" to "metadata"
        ))
        val archive = RunLogsArchive(zip)

        // The synthetic log has 3 user steps (checkout, build, swift test); the API
        // numbers them with "Set up job" as step 1, so the swift-test step is API
        // step 4. Positional fallback: stepNumber - 2 = 2, the 3rd start (index 2),
        // which is "Run swift test".
        val result = archive.stepLog("build-and-test", stepNumber = 4, stepName = "Run tests")
        assertTrue(result != null && result.contains("swift test"), "Expected swift test section, got: $result")
        assertTrue(result.contains("Test Suite passed"), "Expected post-endgroup output to be included, got: $result")
    }

    @Test
    fun `stepLog falls back to slicing matches by exact name when log group equals step name`() {
        val wholeLog = """
            ##[group]Run actions/checkout@v4
            with: stuff
            ##[endgroup]
            Syncing repository
            ##[group]Run user_step
            user output
            ##[endgroup]
            more output
        """.trimIndent()
        val zip = buildZip(mapOf("0_job.txt" to wholeLog))
        val archive = RunLogsArchive(zip)

        // Exact match (with the "Run " prefix included in API step.name).
        val result = archive.stepLog("job", stepNumber = 99, stepName = "Run user_step")
        assertTrue(result != null && result.contains("user output"), "Expected user_step section, got: $result")
    }

    @Test
    fun `bytes are not retained beyond construction-time parse`() {
        // Defensive: confirm that mutating the input array doesn't change the parse output.
        val original = buildZip(mapOf("build.txt" to "hello"))
        val mutable = original.copyOf()
        val archive = RunLogsArchive(mutable)
        assertEquals("hello", archive.jobLog("build"))
        // Zero out the input. If RunLogsArchive lazily reads from `mutable`, this would
        // change subsequent calls. It must not.
        mutable.fill(0x00)
        assertEquals("hello", archive.jobLog("build"))
    }
}
