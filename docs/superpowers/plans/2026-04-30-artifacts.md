# Plan 8 — Artifacts list + download

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the artifacts uploaded by a workflow run and let the user download them. The user explicitly asked for this during brainstorming ("see the artifacts and download them"); the spec lists Artifacts as one of the four sub-tabs of the run detail panel.

**Architecture:** A new `domain/Artifact.kt` value type plus a serializable DTO, two new `GitHubClient` methods (`listArtifacts(repo, runId)` and `downloadArtifact(repo, artifactId)`), repository state (`artifactsState(runId): StateFlow<ArtifactsState>`), and a new `ArtifactsPanel` Swing UI shown in a tab next to the existing log viewer in `RunDetailPanel`. `RunDetailPanel` swaps its `JPanel` second-component for a `JBTabbedPane` with two tabs: *Logs* (existing) and *Artifacts* (new). Download writes the zip into `PluginSettings.defaultDownloadDir` (or prompts the user when blank), then exposes a *Reveal in Finder/Explorer* action.

**Tech Stack:** Same as Plans 1–7 — Kotlin 2.0.21, Ktor 2.3.13, kotlinx-serialization 1.7.3, JUnit 5 + MockK + Ktor MockEngine. Swing for UI.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`):
- *Components → `RunDetailPanel`* — gains an Artifacts tab (third of four sub-tabs).
- *Settings → Default download directory* — already exists in `PluginSettings`; this plan makes it functional.

**Sections deliberately deferred:**
- *Annotations* sub-tab — separate plan.
- *Summary* sub-tab — separate plan.
- Per-artifact unzip / "Browse contents in IDE" — out of scope; plain zip download is enough for v1.
- Drag-from-list-to-Finder — out of scope.

**Plans 1–7 → Plan 8 carry-overs:**
- `kotlinx-coroutines-core` `compileOnly`. New repository code is suspend; tests inject `Dispatchers.Unconfined`.
- `service<X>()` for project services in production; tests construct `RunRepository` directly with mocks (existing pattern).
- Commits one per task, type-prefixed; **no `Co-Authored-By` trailer**.
- Existing repo conventions: `BoundRepo`, `GitHubApiException` / `RateLimitedException`, `ProductionClientFactory`.

---

## File Structure

```
gh_actions_pycharm/
└── src/
    ├── main/kotlin/com/example/ghactions/
    │   ├── domain/
    │   │   ├── Ids.kt                       (modify — add ArtifactId)
    │   │   └── Artifact.kt                  (new — domain type)
    │   ├── api/
    │   │   ├── dto/ArtifactDto.kt           (new)
    │   │   └── GitHubClient.kt              (modify — listArtifacts + downloadArtifact)
    │   ├── repo/
    │   │   └── RunRepository.kt             (modify — artifactsState + refreshArtifacts + downloadArtifactToFile)
    │   └── ui/
    │       ├── ArtifactsPanel.kt            (new)
    │       └── RunDetailPanel.kt            (modify — wrap log viewer + artifacts in JBTabbedPane)
    └── test/kotlin/com/example/ghactions/
        ├── api/
        │   └── GitHubClientArtifactsTest.kt (new — MockEngine tests)
        └── repo/
            └── RunRepositoryArtifactsTest.kt (new — refreshArtifacts state transitions)
```

**File responsibility notes:**
- `Artifact.kt` holds the pure domain type. No Ktor, no kotlinx-serialization.
- `ArtifactDto.kt` holds the on-the-wire `@Serializable` representation plus `toDomain()`. Same pattern as `RunDto`/`JobDto`.
- `ArtifactsPanel` is a self-contained Swing `JPanel` that observes `RunRepository.artifactsState(runId)`. It owns its own coroutine scope and is `Disposable` (chained via the tool-window `Content` disposer, same as `LogViewerPanel`).
- `RunDetailPanel` replaces its current bare log viewer with a tabbed view; the existing tree (top half) stays untouched.

---

## Conventions

- **Tests** stay JUnit 5 + `kotlin.test.*` + Ktor `MockEngine` for HTTP. The repository test uses `Dispatchers.Unconfined` (existing pattern in `RunRepositoryTest`).
- **Cumulative test target:** 125 (Plan 7) + 4 (`GitHubClient.listArtifacts/downloadArtifact`) + 3 (`RunRepository.refreshArtifacts`) = ~132.
- **One commit per task**, type-prefixed.

---

## Task 1: Domain types + DTO

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/domain/Ids.kt`
- Create: `src/main/kotlin/com/example/ghactions/domain/Artifact.kt`
- Create: `src/main/kotlin/com/example/ghactions/api/dto/ArtifactDto.kt`

No tests in this task — Task 2's `GitHubClientArtifactsTest` exercises `toDomain()` end-to-end.

- [ ] **Step 1: Add `ArtifactId`**

Modify `src/main/kotlin/com/example/ghactions/domain/Ids.kt`. Add at the bottom:

```kotlin
@JvmInline
value class ArtifactId(val value: Long) {
    override fun toString(): String = value.toString()
}
```

- [ ] **Step 2: Create `Artifact.kt`**

Create `src/main/kotlin/com/example/ghactions/domain/Artifact.kt`:

```kotlin
package com.example.ghactions.domain

import java.time.Instant

/**
 * One artifact uploaded by a workflow run. GitHub keeps these for ~90 days by default,
 * after which `expired = true` and the download endpoint returns 410.
 */
data class Artifact(
    val id: ArtifactId,
    val name: String,
    val sizeBytes: Long,
    val expired: Boolean,
    val createdAt: Instant?
)
```

- [ ] **Step 3: Create the DTO**

Create `src/main/kotlin/com/example/ghactions/api/dto/ArtifactDto.kt`:

```kotlin
package com.example.ghactions.api.dto

import com.example.ghactions.domain.Artifact
import com.example.ghactions.domain.ArtifactId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ArtifactDto(
    val id: Long,
    val name: String,
    @SerialName("size_in_bytes") val sizeInBytes: Long,
    val expired: Boolean,
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain() = Artifact(
        id = ArtifactId(id),
        name = name,
        sizeBytes = sizeInBytes,
        expired = expired,
        createdAt = createdAt?.let(Instant::parse)
    )
}

/** GitHub's list-artifacts envelope: `{ total_count, artifacts: [...] }`. */
@Serializable
data class ListArtifactsResponse(
    @SerialName("total_count") val totalCount: Int,
    val artifacts: List<ArtifactDto>
)
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/domain/Ids.kt \
        src/main/kotlin/com/example/ghactions/domain/Artifact.kt \
        src/main/kotlin/com/example/ghactions/api/dto/ArtifactDto.kt
git commit -m "feat(domain): add Artifact value type and DTO"
```

---

## Task 2: `GitHubClient.listArtifacts` and `downloadArtifact`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`
- Create: `src/test/kotlin/com/example/ghactions/api/GitHubClientArtifactsTest.kt`

GitHub endpoints:
- `GET /repos/{owner}/{repo}/actions/runs/{run_id}/artifacts` → `ListArtifactsResponse`
- `GET /repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip` → `302` redirect to a signed URL → final `200` with the zip bytes (Ktor follows redirects).

- [ ] **Step 1: Write failing tests**

Create `src/test/kotlin/com/example/ghactions/api/GitHubClientArtifactsTest.kt`:

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.ArtifactId
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubClientArtifactsTest {

    private val repo = BoundRepo(
        host = "https://api.github.com",
        owner = "octocat",
        repo = "hello-world"
    )

    private fun client(engine: MockEngine): GitHubClient {
        val http = GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "x"),
            engine = engine
        )
        return GitHubClient(http)
    }

    @Test
    fun `listArtifacts maps DTO to domain`() = runTest {
        val body = """
            {
              "total_count": 2,
              "artifacts": [
                {"id": 11, "name": "logs", "size_in_bytes": 1024, "expired": false, "created_at": "2026-04-01T12:00:00Z"},
                {"id": 22, "name": "coverage", "size_in_bytes": 4096, "expired": true, "created_at": "2026-04-01T12:01:00Z"}
              ]
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val artifacts = client(engine).listArtifacts(repo, RunId(99))
        assertEquals(2, artifacts.size)
        assertEquals(ArtifactId(11), artifacts[0].id)
        assertEquals("logs", artifacts[0].name)
        assertEquals(1024L, artifacts[0].sizeBytes)
        assertFalse(artifacts[0].expired)
        assertTrue(artifacts[1].expired)
    }

    @Test
    fun `listArtifacts surfaces 404 as GitHubApiException`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "{}", status = HttpStatusCode.NotFound)
        }
        val ex = assertFailsWith<GitHubApiException> {
            client(engine).listArtifacts(repo, RunId(99))
        }
        assertEquals(404, ex.status)
    }

    @Test
    fun `downloadArtifact returns the zip bytes`() = runTest {
        val payload = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x09, 0x09)  // "PK" + a few bytes
        val engine = MockEngine { _ ->
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Zip.toString()))
            )
        }
        val bytes = client(engine).downloadArtifact(repo, ArtifactId(11))
        assertContentEquals(payload, bytes)
    }

    @Test
    fun `downloadArtifact maps 410 to GitHubApiException`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "{}", status = HttpStatusCode.Gone)
        }
        val ex = assertFailsWith<GitHubApiException> {
            client(engine).downloadArtifact(repo, ArtifactId(11))
        }
        assertEquals(410, ex.status)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.api.GitHubClientArtifactsTest`
Expected: FAIL — `listArtifacts` / `downloadArtifact` unresolved.

- [ ] **Step 3: Implement the methods**

Modify `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`. Inside the `GitHubClient` class (alongside the existing methods), add:

```kotlin
/**
 * `GET /repos/{owner}/{repo}/actions/runs/{run_id}/artifacts`. Returns the run's
 * artifacts. Expired artifacts are still listed (with `expired = true`) but their
 * download endpoint returns 410.
 */
suspend fun listArtifacts(
    repo: BoundRepo,
    runId: com.example.ghactions.domain.RunId
): List<com.example.ghactions.domain.Artifact> = withContext(Dispatchers.IO) {
    val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/artifacts")
    if (!response.status.isSuccess()) fail(response, "artifacts")
    response.body<com.example.ghactions.api.dto.ListArtifactsResponse>().artifacts.map { it.toDomain() }
}

/**
 * `GET /repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip`. Returns the zip
 * bytes (Ktor follows the GitHub redirect to the signed URL transparently).
 *
 * Throws [GitHubApiException] with status 410 when the artifact has expired.
 */
suspend fun downloadArtifact(
    repo: BoundRepo,
    artifactId: com.example.ghactions.domain.ArtifactId
): ByteArray = withContext(Dispatchers.IO) {
    val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/artifacts/${artifactId.value}/zip")
    if (!response.status.isSuccess()) fail(response, "artifact zip")
    response.body<ByteArray>()
}
```

(The existing `private suspend fun fail(response, label)` helper from Plan 6 covers both error paths — including the rate-limit promotion if those endpoints ever return 429.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.api.GitHubClientArtifactsTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 129 tests (125 from Plan 7 + 4 new).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/GitHubClient.kt \
        src/test/kotlin/com/example/ghactions/api/GitHubClientArtifactsTest.kt
git commit -m "feat(api): add listArtifacts and downloadArtifact"
```

---

## Task 3: `RunRepository.refreshArtifacts` + `downloadArtifactToFile`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`
- Create: `src/test/kotlin/com/example/ghactions/repo/RunRepositoryArtifactsTest.kt`

State flow per run id (mirrors the existing `JobsState`/`LogState` pattern). Plus a fire-and-forget download helper that writes to disk; the UI uses the result for "Reveal in Files".

- [ ] **Step 1: Write failing tests**

Create `src/test/kotlin/com/example/ghactions/repo/RunRepositoryArtifactsTest.kt`:

```kotlin
package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.Artifact
import com.example.ghactions.domain.ArtifactId
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunRepositoryArtifactsTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "o", repo = "r")
    private val runId = RunId(7)

    private fun newRepo(client: GitHubClient): RunRepository = RunRepository(
        boundRepo = { repo },
        clientFactory = { client },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    @Test
    fun `refreshArtifacts transitions Idle → Loading → Loaded`() = runTest {
        val artifacts = listOf(
            Artifact(id = ArtifactId(1), name = "logs", sizeBytes = 100, expired = false, createdAt = Instant.EPOCH)
        )
        val client = mockk<GitHubClient>()
        coEvery { client.listArtifacts(repo, runId) } returns artifacts
        val repository = newRepo(client)

        repository.refreshArtifacts(runId).join()
        val state = repository.artifactsState(runId).first()
        assertTrue(state is ArtifactsState.Loaded, "expected Loaded, got $state")
        assertEquals(artifacts, (state as ArtifactsState.Loaded).artifacts)
    }

    @Test
    fun `refreshArtifacts maps GitHubApiException to Error state`() = runTest {
        val client = mockk<GitHubClient>()
        coEvery { client.listArtifacts(repo, runId) } throws GitHubApiException(status = 404, message = "not found")
        val repository = newRepo(client)

        repository.refreshArtifacts(runId).join()
        val state = repository.artifactsState(runId).first()
        assertTrue(state is ArtifactsState.Error, "expected Error, got $state")
        assertEquals(404, (state as ArtifactsState.Error).httpStatus)
    }

    @Test
    fun `downloadArtifactToFile writes zip bytes to the requested path`() = runTest {
        val payload = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x09)
        val client = mockk<GitHubClient>()
        coEvery { client.downloadArtifact(repo, ArtifactId(11)) } returns payload
        val repository = newRepo(client)

        val tmp = kotlin.io.path.createTempFile("ghactions-test-", ".zip").toFile()
        try {
            val result = repository.downloadArtifactToFile(ArtifactId(11), tmp)
            assertTrue(result is DownloadResult.Success, "expected Success, got $result")
            assertEquals(tmp, (result as DownloadResult.Success).file)
            kotlin.test.assertContentEquals(payload, tmp.readBytes())
        } finally {
            tmp.delete()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.repo.RunRepositoryArtifactsTest`
Expected: FAIL — `ArtifactsState`, `refreshArtifacts`, `downloadArtifactToFile`, `DownloadResult` unresolved.

- [ ] **Step 3: Add `ArtifactsState`, `DownloadResult`, and the methods**

Modify `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`.

Near the top of the file, after the existing `LogState` sealed class, add:

```kotlin
/** State of the artifacts list for one specific run. */
sealed class ArtifactsState {
    data object Idle : ArtifactsState()
    data object Loading : ArtifactsState()
    data class Loaded(val artifacts: List<com.example.ghactions.domain.Artifact>) : ArtifactsState()
    data class Error(val httpStatus: Int?, val message: String) : ArtifactsState()
}

/** Outcome of [RunRepository.downloadArtifactToFile]. */
sealed class DownloadResult {
    data class Success(val file: java.io.File) : DownloadResult()
    data class Error(val httpStatus: Int?, val message: String) : DownloadResult()
}
```

Inside the `RunRepository` class, after the existing `_logsByJob` and `_stepLogs` declarations, add:

```kotlin
private val _artifactsByRun = ConcurrentHashMap<com.example.ghactions.domain.RunId, MutableStateFlow<ArtifactsState>>()
fun artifactsState(runId: com.example.ghactions.domain.RunId): StateFlow<ArtifactsState> =
    _artifactsByRun.computeIfAbsent(runId) { MutableStateFlow(ArtifactsState.Idle) }.asStateFlow()

private fun artifactsFlow(runId: com.example.ghactions.domain.RunId): MutableStateFlow<ArtifactsState> =
    _artifactsByRun.computeIfAbsent(runId) { MutableStateFlow(ArtifactsState.Idle) }
```

Add the `refreshArtifacts` method (alongside `refreshRuns`/`refreshJobs`):

```kotlin
fun refreshArtifacts(runId: com.example.ghactions.domain.RunId): CJob = scope.launch {
    val repo = boundRepo() ?: return@launch
    val client = clientFactory() ?: run {
        artifactsFlow(runId).value =
            ArtifactsState.Error(null, "No credentials available for ${repo.host}")
        return@launch
    }
    artifactsFlow(runId).value = ArtifactsState.Loading
    artifactsFlow(runId).value = try {
        ArtifactsState.Loaded(client.listArtifacts(repo, runId))
    } catch (e: com.example.ghactions.api.GitHubApiException) {
        log.warn("listArtifacts failed: status=${e.status}", e)
        ArtifactsState.Error(e.status, e.message ?: "API error")
    } catch (e: Throwable) {
        log.warn("listArtifacts threw unexpectedly", e)
        ArtifactsState.Error(null, e.message ?: e::class.java.simpleName)
    }
}
```

Add the suspend download helper (a public method, not on `scope.launch` — caller decides where to run it):

```kotlin
suspend fun downloadArtifactToFile(
    artifactId: com.example.ghactions.domain.ArtifactId,
    target: java.io.File
): DownloadResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val repo = boundRepo() ?: return@withContext DownloadResult.Error(null, "No bound repo")
    val client = clientFactory() ?: return@withContext DownloadResult.Error(null, "No credentials available for ${repo.host}")
    try {
        val bytes = client.downloadArtifact(repo, artifactId)
        target.writeBytes(bytes)
        DownloadResult.Success(target)
    } catch (e: com.example.ghactions.api.GitHubApiException) {
        log.warn("downloadArtifact failed: status=${e.status}", e)
        DownloadResult.Error(e.status, e.message ?: "API error")
    } catch (e: Throwable) {
        log.warn("downloadArtifact threw unexpectedly", e)
        DownloadResult.Error(null, e.message ?: e::class.java.simpleName)
    }
}
```

Update `invalidateAll()` to clear the new map:

```kotlin
fun invalidateAll() {
    _runsState.value = RunListState.Idle
    _jobsByRun.values.forEach { it.value = JobsState.Idle }
    _logsByJob.values.forEach { it.value = LogState.Idle }
    _stepLogs.values.forEach { it.value = LogState.Idle }
    _artifactsByRun.values.forEach { it.value = ArtifactsState.Idle }
    _archivesByRun.clear()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.repo.RunRepositoryArtifactsTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 132 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/repo/RunRepository.kt \
        src/test/kotlin/com/example/ghactions/repo/RunRepositoryArtifactsTest.kt
git commit -m "feat(repo): add artifactsState and downloadArtifactToFile"
```

---

## Task 4: `ArtifactsPanel` UI

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/ArtifactsPanel.kt`

A `JBTable` of artifacts plus toolbar with *Refresh*, *Download…*, *Reveal in Files*. Self-disposing (registered with the tool window content disposer in Task 5).

- [ ] **Step 1: Create the file**

Create `src/main/kotlin/com/example/ghactions/ui/ArtifactsPanel.kt`:

```kotlin
package com.example.ghactions.ui

import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.domain.Artifact
import com.example.ghactions.domain.RunId
import com.example.ghactions.repo.ArtifactsState
import com.example.ghactions.repo.DownloadResult
import com.example.ghactions.repo.RunRepository
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Desktop
import java.io.File
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel

/**
 * Artifacts sub-tab inside [RunDetailPanel]. Lists the artifacts uploaded by the currently
 * selected run; double-click or *Download…* writes the zip into the user's chosen directory.
 */
class ArtifactsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(RunRepository::class.java)

    private var currentRunId: RunId? = null
    private var lastDownload: File? = null
    private var observerJob: CJob? = null

    private val tableModel = ArtifactsTableModel()
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        autoCreateRowSorter = true
    }

    private val statusLabel = JBLabel("").apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        foreground = UIUtil.getContextHelpForeground()
    }

    private val cardLayout = CardLayout()
    private val cardsPanel = JPanel(cardLayout).apply {
        add(JBScrollPane(table), CARD_TABLE)
        add(statusLabel, CARD_STATUS)
    }

    init {
        border = JBUI.Borders.empty()
        add(buildToolbar().component, BorderLayout.NORTH)
        add(cardsPanel, BorderLayout.CENTER)
        showStatus("Select a run to see its artifacts.")
    }

    fun showRun(runId: RunId) {
        currentRunId = runId
        observerJob?.cancel()
        observerJob = scope.launch {
            repository.artifactsState(runId).collect { render(it) }
        }
        repository.refreshArtifacts(runId)
    }

    fun clear() {
        currentRunId = null
        lastDownload = null
        observerJob?.cancel()
        observerJob = null
        tableModel.setArtifacts(emptyList())
        showStatus("Select a run to see its artifacts.")
    }

    private fun buildToolbar() = ActionManager.getInstance().createActionToolbar(
        ActionPlaces.TOOLWINDOW_CONTENT,
        DefaultActionGroup().apply {
            add(refreshAction())
            add(downloadAction())
            add(revealAction())
        },
        true
    ).also { it.targetComponent = this }

    private fun refreshAction() = object : AnAction("Refresh", "Reload artifacts", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentRunId != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            currentRunId?.let { repository.refreshArtifacts(it) }
        }
    }

    private fun downloadAction() = object : AnAction("Download…", "Download the selected artifact zip", AllIcons.Actions.Download) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedArtifact()?.expired == false
        }
        override fun actionPerformed(e: AnActionEvent) {
            val artifact = selectedArtifact() ?: return
            doDownload(artifact)
        }
    }

    private fun revealAction() = object : AnAction("Reveal in Files", "Open the last downloaded zip in the OS file browser", AllIcons.Actions.MenuOpen) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = lastDownload?.exists() == true
        }
        override fun actionPerformed(e: AnActionEvent) {
            lastDownload?.let { revealInFiles(it) }
        }
    }

    private fun selectedArtifact(): Artifact? {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return null
        val modelRow = table.convertRowIndexToModel(row)
        return tableModel.artifactAt(modelRow)
    }

    private fun doDownload(artifact: Artifact) {
        val dir = chooseTargetDir() ?: return
        val target = File(dir, "${artifact.name}-${artifact.id.value}.zip")
        statusLabel.text = "Downloading ${artifact.name}…"
        scope.launch {
            val result = repository.downloadArtifactToFile(artifact.id, target)
            ApplicationManager.getApplication().invokeLater {
                when (result) {
                    is DownloadResult.Success -> {
                        lastDownload = result.file
                        Messages.showInfoMessage(
                            project,
                            "Saved to ${result.file.absolutePath}",
                            "Artifact downloaded"
                        )
                    }
                    is DownloadResult.Error -> Messages.showErrorDialog(
                        project,
                        "Failed${result.httpStatus?.let { " ($it)" } ?: ""}: ${result.message}",
                        "Download failed"
                    )
                }
            }
        }
    }

    private fun chooseTargetDir(): File? {
        val configured = PluginSettings.getInstance().state.defaultDownloadDir
        if (!configured.isNullOrBlank() && File(configured).isDirectory) return File(configured)
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle("Choose Download Directory")
        val chosen = FileChooserFactory.getInstance()
            .createPathChooser(descriptor, project, this)
            .let {
                var picked: File? = null
                it.choose(null) { files -> picked = files.firstOrNull()?.let { vf -> File(vf.path) } }
                picked
            }
        return chosen
    }

    private fun revealInFiles(file: File) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file.parentFile)
            }
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, "Could not open file browser: ${t.message}", "Reveal failed")
        }
    }

    private fun render(state: ArtifactsState) {
        when (state) {
            is ArtifactsState.Idle -> showStatus("Select a run to see its artifacts.")
            is ArtifactsState.Loading -> showStatus("Loading artifacts…")
            is ArtifactsState.Loaded -> {
                tableModel.setArtifacts(state.artifacts)
                if (state.artifacts.isEmpty()) showStatus("No artifacts uploaded by this run.")
                else cardLayout.show(cardsPanel, CARD_TABLE)
            }
            is ArtifactsState.Error -> showStatus(
                "Failed${state.httpStatus?.let { " ($it)" } ?: ""}: ${state.message}"
            )
        }
    }

    private fun showStatus(text: String) {
        statusLabel.text = text
        cardLayout.show(cardsPanel, CARD_STATUS)
    }

    override fun dispose() {
        observerJob?.cancel()
        scope.cancel()
    }

    private companion object {
        const val CARD_TABLE = "table"
        const val CARD_STATUS = "status"
    }
}

private class ArtifactsTableModel : AbstractTableModel() {
    private var rows: List<Artifact> = emptyList()

    fun setArtifacts(artifacts: List<Artifact>) {
        rows = artifacts
        fireTableDataChanged()
    }

    fun artifactAt(modelRow: Int): Artifact? = rows.getOrNull(modelRow)

    override fun getRowCount() = rows.size
    override fun getColumnCount() = 3
    override fun getColumnName(column: Int) = when (column) {
        0 -> "Name"
        1 -> "Size"
        2 -> "Status"
        else -> ""
    }

    override fun getValueAt(row: Int, column: Int): Any {
        val a = rows[row]
        return when (column) {
            0 -> a.name
            1 -> humanSize(a.sizeBytes)
            2 -> if (a.expired) "expired" else "available"
            else -> ""
        }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/ArtifactsPanel.kt
git commit -m "feat(ui): add ArtifactsPanel with download support"
```

---

## Task 5: Tab integration in `RunDetailPanel`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt`
- Modify: `src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt`

Wrap `LogViewerPanel` and `ArtifactsPanel` in a `JBTabbedPane`. Hand-off the run id to `ArtifactsPanel.showRun` whenever `RunDetailPanel.showRun` is called. Register `ArtifactsPanel` with the same disposer chain that already handles `RunDetailPanel`.

- [ ] **Step 1: Modify `RunDetailPanel`**

Edit `src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt`. Add an `artifactsPanel` field and a tabbed wrapper.

Replace the `private val logViewer = LogViewerPanel()` line with:

```kotlin
private val logViewer = LogViewerPanel()
private val artifactsPanel = ArtifactsPanel(project)

private val detailTabs = com.intellij.ui.components.JBTabbedPane().apply {
    addTab("Logs", logViewer)
    addTab("Artifacts", artifactsPanel)
}
```

Replace the `secondComponent = logViewer` in the splitter with `secondComponent = detailTabs`.

In `showRun(run: Run)`, after `repository.refreshJobs(run.id)`, also call:

```kotlin
artifactsPanel.showRun(run.id)
```

In `dispose()`, also dispose the artifacts panel:

```kotlin
override fun dispose() {
    currentRunFlowJob?.cancel()
    currentLogFlowJob?.cancel()
    artifactsPanel.dispose()
    scope.cancel()
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 132 tests, no regressions.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt
git commit -m "feat(ui): wrap log viewer and artifacts in tabbed pane"
```

---

## Task 6: Manual smoke test (`./gradlew runIde`)

**Files:** none.

- [ ] **Step 1: Launch the dev IDE**

Run: `./gradlew --no-daemon runIde`
Expected: sandbox IDE opens.

- [ ] **Step 2: Open the GitHub Actions tool window, select a run that has artifacts**

Confirm:
- The detail panel now shows two tabs: *Logs* (default) and *Artifacts*.
- Switching to *Artifacts* lists the run's artifacts with name, size, and status columns.
- A run with no artifacts shows an empty-state message.
- An expired artifact displays "expired" in the Status column and *Download…* is disabled.

- [ ] **Step 3: Download an artifact**

Click *Download…*. If `defaultDownloadDir` is set in settings, the file lands there silently; otherwise a directory chooser appears. Confirm the success dialog and that the zip is on disk.

- [ ] **Step 4: Reveal in Files**

Click *Reveal in Files*. Expected: OS file browser opens at the parent directory.

- [ ] **Step 5: Switch run while a download is in flight**

Pick a different run before the previous download finishes. Expected: previous download completes silently in the background; switching runs cancels the artifacts state observer cleanly with no leaked dialogs.

- [ ] **Step 6: Document any deviations**

If any step fails, write to `docs/superpowers/notes/2026-04-30-artifacts-smoke-deviations.md` and commit:

```bash
git add docs/superpowers/notes/
git commit -m "docs: smoke test deviations for plan 8"
```

If no deviations, skip the commit.

---

## Task 7: Final sweep + merge

**Files:** none.

- [ ] **Step 1: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS — 132 tests.

- [ ] **Step 2: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED against IDEA 2024.3 + 2025.1. The existing 2 `SegmentedButton` warnings are acceptable; new warnings would need investigation.

- [ ] **Step 4: Fast-forward merge**

```bash
git checkout main
git merge --ff-only feat/plan-8-artifacts
git log --oneline | head -10
```

Expected: clean fast-forward.

- [ ] **Step 5: Plan-level verification**

- All 7 tasks have green check-marks (Task 6 may be deferred).
- `./gradlew test` passes (132 tests).
- `./gradlew buildPlugin` succeeds.
- `./gradlew verifyPlugin` succeeds.
- `git log --oneline` on `main` shows the expected sequence.

---

## What ships at the end of Plan 8

- A new *Artifacts* tab in the run detail panel listing each artifact with name, size, expiration status.
- *Download…* writes the artifact zip to the configured download directory (or prompts for one).
- *Reveal in Files* opens the OS file browser at the downloaded zip's parent directory.
- *Refresh* re-fetches the artifacts list for the current run.

What it does **not** yet do:
- Per-file browsing of the zip's contents inside the IDE.
- Drag-from-list-to-Finder.
- Annotations / Summary tabs (separate plans).

---

## Open questions / risks

1. **`Desktop.getDesktop().open(parentFile)`** opens the directory (most platforms support it). On environments where `Desktop` isn't supported (server JREs, headless tests), the catch falls back to a polite error dialog. No "select the file" semantics — that needs platform-specific code (`open -R` on macOS, `explorer /select,` on Windows). Keep it simple for v1.
2. **GitHub redirects through a signed URL** for the artifact zip endpoint. Ktor follows redirects by default, so we get the bytes from the final 200 response. The signed URL has a short TTL (~1 minute); large artifacts on slow links could time out. The `HttpTimeout` plugin allows 30 s; if reports of timeouts come in, bump for this endpoint specifically.
3. **Memory footprint**: the entire artifact is materialized into a `ByteArray` before being written to disk. Most CI artifacts are sub-100 MB, well within the JVM heap. If a user complains, switch to streaming `ByteReadChannel` directly to a `FileOutputStream`.
