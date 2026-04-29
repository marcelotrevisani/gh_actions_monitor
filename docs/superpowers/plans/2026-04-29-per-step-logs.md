# Plan 3 — Per-Step Logs via Run Logs Zip + Platform-Test-Hang Fix

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Plan 2's heuristic step-filtering of the per-job text log with exact per-step logs sourced from GitHub's run-level zip endpoint, and along the way investigate-and-fix the regression that caused `BasePlatformTestCase` tests to hang since Plan 2.

**Architecture:** Add `GitHubClient.getRunLogsArchive(repo, runId)` that downloads `GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs` (a zip). Add a `RunLogsArchive` value class that wraps the zip bytes and exposes per-step text extraction by `(jobName, stepNumber, stepName)`. The repository caches the archive per run id. UI clicks on a step now fetch *that step's* `.txt` from the cached archive — no parsing, no heuristics, no wrong-section drift. Plan 2's `LogSectionParser` and section-picker dropdown are retired since the zip already gives us clean step boundaries. The per-job text endpoint (`getJobLogs`) stays in place and is used only for *in-progress* runs (where the zip endpoint isn't available yet).

**Tech Stack additions over Plan 2:** `java.util.zip.ZipInputStream` from the JDK (no new dependency). Existing Ktor + kotlinx-serialization + JUnit 5 stack continues to apply. `kotlinx-coroutines-core` remains `compileOnly` per Plan 2's classloader-collision fix.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`). Sections covered:
- *Components → `api/GitHubClient`* (adds `getRunLogsArchive` to the planned method surface).
- *Components → `LogStreamReader`* (deferred to Plan 4 — that class lands when polling/streaming arrive; Plan 3 only needs whole-archive fetching).
- *Representative data flows → B (Live logs for a running job)* (the *running* case still uses `getJobLogs`; the *completed* case switches to the zip).

**Plans 1+2 → Plan 3 carry-overs (lessons learned the hard way):**
- `kotlinx-coroutines-core` must stay **`compileOnly`**. Adding it as `implementation` (or letting Ktor pull it transitively) duplicates `kotlinx.coroutines.flow.StateFlow` between the platform's classloader and the plugin's, raising a `LinkageError` on plugin load. Each Ktor dep has `excludeCoroutines()` already in `build.gradle.kts`. Don't undo that.
- `RunRepository`'s production scope uses `Dispatchers.IO`. Tests inject `Dispatchers.Unconfined` via the third constructor parameter. Don't switch the production default to `Unconfined` again (it ran HTTP setup on the EDT and tripped `SlowOperations` in the live IDE).
- Tool-window panels (`RunListPanel`, `RunDetailPanel`, `LogViewerPanel`) implement `Disposable` and are registered with the `Content`'s disposer in `GhActionsToolWindowFactory`. New disposable panels added in this plan must follow the same pattern or they'll leak.
- `BasePlatformTestCase` does **not** load this project's `plugin.xml` extensions. Tests that need our `RunRepository` instantiate it directly with explicit dependencies — *not* via `project.getService(...)`.
- `BundledGithubAccountSource` uses `service<GHAccountManager>()` (the kotlin-extension form), not the deprecated `GHAccountManager.getInstance()`.

---

## File Structure

Created or modified by this plan:

```
gh_actions_pycharm/
└── src/
    ├── main/
    │   └── kotlin/com/example/ghactions/
    │       ├── api/
    │       │   ├── GitHubClient.kt                       (modify — add getRunLogsArchive)
    │       │   └── logs/
    │       │       └── RunLogsArchive.kt                 (new — zip parsing, step extraction)
    │       ├── repo/
    │       │   └── RunRepository.kt                      (modify — cache archive per run, expose step-fetch)
    │       └── ui/
    │           ├── LogViewerPanel.kt                     (modify — drop section parser; render extracted text directly)
    │           └── RunDetailPanel.kt                     (modify — route step clicks to step-specific log fetch)
    └── test/
        └── kotlin/com/example/ghactions/
            ├── api/
            │   └── logs/
            │       └── RunLogsArchiveTest.kt             (new — zip parsing edge cases)
            ├── api/
            │   └── GitHubClientGetArchiveTest.kt         (new — MockEngine HTTP shape test)
            └── repo/
                └── RunRepositoryArchiveTest.kt            (new — archive caching + step extraction integration)
```

Files deleted (Task 6): `src/main/kotlin/com/example/ghactions/ui/LogSectionParser.kt` and `src/test/kotlin/com/example/ghactions/ui/LogSectionParserTest.kt`. Plan 3's archive-based per-step rendering supersedes the heuristic parser entirely; the in-progress fallback path uses the whole-job text without filtering.

**File responsibility notes:**
- `RunLogsArchive.kt` is a value type — it wraps `ByteArray` (the zip body) and offers two read methods: `stepLog(jobName, stepNumber, stepName)` and `jobLog(jobName)`. No I/O; pure parsing. Easy to unit-test without MockEngine.
- `GitHubClientGetArchiveTest.kt` covers the HTTP shape only (URL, method, redirect handling, error mapping). Body parsing is `RunLogsArchiveTest.kt`'s job.
- `RunRepository.kt` grows two new flows (`logsState(jobId, stepNumber?)`) and one private cache field (`archivesByRun`). The existing `logsState(jobId)` for whole-job logs stays for the in-progress fallback path.
- The platform-test investigation (Task 1) is research-style: it produces either a fix commit or a documented workaround, not a new file.

---

## Conventions

- **Test framework:** still JUnit 5 (`org.junit.jupiter.api.*`) for everything. `BasePlatformTestCase` is **not** used in this plan — the only platform-y bits we touch are tested through direct instantiation per the Plan-2 lesson.
- **Zip handling:** `java.util.zip.ZipInputStream` only. Don't add any commons-compress / okio / etc. dependency.
- **Coroutines:** `kotlinx-coroutines-core` remains `compileOnly`. New methods on `GitHubClient` are `suspend fun`s using `withContext(Dispatchers.IO)` exactly like Plan 2's three methods.
- **Commits:** one per task, type-prefixed message, no `Co-Authored-By` trailer.

---

## Task 1: Investigate the `BasePlatformTestCase` hang

**Files:**
- Create: `docs/superpowers/notes/2026-04-29-base-platform-test-hang.md` (the findings)
- Possibly modify: `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`, `src/main/resources/META-INF/plugin.xml`, or `build.gradle.kts` (depending on what the investigation finds)

The Plan-2 sweep showed every `BasePlatformTestCase` subclass hanging until killed (no `BUILD SUCCESSFUL`, no test XML written). The most likely root causes, listed with the diagnostic step that confirms or rules each one out:

| # | Hypothesis | Diagnostic |
|---|---|---|
| 1 | `RunRepository`'s `(Project)` constructor (run by the IntelliJ DI container at first `getService`) holds the EDT/coroutine context too long. | Comment out `<projectService serviceImplementation="…RunRepository"/>` in `plugin.xml`. Run any `BasePlatformTestCase`. If it now passes, the problem is the secondary constructor. |
| 2 | `BundledGithubAccountSource.listAccounts()` blocks in the test fixture because `org.jetbrains.plugins.github`'s `GHAccountManager` is partially loaded. | Stub the `BundledGithubAccountSource` adapter to return `emptyList()` unconditionally. If tests pass, this is the culprit. |
| 3 | `kotlinx-coroutines-core` is still being pulled into the test classpath (the `excludeCoroutines()` helper applies to `implementation` but tests may resolve their own). | `./gradlew dependencies --configuration testRuntimeClasspath \| grep coroutines` and look for a duplicate. |
| 4 | The IntelliJ Platform Gradle plugin's `prepareTestSandbox` task is putting our jar into a path that triggers a recursive plugin-loading cycle. | Compare `build/idea-sandbox/IC-2024.3/plugins-test/gh-actions-monitor/lib/` to the runtime sandbox's `plugins/gh-actions-monitor/lib/`. They should differ only in absence of the leaked `kotlinx-coroutines-*.jar` files; if both have it, the test-classpath exclusion is broken. |

- [ ] **Step 1: Snapshot the current state**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.auth.PluginSettingsTest" 2>&1 | tail -10`
Expected: hangs or fails. If it now *passes* — great, the fix that landed during Plan 2 already resolved this. Skip to Step 7 and document that.

- [ ] **Step 2: Run hypothesis #3 first (cheap)**

Run: `./gradlew --no-daemon dependencies --configuration testRuntimeClasspath 2>&1 | grep -E "kotlinx-coroutines"`
Expected: at most one `kotlinx-coroutines-core-jvm:1.x.x` entry. If multiple versions appear, that's a duplicate that needs an `exclude` in the test deps.

If duplicates: edit `build.gradle.kts` test block to match the existing `excludeCoroutines()` pattern:
```kotlin
testImplementation("io.ktor:ktor-client-mock:2.3.13") { excludeCoroutines() }
```
…and re-run Step 1. If it passes now, commit with `fix(build): exclude transitive kotlinx-coroutines from test classpath` and the investigation is done.

- [ ] **Step 3: Run hypothesis #1 (most likely structural cause)**

Edit `src/main/resources/META-INF/plugin.xml`. Comment out the `RunRepository` registration:
```xml
<!-- <projectService serviceImplementation="com.example.ghactions.repo.RunRepository"/> -->
```

Run Step 1's command. If tests pass, the secondary constructor is hanging during DI initialization. The fix: keep the registration but make the secondary constructor lazy — replace the property-supplier `clientFactory = { ProductionClientFactory.create(project) }` with a memoized version that doesn't touch any platform service until first refresh.

In `RunRepository.kt`, change:
```kotlin
constructor(project: Project) : this(
    boundRepo = { project.getService(com.example.ghactions.repo.RepoBinding::class.java).current },
    clientFactory = { ProductionClientFactory.create(project) }
)
```
to:
```kotlin
constructor(project: Project) : this(
    project = project,
    boundRepoSupplier = { project.getService(com.example.ghactions.repo.RepoBinding::class.java).current }
)

/** Lazy production constructor — touches platform services only on first refresh. */
private constructor(
    project: Project,
    boundRepoSupplier: () -> com.example.ghactions.events.BoundRepo?
) : this(
    boundRepo = boundRepoSupplier,
    clientFactory = { ProductionClientFactory.create(project) }
)
```

This pattern is structurally identical at runtime, but proves out whether DI eagerness was the issue. If it still hangs after this change, revert and proceed to Step 4.

Restore the `plugin.xml` registration and verify Step 1 passes. Commit with `fix(repo): defer RunRepository production wiring to first refresh`.

- [ ] **Step 4: Run hypothesis #2 (only if #1 didn't fix it)**

Edit `src/main/kotlin/com/example/ghactions/auth/GitHubAccountResolver.kt`. Replace the body of `BundledGithubAccountSource.listAccounts()` with:
```kotlin
override fun listAccounts(): List<IdeAccountInfo> {
    if (java.lang.Boolean.getBoolean("gh.actions.test.no-ide-accounts")) return emptyList()
    return try { /* existing body */ } catch (e: Throwable) { /* existing fallback */ }
}
```

Add `-Dgh.actions.test.no-ide-accounts=true` to the test JVM args in `build.gradle.kts`:
```kotlin
tasks.test { systemProperty("gh.actions.test.no-ide-accounts", "true") }
```

Run Step 1's command. If it passes, the `service<GHAccountManager>()` call in `BasePlatformTestCase` was hanging. The system-property gate means production behaviour is unchanged. Commit with `fix(auth): bypass IDE-account lookup in test fixture to avoid bundled-plugin init hang`.

- [ ] **Step 5: Run hypothesis #4 (last resort)**

Compare both sandbox plugin folders:
```bash
ls -la build/idea-sandbox/IC-2024.3/plugins/gh-actions-monitor/lib/ | grep coroutines
ls -la build/idea-sandbox/IC-2024.3/plugins-test/gh-actions-monitor/lib/ | grep coroutines
```
Both should return zero matches. If `plugins-test` has coroutines jars, the test-sandbox prepare task is bypassing our exclusion. Add an explicit exclusion to `prepareTestSandbox`:
```kotlin
tasks.named("prepareTestSandbox") {
    doFirst {
        val out = file("build/idea-sandbox/IC-2024.3/plugins-test/gh-actions-monitor/lib")
        out.listFiles()?.filter { it.name.startsWith("kotlinx-coroutines-") }?.forEach { it.delete() }
    }
}
```
Run Step 1's command. Commit if successful.

- [ ] **Step 6: If none of the above fixed it**

Document the unresolved investigation in `docs/superpowers/notes/2026-04-29-base-platform-test-hang.md` with:
- What was attempted (the four hypotheses).
- Where each one rules in or out the cause.
- The current workaround: keep relying on direct-instantiation tests for our project services; the four `BasePlatformTestCase` subclasses (`PluginSettingsTest`, `PatStorageTest`, `RepoBindingTest`, `ToolWindowFactoryTest`) stay as-is and can be run individually if needed (they do work in isolation when no other tests precede them, per the Plan-1 sweep).

Commit with `docs: record platform-test hang investigation findings`.

- [ ] **Step 7: Verify the rest of the suite still passes**

Whatever Steps 1–6 changed, run:
```
./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.*" --tests "com.example.ghactions.api.dto.*" --tests "com.example.ghactions.domain.*" --tests "com.example.ghactions.repo.RunRepositoryTest" --tests "com.example.ghactions.ui.LogSectionParserTest"
```
Expected: 44+ tests pass.

---

## Task 2: `RunLogsArchive` — zip parsing

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/api/logs/RunLogsArchive.kt`
- Test: `src/test/kotlin/com/example/ghactions/api/logs/RunLogsArchiveTest.kt`

A pure-parsing value type around the zip bytes. Two public read methods, both returning `String?` (null if not found in the archive).

GitHub's archive structure (verified against actual responses):
```
<jobName>.txt                              # whole-job log (newline-separated text)
<jobName>/                                 # per-step folder
  <stepNumber>_<sanitizedStepName>.txt     # one file per step
```

The step number is 1-indexed. The step name is sanitized: spaces become single spaces, `/` becomes `_`, control characters dropped. Real-world examples: `1_Set up job.txt`, `2_Run actions_checkout@v4.txt`. We match by step number plus an *exact-or-fuzzy* name comparison (matching strategy is in the implementation below).

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.logs.RunLogsArchiveTest"`
Expected: FAIL with `unresolved reference: RunLogsArchive`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
        name.replace('/', '_').replace(' ', '_').trim()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.logs.RunLogsArchiveTest"`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/logs/RunLogsArchive.kt \
        src/test/kotlin/com/example/ghactions/api/logs/RunLogsArchiveTest.kt
git commit -m "feat: add RunLogsArchive zip parser with per-step extraction"
```

---

## Task 3: `GitHubClient.getRunLogsArchive`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`
- Create: `src/test/kotlin/com/example/ghactions/api/GitHubClientGetArchiveTest.kt`

Adds a fourth method to `GitHubClient`. It downloads the archive bytes — `RunLogsArchive` (Task 2) does the parsing.

GitHub's endpoint for completed runs returns `302 Found` with a `Location:` to a signed download URL. Ktor follows redirects by default; the body we get back is the raw zip. For in-progress runs the endpoint returns `404 Not Found` — our caller (`RunRepository`, Task 4) handles this by falling back to the per-job text log.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GitHubClientGetArchiveTest {

    private val auth = AuthSource.Pat("https://api.github.com", "ghp_x")
    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private fun fakeZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("build.txt"))
            zos.write("log content".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `getRunLogsArchive issues GET to actions runs logs endpoint`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val zipBytes = fakeZip()
        val engine = MockEngine { req ->
            captured += req
            respond(zipBytes, HttpStatusCode.OK, headersOf("Content-Type", "application/zip"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.getRunLogsArchive(repo, RunId(30433642L))

        assertEquals("GET", captured[0].method.value)
        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/actions/runs/30433642/logs",
            captured[0].url.toString()
        )
    }

    @Test
    fun `getRunLogsArchive returns the response body as bytes`() = runTest {
        val zipBytes = fakeZip()
        val engine = MockEngine { _ ->
            respond(zipBytes, HttpStatusCode.OK, headersOf("Content-Type", "application/zip"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        val bytes = client.getRunLogsArchive(repo, RunId(30433642L))
        assertContentEquals(zipBytes, bytes)
    }

    @Test
    fun `getRunLogsArchive throws on 404 (in-progress run)`() = runTest {
        val engine = MockEngine { _ -> respond("Not Found", HttpStatusCode.NotFound) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.getRunLogsArchive(repo, RunId(99L))
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(404, e.status)
        }
    }

    @Test
    fun `getRunLogsArchive throws on 5xx`() = runTest {
        val engine = MockEngine { _ -> respond("server error", HttpStatusCode.InternalServerError) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.getRunLogsArchive(repo, RunId(1L))
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(500, e.status)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubClientGetArchiveTest"`
Expected: FAIL with `unresolved reference: getRunLogsArchive`.

- [ ] **Step 3: Add the method to `GitHubClient.kt`**

Append inside the `class GitHubClient` body, between `getJobLogs` and `close()`:

```kotlin
    /**
     * `GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs`. Downloads the entire run's
     * log archive as a zip. GitHub redirects to a signed download URL — Ktor follows the
     * redirect transparently, so the body we return is the raw zip bytes from the final
     * response.
     *
     * Throws [GitHubApiException] with status 404 for in-progress runs (the archive is
     * only available once the run completes). Callers should fall back to [getJobLogs]
     * for that case.
     */
    suspend fun getRunLogsArchive(repo: BoundRepo, runId: com.example.ghactions.domain.RunId): ByteArray =
        withContext(Dispatchers.IO) {
            val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/logs")
            if (!response.status.isSuccess()) {
                throw GitHubApiException(
                    status = response.status.value,
                    message = "GET run logs archive failed: ${response.bodyAsText().take(200)}"
                )
            }
            response.body<ByteArray>()
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubClientGetArchiveTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/GitHubClient.kt \
        src/test/kotlin/com/example/ghactions/api/GitHubClientGetArchiveTest.kt
git commit -m "feat: add GitHubClient.getRunLogsArchive"
```

---

## Task 4: `RunRepository` — archive caching + step extraction

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`
- Modify: `src/test/kotlin/com/example/ghactions/repo/RunRepositoryTest.kt`
- Create: `src/test/kotlin/com/example/ghactions/repo/RunRepositoryArchiveTest.kt`

The repository gains:
- A new private cache `archivesByRun: ConcurrentHashMap<RunId, RunLogsArchive>`.
- A new public method `refreshStepLog(runId, jobName, stepNumber, stepName)` that fetches the archive (cache-or-network), extracts the step's text, and surfaces the result as a new `LogState` keyed on `(jobId, stepNumber)`.
- An overload `logsState(jobId, stepNumber)` returning the `StateFlow<LogState>` for that key.
- Existing `refreshLogs(jobId)` and `logsState(jobId)` stay untouched — they're the in-progress-run fallback path used by `RunDetailPanel` when the user clicks a *job* (not a step) or when the archive 404s.

The implementation also gracefully falls back to the per-job text log when `getRunLogsArchive` throws 404. That keeps the in-progress experience working.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/example/ghactions/repo/RunRepositoryArchiveTest.kt`:

```kotlin
package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RunRepositoryArchiveTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")
    private fun unconfinedScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun zipWith(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((p, b) in entries) {
                zos.putNextEntry(ZipEntry(p)); zos.write(b.toByteArray()); zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `refreshStepLog fetches the archive, extracts the step text, transitions Loaded`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getRunLogsArchive(repo, RunId(1L)) } returns zipWith(
            mapOf("build/3_Run swift test.txt" to "tests output")
        )
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshStepLog(RunId(1L), JobId(7L), jobName = "build", stepNumber = 3, stepName = "Run swift test")

        val state = repository.logsState(JobId(7L), stepNumber = 3).value
        assertIs<LogState.Loaded>(state)
        assertEquals("tests output", state.text)
    }

    @Test
    fun `refreshStepLog reuses the cached archive on a second call to the same run`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getRunLogsArchive(repo, RunId(1L)) } returns zipWith(
            mapOf(
                "build/1_Set up job.txt" to "setup output",
                "build/3_Run swift test.txt" to "tests output"
            )
        )
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshStepLog(RunId(1L), JobId(7L), "build", 1, "Set up job")
        repository.refreshStepLog(RunId(1L), JobId(7L), "build", 3, "Run swift test")

        coVerify(exactly = 1) { client.getRunLogsArchive(repo, RunId(1L)) }
    }

    @Test
    fun `refreshStepLog falls back to getJobLogs when archive returns 404`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getRunLogsArchive(repo, RunId(1L)) } throws GitHubApiException(404, "Not Found")
        coEvery { client.getJobLogs(repo, JobId(7L)) } returns "fallback in-progress text"
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshStepLog(RunId(1L), JobId(7L), "build", 3, "Run swift test")

        val state = repository.logsState(JobId(7L), stepNumber = 3).value
        assertIs<LogState.Loaded>(state)
        assertEquals("fallback in-progress text", state.text)
        coVerify { client.getJobLogs(repo, JobId(7L)) }
    }

    @Test
    fun `refreshStepLog surfaces non-404 errors as Error state`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getRunLogsArchive(repo, RunId(1L)) } throws GitHubApiException(500, "boom")
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshStepLog(RunId(1L), JobId(7L), "build", 3, "Run swift test")

        val state = repository.logsState(JobId(7L), stepNumber = 3).value
        assertIs<LogState.Error>(state)
        assertEquals(500, state.httpStatus)
    }

    @Test
    fun `refreshStepLog with a step not in the archive surfaces a clear error`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getRunLogsArchive(repo, RunId(1L)) } returns zipWith(
            mapOf("build/1_Set up job.txt" to "x")  // step 99 not in archive
        )
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshStepLog(RunId(1L), JobId(7L), "build", 99, "Run nothing")

        val state = repository.logsState(JobId(7L), stepNumber = 99).value
        assertIs<LogState.Error>(state)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.repo.RunRepositoryArchiveTest"`
Expected: FAIL with unresolved references (`refreshStepLog`, `logsState(jobId, stepNumber)` overload).

- [ ] **Step 3: Add the new repository surface to `RunRepository.kt`**

Add the imports near the existing ones (top of file):
```kotlin
import com.example.ghactions.api.logs.RunLogsArchive
```

Inside the `class RunRepository` body, after the existing `_logsByJob` block, add:
```kotlin
    /** Per-(jobId, stepNumber) state flow. Plan 3 wires step clicks here. */
    private val _stepLogs = ConcurrentHashMap<Pair<JobId, Int>, MutableStateFlow<LogState>>()
    fun logsState(jobId: JobId, stepNumber: Int): StateFlow<LogState> =
        _stepLogs.computeIfAbsent(jobId to stepNumber) { MutableStateFlow(LogState.Idle) }.asStateFlow()

    /** Cached run-logs zips keyed by run id. Refresh evicts on `invalidateAll()`. */
    private val _archivesByRun = ConcurrentHashMap<RunId, RunLogsArchive>()
```

Inside the same class, also add:
```kotlin
    fun refreshStepLog(
        runId: RunId,
        jobId: JobId,
        jobName: String,
        stepNumber: Int,
        stepName: String
    ): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            stepFlow(jobId, stepNumber).value =
                LogState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        stepFlow(jobId, stepNumber).value = LogState.Loading

        val archive = try {
            _archivesByRun.getOrPut(runId) { RunLogsArchive(client.getRunLogsArchive(repo, runId)) }
        } catch (e: GitHubApiException) {
            if (e.status == 404) {
                // In-progress runs don't have an archive yet — fall back to the per-job text endpoint.
                stepFlow(jobId, stepNumber).value = try {
                    LogState.Loaded(client.getJobLogs(repo, jobId))
                } catch (e2: GitHubApiException) {
                    log.warn("getJobLogs fallback failed: status=${e2.status}", e2)
                    LogState.Error(e2.status, e2.message ?: "API error")
                } catch (e2: Throwable) {
                    log.warn("getJobLogs fallback threw unexpectedly", e2)
                    LogState.Error(null, e2.message ?: e2::class.java.simpleName)
                }
                return@launch
            }
            log.warn("getRunLogsArchive failed: status=${e.status}", e)
            stepFlow(jobId, stepNumber).value = LogState.Error(e.status, e.message ?: "API error")
            return@launch
        } catch (e: Throwable) {
            log.warn("getRunLogsArchive threw unexpectedly", e)
            stepFlow(jobId, stepNumber).value =
                LogState.Error(null, e.message ?: e::class.java.simpleName)
            return@launch
        }

        val text = archive.stepLog(jobName, stepNumber, stepName)
        stepFlow(jobId, stepNumber).value = if (text != null) {
            LogState.Loaded(text)
        } else {
            LogState.Error(
                null,
                "Step $stepNumber (\"$stepName\") not found in run logs archive for job '$jobName'."
            )
        }
    }

    private fun stepFlow(jobId: JobId, stepNumber: Int): MutableStateFlow<LogState> =
        _stepLogs.computeIfAbsent(jobId to stepNumber) { MutableStateFlow(LogState.Idle) }
```

Also update `invalidateAll()` to clear archive cache and step flows:
```kotlin
    fun invalidateAll() {
        _runsState.value = RunListState.Idle
        _jobsByRun.values.forEach { it.value = JobsState.Idle }
        _logsByJob.values.forEach { it.value = LogState.Idle }
        _stepLogs.values.forEach { it.value = LogState.Idle }
        _archivesByRun.clear()
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.repo.RunRepositoryArchiveTest"`
Expected: PASS, 5 tests.

Also re-run the existing `RunRepositoryTest` to confirm nothing regressed:
```
./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.repo.RunRepositoryTest"
```
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/repo/RunRepository.kt \
        src/test/kotlin/com/example/ghactions/repo/RunRepositoryArchiveTest.kt
git commit -m "feat(repo): cache run-logs archive and expose per-step log state"
```

---

## Task 5: Wire the UI to use step-specific logs

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt`
- Modify: `src/main/kotlin/com/example/ghactions/ui/LogViewerPanel.kt`

When the user clicks a step, the panel calls `repository.refreshStepLog(...)` and observes `repository.logsState(jobId, stepNumber)`. The `LogViewerPanel` is no longer responsible for parsing `##[group]` markers — it just renders whatever text the repository hands it. The section-picker dropdown and `LogSectionParser` invocation are removed from the viewer.

The *Show timestamps* toggle stays — it's a UX win that's independent of the source of the log text.

- [ ] **Step 1: Strip section parsing from `LogViewerPanel.kt`**

Replace the file's contents:

```kotlin
package com.example.ghactions.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Read-only viewer for log text. Plan 3: no section parsing — the [RunRepository] now
 * hands us pre-extracted per-step text via the run-logs zip archive, so [setText] just
 * renders it. The only header control is the *Show timestamps* toggle.
 *
 * (ANSI color rendering is Plan 5.)
 */
class LogViewerPanel : JPanel(BorderLayout()) {

    private var rawText: String = ""

    private val textArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        margin = JBUI.insets(4, 8)
    }

    private val timestampToggle = JBCheckBox("Show timestamps", false).apply {
        addItemListener { renderText() }
    }

    private val statusLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        border = JBUI.Borders.empty(2, 6)
        add(timestampToggle)
        add(statusLabel)
    }

    private val scrollPane = JBScrollPane(textArea).apply {
        border = JBUI.Borders.empty()
    }

    init {
        border = JBUI.Borders.empty()
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /** Replace the displayed text. Safe from any thread; marshals to EDT. */
    fun setText(text: String) {
        rawText = text
        statusLabel.text = ""
        renderText()
    }

    /** Set a status hint shown next to the timestamps toggle (e.g., 'Step 3 · Run swift test'). */
    fun setStatus(text: String) {
        statusLabel.text = text
    }

    fun clear() {
        setText("")
        statusLabel.text = ""
    }

    private fun renderText() {
        ApplicationManager.getApplication().invokeLater {
            val visible = if (timestampToggle.isSelected) {
                rawText
            } else {
                rawText.lineSequence().joinToString("\n") { stripTimestamp(it) }
            }
            textArea.text = visible
            textArea.caretPosition = 0
        }
    }

    private fun stripTimestamp(line: String): String =
        TIMESTAMP_RE.replaceFirst(line, "")

    private companion object {
        private val TIMESTAMP_RE = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z\s""")
    }
}
```

- [ ] **Step 2: Remove the section picker references and route step clicks to the new repository method in `RunDetailPanel.kt`**

The current `RunDetailPanel.kt` calls `logViewer.showStep(stepNumber, stepName)` after a step click. Replace that with the new repository-driven flow.

In `RunDetailPanel.kt`, find the existing tree click handler:
```kotlin
addTreeSelectionListener { e ->
    val node = e.path.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
    when (val payload = node.userObject) {
        is Job -> {
            pendingStepFocus = null
            showJobLogs(payload)
        }
        is Step -> {
            val parentJob = (node.parent as? DefaultMutableTreeNode)?.userObject as? Job
                ?: return@addTreeSelectionListener
            pendingStepFocus = StepFocus(parentJob.id, payload.number, payload.name)
            showJobLogs(parentJob)
        }
        else -> Unit
    }
}
```

Replace it with:
```kotlin
addTreeSelectionListener { e ->
    val node = e.path.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
    when (val payload = node.userObject) {
        is Job -> showJobLogs(payload)
        is Step -> {
            val parentJob = (node.parent as? DefaultMutableTreeNode)?.userObject as? Job
                ?: return@addTreeSelectionListener
            val run = rootNode.userObject as? Run ?: return@addTreeSelectionListener
            showStepLogs(run, parentJob, payload)
        }
        else -> Unit
    }
}
```

Remove the `StepFocus` data class and `pendingStepFocus` field — they're no longer needed.

Replace `showJobLogs` with two methods:
```kotlin
private fun showJobLogs(job: Job) {
    repository.refreshLogs(job.id)
    currentLogFlowJob?.cancel()
    currentLogFlowJob = scope.launch {
        repository.logsState(job.id).collect { state ->
            renderLogState(state, statusHint = "")
        }
    }
}

private fun showStepLogs(run: Run, job: Job, step: Step) {
    repository.refreshStepLog(
        runId = run.id,
        jobId = job.id,
        jobName = job.name,
        stepNumber = step.number,
        stepName = step.name
    )
    currentLogFlowJob?.cancel()
    currentLogFlowJob = scope.launch {
        repository.logsState(job.id, stepNumber = step.number).collect { state ->
            renderLogState(state, statusHint = "Step ${step.number} · ${step.name}")
        }
    }
}

private fun renderLogState(state: LogState, statusHint: String) {
    when (state) {
        is LogState.Idle -> logViewer.clear()
        is LogState.Loading -> { logViewer.setText("(loading logs…)"); logViewer.setStatus(statusHint) }
        is LogState.Loaded -> { logViewer.setText(state.text); logViewer.setStatus(statusHint) }
        is LogState.Error -> {
            logViewer.setText("Failed${state.httpStatus?.let { " ($it)" } ?: ""}: ${state.message}")
            logViewer.setStatus("")
        }
    }
}
```

Also remove the import for `JobId` from the top of the file (no longer used) and any references to `StepFocus`.

- [ ] **Step 3: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Re-run the full non-platform suite**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.*" --tests "com.example.ghactions.api.dto.*" --tests "com.example.ghactions.api.logs.*" --tests "com.example.ghactions.domain.*" --tests "com.example.ghactions.repo.*"`
Expected: all tests pass (the existing 44 plus this plan's new ones).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/LogViewerPanel.kt \
        src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt
git commit -m "feat(ui): route step clicks to per-step logs from run archive"
```

---

## Task 6: Retire `LogSectionParser` (delete or downgrade)

**Files:**
- Delete: `src/main/kotlin/com/example/ghactions/ui/LogSectionParser.kt`
- Delete: `src/test/kotlin/com/example/ghactions/ui/LogSectionParserTest.kt`

The section parser was a Plan-2 stop-gap. Plan 3's archive-based per-step rendering supersedes it. The fallback path for in-progress runs uses the whole-job text *without* filtering — there's no use case left for the parser.

- [ ] **Step 1: Confirm no remaining references**

Run: `grep -rn "LogSectionParser\|LogSection\b" src/`
Expected: zero matches (after Task 5's `LogViewerPanel.kt` rewrite removed the last consumer).

- [ ] **Step 2: Delete the files**

```bash
rm src/main/kotlin/com/example/ghactions/ui/LogSectionParser.kt
rm src/test/kotlin/com/example/ghactions/ui/LogSectionParserTest.kt
```

- [ ] **Step 3: Verify build**

Run: `./gradlew --no-daemon compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A src/main/kotlin/com/example/ghactions/ui/ src/test/kotlin/com/example/ghactions/ui/
git commit -m "refactor(ui): remove LogSectionParser (superseded by archive-based step logs)"
```

---

## Task 7: Manual smoke test (`./gradlew runIde`)

**Files:** none.

Plan 2's smoke test verified that runs/jobs/logs render at all. Plan 3's smoke test verifies that step clicks now show *exactly* that step's output, with zero parsing drift.

- [ ] **Step 1: Launch the dev IDE**

Run: `./gradlew --no-daemon runIde`
Expected: sandbox IDE opens. (Note: settings persist between runs *only if* the sandbox config dir is preserved. Don't delete `build/idea-sandbox` between iterations — that wipes the PAT. If the PAT is missing, re-enter it in *Settings → Tools → GitHub Actions Monitor*.)

- [ ] **Step 2: Open a project bound to a real GitHub repo**

Open the same project used in Plan-2's smoke test. Confirm the tool window populates with runs after clicking *Refresh*.

- [ ] **Step 3: Click a *completed* run, then click any step**

Expected: the log viewer shows *only that step's* output — full content, no preamble from earlier steps, no truncation. The status next to the *Show timestamps* checkbox reads *"Step N · &lt;step name&gt;"*.

Pick at least three different steps to confirm consistent behaviour:
- A *"Set up job"* (step 1) — should show runner-setup output.
- A user step like *"Run swift test"* — should show the full command output incl. test results.
- A *"Post Run actions/checkout@v4"* — should show the cleanup output.

- [ ] **Step 4: Click an *in-progress* run and click a step**

Expected: log viewer shows the whole-job text (the fallback path). Status reads the same step label. The behaviour is degraded but functional.

- [ ] **Step 5: Click the *job* (not a step)**

Expected: log viewer shows the whole-job text (this path is unchanged from Plan 2 — `repository.refreshLogs(jobId)` and `logsState(jobId)` continue to back the job-level click).

- [ ] **Step 6: Toggle *Show timestamps***

Expected: the leading `2026-…Z ` prefixes appear/disappear in real time.

- [ ] **Step 7: Document any deviations**

If anything misbehaves (e.g., a step shows empty content for a particular workflow), note the workflow shape and step name in `docs/superpowers/notes/2026-04-29-step-logs-smoke-deviations.md` and commit it. We address it in Plan 4 unless it blocks usability.

```bash
git add docs/superpowers/notes/  # only if a notes file was created
git commit -m "docs: smoke test deviations for plan 3"
```

---

## Task 8: Final sweep + merge

**Files:** none — verification and merge only.

- [ ] **Step 1: Full non-platform test run**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.*" --tests "com.example.ghactions.api.dto.*" --tests "com.example.ghactions.api.logs.*" --tests "com.example.ghactions.domain.*" --tests "com.example.ghactions.repo.*"`
Expected: PASS. Plan 3 adds approximately 18 new tests (`RunLogsArchiveTest` 9, `GitHubClientGetArchiveTest` 4, `RunRepositoryArchiveTest` 5). Total cumulative non-platform tests should be 44 + 18 = ~62.

- [ ] **Step 2: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL. Updated zip at `build/distributions/`.

- [ ] **Step 3: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED against IDEA 2024.3 + 2025.1. Acceptable warnings: experimental-API uses (carry-over from Plans 1–2). New for this plan: any `ZipInputStream`-related warnings should be zero — it's a JDK API and shouldn't trip the verifier.

- [ ] **Step 4: Fast-forward merge to `main`**

```bash
git checkout main
git merge --ff-only feat/plan-3-per-step-logs
git log --oneline | head -10
```
Expected: clean fast-forward; no merge commit.

> If the merge isn't fast-forwardable, something else has landed on `main` since this plan's branch was cut. Investigate before forcing.

- [ ] **Step 5: Plan-level verification**

- All 8 tasks have green check-marks.
- `./gradlew test` passes the non-platform suites.
- `./gradlew buildPlugin` succeeds.
- `./gradlew verifyPlugin` succeeds.
- Manual smoke test (Task 7) all green.
- `git log --oneline` on `main` shows the expected sequence.

---

## What ships at the end of Plan 3

- `GitHubClient.getRunLogsArchive(repo, runId)` returns the zip body.
- `RunLogsArchive` parses zip → exposes `jobLog(name)`, `stepLog(jobName, num, name)`, `listJobs()`.
- `RunRepository`:
  - Caches archive bytes per run id.
  - Exposes `logsState(jobId, stepNumber)` and `refreshStepLog(...)`.
  - Falls back to `getJobLogs(text)` for in-progress runs (404 from archive endpoint).
  - `invalidateAll()` clears archive cache and step flows.
- UI: clicking a step routes to step-specific text, no parsing. Header status reads *"Step N · &lt;name&gt;"*.
- `LogSectionParser` retired.
- BasePlatformTestCase hangs either fixed or documented with the workaround.

What it does **not** yet do (deferred to later plans):
- Live polling — Plan 4.
- Rate-limit awareness — Plan 4.
- Log delta streaming for in-progress jobs — Plan 4.
- ANSI color rendering — Plan 5.
- PR-centric view — Plan 6+.

---

## Open questions / risks

1. **Archive size for huge runs.** Some workflows produce hundreds of MB of logs. We currently load the entire zip into memory as a `ByteArray`, then parse all entries into a Map. For Plan 3 this is acceptable (most CI runs are well under 50 MB), but the eviction policy will need tightening once Plan 4's polling refreshes archives more aggressively.
2. **GitHub's file-name sanitisation rules are not fully documented.** Task 2's fallback ("match by step number under the right job folder") covers the common cases. If someone reports a workflow where step extraction misses, the fix is to extend the sanitisation in `RunLogsArchive.sanitiseForFileName` or add a stronger fallback in `stepLog`.
3. **Test-hang fix may be brittle.** Task 1 covers four hypotheses. If one fixes the hang, document precisely what changed so future plan authors know not to undo it.
4. **In-progress fallback shows the *whole-job* text.** Step filtering is unavailable for in-progress runs. Plan 4's live-streaming work can revisit this — once we're polling logs, we can keep a parallel `LogStreamReader` for in-progress steps and re-introduce something like `LogSectionParser` *only* for that case.
