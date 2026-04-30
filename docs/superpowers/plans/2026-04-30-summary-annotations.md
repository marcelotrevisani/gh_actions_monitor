# Plan 10 — Summary and Annotations tabs

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two new tabs in the run-detail bottom pane (currently *Logs* / *Artifacts*): **Summary** showing each job's check-run summary text, and **Annotations** showing the aggregated annotations across all jobs of the run, with double-click navigation to the file/line. Both were originally listed in the spec's *Components → RunDetailPanel* sub-tabs and were called out by the user during brainstorming.

**Architecture:** GitHub doesn't expose step-summary or annotation endpoints directly on workflow jobs — they hang off the *check-run* associated with each job. Every `Job` returned by `GET /actions/runs/{id}/jobs` carries a `check_run_url` like `https://api.github.com/repos/{o}/{r}/check-runs/{id}`. We extract the trailing id, then call:
- `GET /repos/{o}/{r}/check-runs/{check_run_id}` — `output: { title, summary, text }` for the Summary tab.
- `GET /repos/{o}/{r}/check-runs/{check_run_id}/annotations` — list of `{ path, start_line, end_line, annotation_level, message }` for the Annotations tab.

Per-job results are aggregated at the repository layer into one `Map<JobId, ...>` per run, exposed as `StateFlow`s. Two new self-contained Swing panels (`SummaryPanel`, `AnnotationsPanel`) consume these flows and live as new tabs inside `RunDetailPanel`'s `JBTabbedPane`. Annotations double-click opens the file at the start line via `OpenFileDescriptor` — works whenever the file exists in the project.

**Tech Stack:** Same as Plans 1–9 — Kotlin 2.0.21, Ktor 2.3.13, kotlinx-serialization 1.7.3, JUnit 5 + MockK + Ktor MockEngine. Swing for UI.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`):
- *Components → `RunDetailPanel`* — gains the third and fourth sub-tabs.
- *IDE integrations → Annotation gutter markers* — out of scope for v1; the Annotations tab is a tabular view, not gutter markers. A later plan can add gutter markers using the same data path.

**Sections deliberately deferred:**
- Editor gutter markers — separate plan (uses the same `Annotation` domain plus an `AnnotationLineMarkerProvider`).
- Problems-tool-window integration — separate plan.
- Markdown rendering of the summary text (`output.summary` is markdown). v1 displays plain text; a later plan can convert via `IntellijMarkdownToHtmlConverter` and render in a `JEditorPane`.
- SHA-mismatch banner ("annotations are from commit X — your working copy differs"). v1 just opens the file, accepting that line numbers can drift.

**Plans 1–9 → Plan 10 carry-overs:**
- DTO + domain pattern from Plans 2–8 (PR/Run/Job/Artifact). Same `@Serializable` style, `toDomain()` adapters.
- Error path is the existing `fail(response, label)` helper in `GitHubClient` (rate-limit aware) plus `friendlyApiError` in panels.
- `RunRepository` already manages per-run/per-job state-flows; adding two more follows the same `ConcurrentHashMap<id, MutableStateFlow<X>>` pattern.
- Tests use Ktor `MockEngine` (API tier) and MockK + `Dispatchers.Unconfined` (repository tier).
- Commits one per task, type-prefixed; **no `Co-Authored-By` trailer**.

---

## File Structure

```
gh_actions_pycharm/
└── src/
    ├── main/kotlin/com/example/ghactions/
    │   ├── domain/
    │   │   ├── Ids.kt                     (modify — add CheckRunId)
    │   │   ├── CheckRun.kt                (new — CheckRunOutput)
    │   │   └── Annotation.kt              (new — Annotation, AnnotationLevel)
    │   ├── api/
    │   │   ├── dto/JobDto.kt              (modify — capture check_run_url)
    │   │   ├── dto/CheckRunDto.kt         (new)
    │   │   ├── dto/AnnotationDto.kt       (new)
    │   │   └── GitHubClient.kt            (modify — getCheckRun, listAnnotations)
    │   ├── repo/
    │   │   └── RunRepository.kt           (modify — summaryState, refreshSummary,
    │   │                                    annotationsState, refreshAnnotations)
    │   └── ui/
    │       ├── SummaryPanel.kt            (new — plain-text per-job summary)
    │       ├── AnnotationsPanel.kt        (new — table + double-click open file)
    │       └── RunDetailPanel.kt          (modify — add Summary + Annotations tabs)
    └── test/kotlin/com/example/ghactions/
        ├── api/
        │   └── GitHubClientCheckRunsTest.kt   (new — getCheckRun + listAnnotations)
        └── repo/
            └── RunRepositoryCheckRunsTest.kt  (new — refreshSummary + refreshAnnotations)
```

**File responsibility notes:**
- `CheckRun` and `Annotation` are pure domain types, no Ktor.
- `JobDto` already exists — we add `checkRunUrl: String?` and a `checkRunId(): CheckRunId?` extractor that parses the URL's trailing path segment.
- `GitHubClient` gets two more `suspend fun`s, both reusing the existing `fail()` error helper.
- `RunRepository` learns two `ConcurrentHashMap<RunId, MutableStateFlow<…>>` — `_summariesByRun` and `_annotationsByRun` — and aggregator methods that fan out per job and assemble into a single state.
- `SummaryPanel` is a `JBScrollPane(JTextArea)` with one section per job (header line + summary body or "(no summary)" placeholder); plain-text only.
- `AnnotationsPanel` is a `JBTable` with columns *Severity / File:Line / Message*; double-click opens via `OpenFileDescriptor`.

---

## Conventions

- Tests: JUnit 5, `kotlin.test.*`, MockK, Ktor `MockEngine`.
- Production scope `Dispatchers.IO`; tests `Dispatchers.Unconfined`.
- Commits one per task, type-prefixed.
- **Cumulative test target:** 132 (post-`08e72f3`) + 4 (`getCheckRun` / `listAnnotations`) + 4 (repo aggregators) = ~140.

---

## Task 1: Domain types + JobDto extension

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/domain/Ids.kt`
- Create: `src/main/kotlin/com/example/ghactions/domain/CheckRun.kt`
- Create: `src/main/kotlin/com/example/ghactions/domain/Annotation.kt`
- Modify: `src/main/kotlin/com/example/ghactions/api/dto/JobDto.kt`

No tests in this task — Tasks 2–4 cover the wire and aggregation paths.

- [ ] **Step 1: Add `CheckRunId`**

Modify `src/main/kotlin/com/example/ghactions/domain/Ids.kt`. Append:

```kotlin
@JvmInline
value class CheckRunId(val value: Long) {
    override fun toString(): String = value.toString()
}
```

- [ ] **Step 2: Create `CheckRun.kt`**

Create `src/main/kotlin/com/example/ghactions/domain/CheckRun.kt`:

```kotlin
package com.example.ghactions.domain

/**
 * Subset of GitHub's check-run object we surface in the UI. The summary and text
 * fields are markdown; v1 displays them as plain text.
 */
data class CheckRunOutput(
    val title: String?,
    val summary: String?,
    val text: String?,
    val annotationsCount: Int
)
```

- [ ] **Step 3: Create `Annotation.kt`**

Create `src/main/kotlin/com/example/ghactions/domain/Annotation.kt`:

```kotlin
package com.example.ghactions.domain

/**
 * GitHub annotation on a check run: a severity-tagged message anchored at a file/line range.
 * Maps to a row in the Annotations tab.
 */
data class Annotation(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val level: AnnotationLevel,
    val title: String?,
    val message: String,
    val rawDetails: String? = null
)

enum class AnnotationLevel {
    NOTICE, WARNING, FAILURE, UNKNOWN;

    companion object {
        fun fromWire(value: String?): AnnotationLevel = when (value?.lowercase()) {
            "notice" -> NOTICE
            "warning" -> WARNING
            "failure" -> FAILURE
            else -> UNKNOWN
        }
    }
}
```

- [ ] **Step 4: Extend `JobDto` with `check_run_url`**

Read `src/main/kotlin/com/example/ghactions/api/dto/JobDto.kt`. Add a `@SerialName("check_run_url") val checkRunUrl: String? = null` field, and an extractor:

```kotlin
import com.example.ghactions.domain.CheckRunId

fun JobDto.checkRunId(): CheckRunId? {
    val url = checkRunUrl ?: return null
    // Trailing path segment of `https://api.github.com/repos/o/r/check-runs/123` → 123
    val tail = url.substringAfterLast('/').toLongOrNull() ?: return null
    return CheckRunId(tail)
}
```

(Place `checkRunId()` as a top-level extension on `JobDto`, below the data class declaration.)

- [ ] **Step 5: Verify compile**

Run: `./gradlew --no-daemon compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/domain/Ids.kt \
        src/main/kotlin/com/example/ghactions/domain/CheckRun.kt \
        src/main/kotlin/com/example/ghactions/domain/Annotation.kt \
        src/main/kotlin/com/example/ghactions/api/dto/JobDto.kt
git commit -m "feat(domain): add CheckRunOutput, Annotation, and JobDto.checkRunId"
```

---

## Task 2: `GitHubClient.getCheckRun` and `listAnnotations`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`
- Create: `src/main/kotlin/com/example/ghactions/api/dto/CheckRunDto.kt`
- Create: `src/main/kotlin/com/example/ghactions/api/dto/AnnotationDto.kt`
- Create: `src/test/kotlin/com/example/ghactions/api/GitHubClientCheckRunsTest.kt`

GitHub endpoints:
- `GET /repos/{o}/{r}/check-runs/{id}` → check-run object (we extract `output`).
- `GET /repos/{o}/{r}/check-runs/{id}/annotations` → list of annotations (paginated; v1 fetches first page).

- [ ] **Step 1: Create the DTOs**

Create `src/main/kotlin/com/example/ghactions/api/dto/CheckRunDto.kt`:

```kotlin
package com.example.ghactions.api.dto

import com.example.ghactions.domain.CheckRunOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CheckRunDto(
    val id: Long,
    val output: CheckRunOutputDto = CheckRunOutputDto()
) {
    fun toDomain(): CheckRunOutput = CheckRunOutput(
        title = output.title,
        summary = output.summary,
        text = output.text,
        annotationsCount = output.annotationsCount ?: 0
    )
}

@Serializable
data class CheckRunOutputDto(
    val title: String? = null,
    val summary: String? = null,
    val text: String? = null,
    @SerialName("annotations_count") val annotationsCount: Int? = null
)
```

Create `src/main/kotlin/com/example/ghactions/api/dto/AnnotationDto.kt`:

```kotlin
package com.example.ghactions.api.dto

import com.example.ghactions.domain.Annotation
import com.example.ghactions.domain.AnnotationLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnnotationDto(
    val path: String,
    @SerialName("start_line") val startLine: Int,
    @SerialName("end_line") val endLine: Int,
    @SerialName("annotation_level") val annotationLevel: String? = null,
    val title: String? = null,
    val message: String,
    @SerialName("raw_details") val rawDetails: String? = null
) {
    fun toDomain(): Annotation = Annotation(
        path = path,
        startLine = startLine,
        endLine = endLine,
        level = AnnotationLevel.fromWire(annotationLevel),
        title = title,
        message = message,
        rawDetails = rawDetails
    )
}
```

- [ ] **Step 2: Write failing tests**

Create `src/test/kotlin/com/example/ghactions/api/GitHubClientCheckRunsTest.kt`:

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.AnnotationLevel
import com.example.ghactions.domain.CheckRunId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubClientCheckRunsTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "o", repo = "r")

    private fun client(engine: MockEngine): GitHubClient {
        val http = GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "x"),
            engine = engine
        )
        return GitHubClient(http)
    }

    @Test
    fun `getCheckRun maps output fields`() = runTest {
        val body = """
            {
              "id": 7,
              "output": {
                "title": "Build failed",
                "summary": "## Summary\nthings broke",
                "text": "details here",
                "annotations_count": 3
              }
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val output = client(engine).getCheckRun(repo, CheckRunId(7))
        assertEquals("Build failed", output.title)
        assertEquals("## Summary\nthings broke", output.summary)
        assertEquals("details here", output.text)
        assertEquals(3, output.annotationsCount)
    }

    @Test
    fun `getCheckRun surfaces 404 as GitHubApiException`() = runTest {
        val engine = MockEngine { _ -> respond(content = "{}", status = HttpStatusCode.NotFound) }
        val ex = assertFailsWith<GitHubApiException> { client(engine).getCheckRun(repo, CheckRunId(7)) }
        assertEquals(404, ex.status)
    }

    @Test
    fun `listAnnotations maps fields and severity`() = runTest {
        val body = """
            [
              {"path": "src/main.kt", "start_line": 10, "end_line": 10,
               "annotation_level": "failure", "title": "boom",
               "message": "Unresolved reference"},
              {"path": "src/util.kt", "start_line": 1, "end_line": 5,
               "annotation_level": "warning", "message": "deprecated"}
            ]
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val anns = client(engine).listAnnotations(repo, CheckRunId(7))
        assertEquals(2, anns.size)
        assertEquals(AnnotationLevel.FAILURE, anns[0].level)
        assertEquals(10, anns[0].startLine)
        assertEquals("boom", anns[0].title)
        assertEquals(AnnotationLevel.WARNING, anns[1].level)
    }

    @Test
    fun `listAnnotations surfaces 404 as GitHubApiException`() = runTest {
        val engine = MockEngine { _ -> respond(content = "[]", status = HttpStatusCode.NotFound) }
        val ex = assertFailsWith<GitHubApiException> { client(engine).listAnnotations(repo, CheckRunId(7)) }
        assertEquals(404, ex.status)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.api.GitHubClientCheckRunsTest`
Expected: FAIL — `getCheckRun` / `listAnnotations` unresolved.

- [ ] **Step 4: Implement the two `GitHubClient` methods**

Inside the `GitHubClient` class, alongside the existing methods (before the private `fail(...)` helper), add:

```kotlin
suspend fun getCheckRun(
    repo: BoundRepo,
    checkRunId: com.example.ghactions.domain.CheckRunId
): com.example.ghactions.domain.CheckRunOutput = withContext(Dispatchers.IO) {
    val response = http.get("/repos/${repo.owner}/${repo.repo}/check-runs/${checkRunId.value}")
    if (!response.status.isSuccess()) fail(response, "check-run")
    response.body<com.example.ghactions.api.dto.CheckRunDto>().toDomain()
}

suspend fun listAnnotations(
    repo: BoundRepo,
    checkRunId: com.example.ghactions.domain.CheckRunId
): List<com.example.ghactions.domain.Annotation> = withContext(Dispatchers.IO) {
    val response = http.get("/repos/${repo.owner}/${repo.repo}/check-runs/${checkRunId.value}/annotations")
    if (!response.status.isSuccess()) fail(response, "annotations")
    response.body<List<com.example.ghactions.api.dto.AnnotationDto>>().map { it.toDomain() }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.api.GitHubClientCheckRunsTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 136 tests (132 + 4).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/dto/CheckRunDto.kt \
        src/main/kotlin/com/example/ghactions/api/dto/AnnotationDto.kt \
        src/main/kotlin/com/example/ghactions/api/GitHubClient.kt \
        src/test/kotlin/com/example/ghactions/api/GitHubClientCheckRunsTest.kt
git commit -m "feat(api): add getCheckRun and listAnnotations"
```

---

## Task 3: `RunRepository.summaryState` + `refreshSummary`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`
- Create: `src/test/kotlin/com/example/ghactions/repo/RunRepositoryCheckRunsTest.kt` (will grow in Task 4 too)

`refreshSummary(runId)` reads the run's jobs from `jobsState(runId)` (a single snapshot), then for each job that has a `check_run_url` fetches the check-run output. Aggregates into a single `SummaryState` keyed by run id.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/example/ghactions/repo/RunRepositoryCheckRunsTest.kt`:

```kotlin
package com.example.ghactions.repo

import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.CheckRunId
import com.example.ghactions.domain.CheckRunOutput
import com.example.ghactions.domain.Job
import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
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

class RunRepositoryCheckRunsTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "o", repo = "r")
    private val runId = RunId(99)

    private fun job(id: Long, name: String, checkRunId: Long?): Job = Job(
        id = JobId(id),
        runId = runId,
        name = name,
        status = RunStatus.COMPLETED,
        conclusion = RunConclusion.SUCCESS,
        startedAt = Instant.EPOCH,
        completedAt = Instant.EPOCH,
        steps = emptyList(),
        checkRunId = checkRunId?.let { CheckRunId(it) }
    )

    private fun newRepo(client: GitHubClient): RunRepository = RunRepository(
        boundRepo = { repo },
        clientFactory = { client },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    @Test
    fun `refreshSummary aggregates one CheckRunOutput per job`() = runTest {
        val client = mockk<GitHubClient>()
        coEvery { client.listJobs(repo, runId) } returns listOf(
            job(1, "build", checkRunId = 11),
            job(2, "test", checkRunId = 22)
        )
        coEvery { client.getCheckRun(repo, CheckRunId(11)) } returns
            CheckRunOutput(title = "Build", summary = "build OK", text = null, annotationsCount = 0)
        coEvery { client.getCheckRun(repo, CheckRunId(22)) } returns
            CheckRunOutput(title = "Test", summary = "1 failure", text = null, annotationsCount = 1)
        val repository = newRepo(client)

        repository.refreshSummary(runId).join()
        val state = repository.summaryState(runId).first()
        assertTrue(state is SummaryState.Loaded, "expected Loaded, got $state")
        val sections = (state as SummaryState.Loaded).sections
        assertEquals(2, sections.size)
        assertEquals("build", sections[0].jobName)
        assertEquals("build OK", sections[0].output.summary)
        assertEquals("1 failure", sections[1].output.summary)
    }

    @Test
    fun `refreshSummary skips jobs without a check_run_url`() = runTest {
        val client = mockk<GitHubClient>()
        coEvery { client.listJobs(repo, runId) } returns listOf(
            job(1, "build", checkRunId = null),
            job(2, "test", checkRunId = 22)
        )
        coEvery { client.getCheckRun(repo, CheckRunId(22)) } returns
            CheckRunOutput(title = "Test", summary = "ok", text = null, annotationsCount = 0)
        val repository = newRepo(client)

        repository.refreshSummary(runId).join()
        val state = repository.summaryState(runId).first()
        assertTrue(state is SummaryState.Loaded)
        assertEquals(1, (state as SummaryState.Loaded).sections.size)
        assertEquals("test", state.sections[0].jobName)
    }
}
```

The test references `Job.checkRunId` — that's a new field. Add it to the `Job` data class, plumbed through `JobDto.toDomain()` from `JobDto.checkRunId()` (already added in Task 1):

```kotlin
// In domain/Job.kt: add `val checkRunId: CheckRunId? = null` to the constructor.
// In api/dto/JobDto.kt: in toDomain(), pass checkRunId = checkRunId().
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.repo.RunRepositoryCheckRunsTest`
Expected: FAIL — `Job.checkRunId`, `SummaryState`, `refreshSummary`, `summaryState`, `Section` unresolved.

- [ ] **Step 3: Add `Job.checkRunId` and plumb through DTO**

Open `src/main/kotlin/com/example/ghactions/domain/Job.kt`. Add a `val checkRunId: CheckRunId? = null` field at the end of the primary constructor.

Open `src/main/kotlin/com/example/ghactions/api/dto/JobDto.kt`. In `toDomain()`, add `checkRunId = checkRunId()` (the extension we declared in Task 1). Adjust the existing call to `toDomain()` if it had positional args.

- [ ] **Step 4: Add `SummaryState` and the aggregator**

Modify `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`.

After the existing sealed states (RunListState, JobsState, LogState, ArtifactsState), add:

```kotlin
/** State of the aggregated check-run summaries for a run. */
sealed class SummaryState {
    data object Idle : SummaryState()
    data object Loading : SummaryState()
    data class Loaded(val sections: List<Section>) : SummaryState()
    data class Error(val httpStatus: Int?, val message: String) : SummaryState()

    /** One section per job that has a check-run; ordered as the jobs were returned. */
    data class Section(val jobName: String, val output: com.example.ghactions.domain.CheckRunOutput)
}
```

Inside the `RunRepository` class, alongside the other per-run flows:

```kotlin
private val _summariesByRun = ConcurrentHashMap<com.example.ghactions.domain.RunId, MutableStateFlow<SummaryState>>()
fun summaryState(runId: com.example.ghactions.domain.RunId): StateFlow<SummaryState> =
    _summariesByRun.computeIfAbsent(runId) { MutableStateFlow(SummaryState.Idle) }.asStateFlow()

private fun summaryFlow(runId: com.example.ghactions.domain.RunId): MutableStateFlow<SummaryState> =
    _summariesByRun.computeIfAbsent(runId) { MutableStateFlow(SummaryState.Idle) }
```

Add the `refreshSummary` method:

```kotlin
fun refreshSummary(runId: com.example.ghactions.domain.RunId): CJob = scope.launch {
    val repo = boundRepo() ?: return@launch
    val client = clientFactory() ?: run {
        summaryFlow(runId).value =
            SummaryState.Error(null, "No credentials available for ${repo.host}")
        return@launch
    }
    summaryFlow(runId).value = SummaryState.Loading
    summaryFlow(runId).value = try {
        val jobs = client.listJobs(repo, runId)
        val sections = jobs.mapNotNull { job ->
            val crid = job.checkRunId ?: return@mapNotNull null
            val output = client.getCheckRun(repo, crid)
            SummaryState.Section(job.name, output)
        }
        SummaryState.Loaded(sections)
    } catch (e: com.example.ghactions.api.GitHubApiException) {
        log.warn("refreshSummary failed: status=${e.status}", e)
        SummaryState.Error(e.status, e.message ?: "API error")
    } catch (e: Throwable) {
        log.warn("refreshSummary threw unexpectedly", e)
        SummaryState.Error(null, e.message ?: e::class.java.simpleName)
    }
}
```

Update `invalidateAll()` to reset `_summariesByRun.values.forEach { it.value = SummaryState.Idle }`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.repo.RunRepositoryCheckRunsTest`
Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/domain/Job.kt \
        src/main/kotlin/com/example/ghactions/api/dto/JobDto.kt \
        src/main/kotlin/com/example/ghactions/repo/RunRepository.kt \
        src/test/kotlin/com/example/ghactions/repo/RunRepositoryCheckRunsTest.kt
git commit -m "feat(repo): aggregate per-job check-run summaries into SummaryState"
```

---

## Task 4: `RunRepository.annotationsState` + `refreshAnnotations`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`
- Modify: `src/test/kotlin/com/example/ghactions/repo/RunRepositoryCheckRunsTest.kt`

Same shape as Task 3, but for annotations. Each annotation is tagged with the source job name so the UI can group by job if it wants.

- [ ] **Step 1: Append the failing test**

Append to `RunRepositoryCheckRunsTest`:

```kotlin
@Test
fun `refreshAnnotations aggregates annotations across jobs`() = runTest {
    val client = mockk<GitHubClient>()
    coEvery { client.listJobs(repo, runId) } returns listOf(
        job(1, "build", checkRunId = 11),
        job(2, "test", checkRunId = 22)
    )
    coEvery { client.listAnnotations(repo, CheckRunId(11)) } returns listOf(
        com.example.ghactions.domain.Annotation(
            path = "src/main.kt", startLine = 1, endLine = 1,
            level = com.example.ghactions.domain.AnnotationLevel.FAILURE,
            title = "fail", message = "boom"
        )
    )
    coEvery { client.listAnnotations(repo, CheckRunId(22)) } returns listOf(
        com.example.ghactions.domain.Annotation(
            path = "src/test.kt", startLine = 5, endLine = 5,
            level = com.example.ghactions.domain.AnnotationLevel.WARNING,
            title = null, message = "deprecated"
        )
    )
    val repository = newRepo(client)

    repository.refreshAnnotations(runId).join()
    val state = repository.annotationsState(runId).first()
    assertTrue(state is AnnotationsState.Loaded)
    val items = (state as AnnotationsState.Loaded).items
    assertEquals(2, items.size)
    assertEquals("build", items[0].jobName)
    assertEquals("src/main.kt", items[0].annotation.path)
    assertEquals("test", items[1].jobName)
}

@Test
fun `refreshAnnotations skips jobs without a check_run_url`() = runTest {
    val client = mockk<GitHubClient>()
    coEvery { client.listJobs(repo, runId) } returns listOf(
        job(1, "build", checkRunId = null)
    )
    val repository = newRepo(client)

    repository.refreshAnnotations(runId).join()
    val state = repository.annotationsState(runId).first()
    assertTrue(state is AnnotationsState.Loaded)
    assertEquals(emptyList(), (state as AnnotationsState.Loaded).items)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.repo.RunRepositoryCheckRunsTest`
Expected: FAIL — `AnnotationsState` (the new one — note: `ArtifactsState` already exists; ignore similarity), `refreshAnnotations`, `annotationsState` unresolved.

**Naming clash warning**: We already have `ArtifactsState`. The new state is `AnnotationsState` (note the *Annotat* prefix). Don't confuse them in the file.

- [ ] **Step 3: Add `AnnotationsState` and the aggregator**

In `RunRepository.kt`, after `SummaryState`, add:

```kotlin
/** State of the aggregated check-run annotations for a run. */
sealed class AnnotationsState {
    data object Idle : AnnotationsState()
    data object Loading : AnnotationsState()
    data class Loaded(val items: List<Item>) : AnnotationsState()
    data class Error(val httpStatus: Int?, val message: String) : AnnotationsState()

    data class Item(val jobName: String, val annotation: com.example.ghactions.domain.Annotation)
}
```

Inside the class:

```kotlin
private val _annotationsByRun = ConcurrentHashMap<com.example.ghactions.domain.RunId, MutableStateFlow<AnnotationsState>>()
fun annotationsState(runId: com.example.ghactions.domain.RunId): StateFlow<AnnotationsState> =
    _annotationsByRun.computeIfAbsent(runId) { MutableStateFlow(AnnotationsState.Idle) }.asStateFlow()

private fun annotationsFlow(runId: com.example.ghactions.domain.RunId): MutableStateFlow<AnnotationsState> =
    _annotationsByRun.computeIfAbsent(runId) { MutableStateFlow(AnnotationsState.Idle) }

fun refreshAnnotations(runId: com.example.ghactions.domain.RunId): CJob = scope.launch {
    val repo = boundRepo() ?: return@launch
    val client = clientFactory() ?: run {
        annotationsFlow(runId).value =
            AnnotationsState.Error(null, "No credentials available for ${repo.host}")
        return@launch
    }
    annotationsFlow(runId).value = AnnotationsState.Loading
    annotationsFlow(runId).value = try {
        val jobs = client.listJobs(repo, runId)
        val items = jobs.flatMap { job ->
            val crid = job.checkRunId ?: return@flatMap emptyList()
            client.listAnnotations(repo, crid).map { ann -> AnnotationsState.Item(job.name, ann) }
        }
        AnnotationsState.Loaded(items)
    } catch (e: com.example.ghactions.api.GitHubApiException) {
        log.warn("refreshAnnotations failed: status=${e.status}", e)
        AnnotationsState.Error(e.status, e.message ?: "API error")
    } catch (e: Throwable) {
        log.warn("refreshAnnotations threw unexpectedly", e)
        AnnotationsState.Error(null, e.message ?: e::class.java.simpleName)
    }
}
```

Update `invalidateAll()` to reset `_annotationsByRun.values.forEach { it.value = AnnotationsState.Idle }`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.repo.RunRepositoryCheckRunsTest`
Expected: PASS, 4 tests total in the file.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 140 tests (136 + 4).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/repo/RunRepository.kt \
        src/test/kotlin/com/example/ghactions/repo/RunRepositoryCheckRunsTest.kt
git commit -m "feat(repo): aggregate per-job annotations into AnnotationsState"
```

---

## Task 5: `SummaryPanel` UI

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/SummaryPanel.kt`

A `JBScrollPane(JTextArea)` that renders one section per job: `=== <job name> ===\n<summary text or "(no summary)">`. Plain text only — no markdown rendering in v1.

- [ ] **Step 1: Create the file**

```kotlin
package com.example.ghactions.ui

import com.example.ghactions.domain.RunId
import com.example.ghactions.repo.RunRepository
import com.example.ghactions.repo.SummaryState
import com.example.ghactions.repo.friendlyApiError
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Plain-text view of each job's check-run summary. v1: one section per job, no
 * markdown rendering. The summary text from GitHub is markdown; the user sees the raw
 * source until a future plan adds an HTML renderer.
 */
class SummaryPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(RunRepository::class.java)

    private var observerJob: CJob? = null

    private val textArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        margin = JBUI.insets(8)
    }

    init {
        border = JBUI.Borders.empty()
        add(JBScrollPane(textArea), BorderLayout.CENTER)
        textArea.text = "Select a run to see its summary."
    }

    fun showRun(runId: RunId) {
        observerJob?.cancel()
        observerJob = scope.launch {
            repository.summaryState(runId).collect { render(it) }
        }
        repository.refreshSummary(runId)
    }

    fun clear() {
        observerJob?.cancel()
        observerJob = null
        ApplicationManager.getApplication().invokeLater {
            textArea.text = "Select a run to see its summary."
            textArea.caretPosition = 0
        }
    }

    private fun render(state: SummaryState) {
        val text = when (state) {
            is SummaryState.Idle -> "Select a run to see its summary."
            is SummaryState.Loading -> "Loading summary…"
            is SummaryState.Loaded -> if (state.sections.isEmpty()) {
                "No check-run summaries for this run."
            } else {
                state.sections.joinToString("\n\n") { section ->
                    val body = section.output.summary?.takeIf { it.isNotBlank() } ?: "(no summary)"
                    "=== ${section.jobName} ===\n$body"
                }
            }
            is SummaryState.Error -> friendlyApiError(state.httpStatus, state.message)
        }
        ApplicationManager.getApplication().invokeLater {
            textArea.text = text
            textArea.caretPosition = 0
        }
    }

    override fun dispose() {
        observerJob?.cancel()
        scope.cancel()
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/SummaryPanel.kt
git commit -m "feat(ui): add SummaryPanel rendering per-job check-run summaries"
```

---

## Task 6: `AnnotationsPanel` UI with double-click navigation

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/AnnotationsPanel.kt`

A `JBTable` with three columns: *Severity* (icon by `AnnotationLevel`), *Job · File:Line*, *Message*. Double-click → open file at line via `OpenFileDescriptor` (best-effort: only works if the file exists in the project's content roots).

- [ ] **Step 1: Create the file**

```kotlin
package com.example.ghactions.ui

import com.example.ghactions.domain.Annotation as DomainAnnotation
import com.example.ghactions.domain.AnnotationLevel
import com.example.ghactions.domain.RunId
import com.example.ghactions.repo.AnnotationsState
import com.example.ghactions.repo.RunRepository
import com.example.ghactions.repo.friendlyApiError
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
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
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class AnnotationsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(RunRepository::class.java)
    private var observerJob: CJob? = null
    private var currentRunId: RunId? = null

    private val tableModel = AnnotationsTableModel()
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        autoCreateRowSorter = true
        getColumnModel().getColumn(0).preferredWidth = 30
        getColumnModel().getColumn(0).cellRenderer = SeverityRenderer()
        getColumnModel().getColumn(1).preferredWidth = 320
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
        add(cardsPanel, BorderLayout.CENTER)
        showStatus("Select a run to see its annotations.")
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    selectedItem()?.let { openInEditor(it.annotation) }
                }
            }
        })
    }

    fun showRun(runId: RunId) {
        currentRunId = runId
        observerJob?.cancel()
        observerJob = scope.launch {
            repository.annotationsState(runId).collect { render(it) }
        }
        repository.refreshAnnotations(runId)
    }

    fun clear() {
        currentRunId = null
        observerJob?.cancel()
        observerJob = null
        tableModel.setItems(emptyList())
        showStatus("Select a run to see its annotations.")
    }

    private fun selectedItem(): AnnotationsState.Item? {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return null
        return tableModel.itemAt(table.convertRowIndexToModel(row))
    }

    private fun openInEditor(ann: DomainAnnotation) {
        // Try to resolve the path against the project's content roots. The annotation path
        // is repo-relative; if multiple roots exist we pick the first match. If no match,
        // attempt absolute resolution (rare).
        val basePath = project.basePath ?: return
        val candidate = LocalFileSystem.getInstance().findFileByPath("$basePath/${ann.path}")
            ?: LocalFileSystem.getInstance().findFileByPath(ann.path)
            ?: return
        if (!ProjectFileIndex.getInstance(project).isInContent(candidate)) {
            // Outside the project; still try to open it.
        }
        // OpenFileDescriptor uses 0-based lines; GitHub annotations are 1-based.
        OpenFileDescriptor(project, candidate, (ann.startLine - 1).coerceAtLeast(0), 0)
            .navigate(true)
    }

    private fun render(state: AnnotationsState) {
        when (state) {
            is AnnotationsState.Idle -> showStatus("Select a run to see its annotations.")
            is AnnotationsState.Loading -> showStatus("Loading annotations…")
            is AnnotationsState.Loaded -> {
                tableModel.setItems(state.items)
                if (state.items.isEmpty()) showStatus("No annotations for this run.")
                else cardLayout.show(cardsPanel, CARD_TABLE)
            }
            is AnnotationsState.Error -> showStatus(friendlyApiError(state.httpStatus, state.message))
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

private class AnnotationsTableModel : AbstractTableModel() {
    private var rows: List<AnnotationsState.Item> = emptyList()

    fun setItems(items: List<AnnotationsState.Item>) {
        rows = items
        fireTableDataChanged()
    }

    fun itemAt(modelRow: Int): AnnotationsState.Item? = rows.getOrNull(modelRow)

    override fun getRowCount() = rows.size
    override fun getColumnCount() = 3
    override fun getColumnName(column: Int) = when (column) {
        0 -> ""
        1 -> "Job · File:Line"
        2 -> "Message"
        else -> ""
    }

    override fun getValueAt(row: Int, column: Int): Any {
        val item = rows[row]
        return when (column) {
            0 -> item.annotation.level
            1 -> "${item.jobName} · ${item.annotation.path}:${item.annotation.startLine}"
            2 -> (item.annotation.title?.let { "$it — " } ?: "") + item.annotation.message
            else -> ""
        }
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        0 -> AnnotationLevel::class.java
        else -> String::class.java
    }
}

private class SeverityRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean,
        row: Int, column: Int
    ): Component {
        super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column)
        val level = value as? AnnotationLevel ?: AnnotationLevel.UNKNOWN
        icon = iconFor(level)
        horizontalAlignment = JLabel.CENTER
        return this
    }

    private fun iconFor(level: AnnotationLevel): Icon = when (level) {
        AnnotationLevel.FAILURE -> AllIcons.General.Error
        AnnotationLevel.WARNING -> AllIcons.General.Warning
        AnnotationLevel.NOTICE -> AllIcons.General.Information
        AnnotationLevel.UNKNOWN -> AllIcons.General.Note
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/AnnotationsPanel.kt
git commit -m "feat(ui): add AnnotationsPanel with double-click open-in-editor"
```

---

## Task 7: Tab integration in `RunDetailPanel`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt`

Add the two new panels as tabs in the existing `JBTabbedPane` (currently *Logs* / *Artifacts*). New order: *Logs · Summary · Annotations · Artifacts*.

- [ ] **Step 1: Add the panels and update the tab pane**

Find the existing field declarations:

```kotlin
private val logViewer = LogViewerPanel()
private val artifactsPanel = ArtifactsPanel(project)

private val detailTabs = com.intellij.ui.components.JBTabbedPane().apply {
    addTab("Logs", logViewer)
    addTab("Artifacts", artifactsPanel)
}
```

Add the two new panels and tabs (reorder so logs stays first; artifacts goes last):

```kotlin
private val logViewer = LogViewerPanel()
private val summaryPanel = SummaryPanel(project)
private val annotationsPanel = AnnotationsPanel(project)
private val artifactsPanel = ArtifactsPanel(project)

private val detailTabs = com.intellij.ui.components.JBTabbedPane().apply {
    addTab("Logs", logViewer)
    addTab("Summary", summaryPanel)
    addTab("Annotations", annotationsPanel)
    addTab("Artifacts", artifactsPanel)
}
```

- [ ] **Step 2: Hand the run id off to the new panels**

Find `showRun(run: Run)` — it currently ends with `artifactsPanel.showRun(run.id)`. Add:

```kotlin
summaryPanel.showRun(run.id)
annotationsPanel.showRun(run.id)
```

- [ ] **Step 3: Dispose the new panels**

Find `dispose()`:

```kotlin
override fun dispose() {
    currentRunFlowJob?.cancel()
    currentLogFlowJob?.cancel()
    artifactsPanel.dispose()
    scope.cancel()
}
```

Add the two disposals before `scope.cancel()`:

```kotlin
override fun dispose() {
    currentRunFlowJob?.cancel()
    currentLogFlowJob?.cancel()
    summaryPanel.dispose()
    annotationsPanel.dispose()
    artifactsPanel.dispose()
    scope.cancel()
}
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 140 tests, no regressions.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt
git commit -m "feat(ui): add Summary and Annotations tabs to run detail"
```

---

## Task 8: Smoke test (deferred-okay) + final sweep + merge

**Files:** none.

- [ ] **Step 1: Manual smoke (deferred is fine)**

Run: `./gradlew --no-daemon runIde`
Click a run with known annotations (a failed CI run on a public repo works; otherwise pick one from your repo where lint flagged something). Confirm:
- *Summary* tab shows one section per job that emitted a check-run summary.
- *Annotations* tab lists the annotations with severity icons, file:line, and message.
- Double-clicking an annotation opens the file at the right line *if* the file exists in the project. Otherwise the click is a no-op (no error dialog, just nothing).
- A run with no annotations shows "No annotations for this run."
- Existing *Logs* and *Artifacts* tabs are unaffected.

- [ ] **Step 2: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS — 140 tests.

- [ ] **Step 3: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED. Existing 1 `SegmentedButton` warning is acceptable. `ProjectFileIndex.isInContent` and `OpenFileDescriptor` are stable APIs; no new warnings expected.

- [ ] **Step 5: Fast-forward merge**

```bash
git checkout main
git merge --ff-only feat/plan-10-summary-annotations
git log --oneline | head -10
```

- [ ] **Step 6: Plan-level verification**

- All 8 tasks have green check-marks (Task 8 Step 1 may be deferred).
- `./gradlew test` passes.
- `./gradlew buildPlugin` and `verifyPlugin` succeed.

---

## What ships at the end of Plan 10

- A *Summary* tab in the run-detail bottom pane displaying each job's check-run summary as plain text.
- An *Annotations* tab listing every annotation across all jobs of the run, with severity icons and file:line.
- Double-clicking an annotation opens the file at the start line via `OpenFileDescriptor` whenever the file exists in the project.
- Both tabs honour the existing `friendlyApiError` rendering on 401/403/404.

What it does **not** yet do:
- Markdown rendering for the summary text (raw markdown source for now).
- Editor gutter markers for annotations (separate plan).
- Problems-tool-window integration.
- SHA mismatch banner ("annotations are from commit X").

---

## Open questions / risks

1. **`check_run_url` may be absent on older runs.** The DTO field is nullable; jobs without a check-run are silently skipped in the aggregator. Empty-state handling already covers this case in the panels.
2. **Annotations endpoint paginates** at 30 by default; v1 fetches first page only. Most CI runs emit fewer than 30 annotations. If a user reports truncation, the fix is paging in `GitHubClient.listAnnotations` — backwards-compatible.
3. **File resolution heuristic.** `project.basePath + path` covers single-module projects. Multi-module repos with content roots that don't include `basePath` are theoretically lossy — but `ProjectFileIndex.isInContent` only logs intent in v1; we still attempt to open the file regardless. If users report misses on monorepos, we'd add a path-walk that tries each content root.
