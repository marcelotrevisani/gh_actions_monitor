# Plan 2 — Read-Only Runs (GitHub HTTP client, RunRepository, run list & detail panels)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the plugin actually *show* GitHub Actions data. After this plan, opening the tool window on a project bound to a GitHub repo lists the most recent workflow runs; clicking a run reveals its jobs and steps; clicking a job shows its logs in a read-only editor. All loads are on-demand via a manual *Refresh* button — there is **no live polling yet** (that's Plan 3).

**Architecture:** A new `api/` package wraps GitHub's REST API using Ktor (CIO engine) with kotlinx-serialization for JSON. A new `domain/` package holds plain Kotlin data classes; an `api/dto/` package holds wire DTOs that map into them. The `RunRepository` project service from the spec lands as the single source of truth — `StateFlow<RunListState>` plus per-run `StateFlow<JobsState>` plus per-job `StateFlow<LogState>`. The UI replaces Plan 1's empty state with a vertical split: a `RunListPanel` on top showing recent runs, a `RunDetailPanel` below with a jobs/steps tree and a log viewer. Empty states still apply when there's no repo or no credentials.

**Tech Stack additions over Plan 1:** Ktor client 2.3.x + CIO engine, kotlinx-serialization-json 1.7.x, kotlinx-coroutines-core 1.9.x. Tests use Ktor's `MockEngine` (no external server, no port allocation). All HTTP work runs on `Dispatchers.IO`; UI updates marshalled via `Dispatchers.Main` (which the IntelliJ Platform binds to the EDT).

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`). Sections covered by this plan: *Architecture → `api/`, `domain/`, `repo/` → RunRepository*, *Components → GitHubClient (subset: list runs, list jobs, get job logs only)*, *Representative data flows → A* (opening the tool window), *UI → RunListPanel · RunDetailPanel · LogViewerPanel (subset: just the **Logs** sub-tab)*. **Sections deliberately deferred:** PollingCoordinator (Plan 3), LogStreamReader delta tracking (Plan 3), RateLimitInterceptor (Plan 3), Filters (Plan 4), three view modes (Plan 4), annotations (Plan 5), summary panel (Plan 6), artifacts (Plan 6), write actions (Plan 7), notifications + status bar widget (Plan 8).

**Plan 1 → Plan 2 carry-overs (lessons learned):**
- `BasePlatformTestCase` uses a *light* fixture that does not load this project's `plugin.xml`. Tests that need our `RunRepository` instantiate it directly with the project as a parameter rather than going through `project.getService(...)`.
- The bundled GitHub plugin's API uses `service<GHAccountManager>()` (not `getInstance()`). Anything that touches platform plugin services should be wrapped defensively with try/catch + logger.warn fallback.
- API surface drift is real. Where the plan calls for a specific platform symbol, an implementer who hits an "unresolved reference" should consult the cached IntelliJ jars under `~/.gradle/caches/8.10.2/transforms/.../ideaIC-2024.3/` and substitute the actual API name. The plan flags every spot where this is likely.
- JUnit Vintage engine is already wired (`testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.3")` is in `build.gradle.kts`). Both JUnit 5 (`@Test`) and `BasePlatformTestCase` (JUnit 3-style `testFoo()` methods) tests run side-by-side.

---

## File Structure

Created or modified by this plan:

```
gh_actions_pycharm/
├── build.gradle.kts                                        (modify — add deps)
└── src/
    ├── main/
    │   ├── kotlin/com/example/ghactions/
    │   │   ├── api/
    │   │   │   ├── GitHubClient.kt                          (new) — HTTP entry point
    │   │   │   ├── GitHubHttp.kt                            (new) — Ktor client factory + auth
    │   │   │   └── dto/
    │   │   │       ├── ListResponses.kt                     (new) — { total_count, workflow_runs/jobs }
    │   │   │       ├── RunDto.kt                            (new) — workflow run JSON shape + mapper
    │   │   │       ├── JobDto.kt                            (new) — job/step JSON shape + mapper
    │   │   │       └── UserDto.kt                           (new) — minimal user actor shape
    │   │   ├── domain/
    │   │   │   ├── Ids.kt                                   (new) — RunId, JobId value classes
    │   │   │   ├── Run.kt                                   (new) — Run data class
    │   │   │   ├── Job.kt                                   (new) — Job data class
    │   │   │   ├── Step.kt                                  (new) — Step data class
    │   │   │   └── Statuses.kt                              (new) — RunStatus, RunConclusion enums
    │   │   ├── repo/
    │   │   │   └── RunRepository.kt                         (new) — project service holding StateFlows
    │   │   ├── settings/
    │   │   │   └── GhActionsConfigurable.kt                 (modify — publish AuthChanged)
    │   │   └── ui/
    │   │       ├── GhActionsToolWindowFactory.kt            (modify — switch panel based on state)
    │   │       ├── RunListPanel.kt                          (new) — Swing JBList of runs
    │   │       ├── RunDetailPanel.kt                        (new) — jobs/steps tree + log tab
    │   │       └── LogViewerPanel.kt                        (new) — read-only EditorEx
    │   └── resources/
    │       └── (no changes — services and tool window already registered in plugin.xml)
    └── test/
        ├── kotlin/com/example/ghactions/
        │   ├── api/
        │   │   ├── GitHubHttpTest.kt                        (new) — auth header + base URL
        │   │   ├── GitHubClientListRunsTest.kt              (new) — listRunsForRepo via MockEngine
        │   │   ├── GitHubClientListJobsTest.kt              (new)
        │   │   └── GitHubClientGetLogsTest.kt               (new)
        │   ├── api/dto/
        │   │   ├── RunDtoMappingTest.kt                     (new)
        │   │   └── JobDtoMappingTest.kt                     (new)
        │   └── repo/
        │       └── RunRepositoryTest.kt                     (new) — direct instantiation, not via service registry
        └── resources/github-fixtures/
            ├── runs-list-200.json                            (new)
            ├── jobs-list-200.json                            (new)
            └── job-logs-200.txt                              (new)
```

**File responsibility notes:**
- `GitHubHttp.kt` and `GitHubClient.kt` are split because `GitHubHttp` is the low-level Ktor wiring (engine, base URL, auth header, JSON config) and `GitHubClient` is the high-level method surface. Splitting keeps each file focused: `GitHubHttp` rarely changes after Plan 2; `GitHubClient` grows in every later plan as we add endpoints.
- DTO classes are split per-resource (`RunDto.kt`, `JobDto.kt`) because each one has its own mapper logic and tests. Shared envelope shapes (`ListResponse<T>`) live in `ListResponses.kt`.
- `Ids.kt`, `Statuses.kt`, and the three domain entities (`Run`, `Job`, `Step`) are separate small files because they're independent and the domain package is the project's vocabulary — keeping each in its own file makes them easy to find and modify in isolation.
- `RunRepository.kt` is intentionally one file — the StateFlow shape and the refresh methods are tightly coupled and trying to split would just shuffle private mutable state across files.
- `GhActionsToolWindowFactory.kt` is *modified*, not replaced. The factory now does a content-switching dance based on `RepoBinding.current` and credential availability. Empty state from Plan 1 still wins when nothing's configured.

---

## Conventions for this plan

- **HTTP engine:** Ktor with the **CIO** engine (pure-Kotlin, no extra JVM dep). Tests swap to **MockEngine** by injecting a `HttpClient` directly. `GitHubHttp.create(...)` is a factory function that takes either a real engine or a `MockEngine` — no global singleton.
- **JSON:** kotlinx-serialization-json. All DTOs annotated `@Serializable`. The Ktor client uses the `ContentNegotiation` plugin with `Json { ignoreUnknownKeys = true }` so we don't break when GitHub adds fields.
- **Coroutines & threading:** `GitHubClient` methods are `suspend` functions that internally `withContext(Dispatchers.IO)`. UI panels launch via `project.coroutineScope` (the IntelliJ Platform 2024.3 helper) on `Dispatchers.Main`. No raw `Thread.start()`.
- **Testing pattern for `RunRepository` and `GitHubClient`:** instantiate directly with explicit dependencies — `RunRepository(project, client)`. The `@Service(Service.Level.PROJECT)` annotation is kept for production use, but tests bypass the service registry. This sidesteps `BasePlatformTestCase`'s fixture limitations from Plan 1.
- **Pure unit tests** (`api/dto/*Test`, `domain/*Test`, `RunRepositoryTest`) use **JUnit 5** + `kotlin.test` + `kotlinx-coroutines-test` for `runTest`.
- **Service-level tests** (`GitHubClientListRunsTest`, etc.) use Ktor `MockEngine` — *no* `BasePlatformTestCase`, *no* MockWebServer, *no* network.
- **Commits:** one per task. Same style as Plan 1: `<type>: <imperative summary>`. **No `Co-Authored-By` trailer, ever.**
- **Fixture files** under `src/test/resources/github-fixtures/` are real GitHub responses with secret-bearing fields scrubbed. They're loaded with `this::class.java.getResource("/github-fixtures/<name>").readText()`.

---

## Task 1: Build dependency additions

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add the Kotlin Serialization plugin and the runtime + Ktor + coroutines deps**

In `build.gradle.kts`, change the top-level `plugins` block to include the kotlin serialization plugin:

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}
```

In the `dependencies` block, **after** the existing `intellijPlatform { ... }` inner block and **before** the existing `testImplementation` lines, add:

```kotlin
    // HTTP and JSON
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
```

In the `dependencies` block, **after** the existing `testImplementation(kotlin("test"))` line, add:

```kotlin
    // Ktor MockEngine for service-level tests; coroutines-test for runTest
    testImplementation("io.ktor:ktor-client-mock:2.3.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

- [ ] **Step 2: Verify resolution**

Run: `./gradlew --no-daemon dependencies --configuration runtimeClasspath 2>&1 | grep -E "(ktor|kotlinx-serialization|kotlinx-coroutines-core)" | head -20`
Expected: lines listing `io.ktor:ktor-client-core:2.3.13`, `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`, `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0` and their transitive deps.

- [ ] **Step 3: Smoke-build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: `BUILD SUCCESSFUL`. No code yet uses these deps; this just verifies the build still resolves.

> **If a dependency fails to resolve** (e.g., Ktor 2.3.13 yanked from Maven Central), bump to the closest patch version on `2.3.x`. Do not jump to Ktor 3.x — the package names change and the rest of the plan would need rewrites.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add ktor, kotlinx-serialization, coroutines deps for plan 2"
```

---

## Task 2: Domain enums (`Statuses.kt`)

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/domain/Statuses.kt`
- Test: `src/test/kotlin/com/example/ghactions/domain/StatusesTest.kt`

GitHub's API uses lowercase strings like `"in_progress"`, `"completed"`, `"success"`. We model them as enums with explicit `wireValue` properties so deserialization is reversible and adding new values doesn't break old code.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusesTest {
    @Test
    fun `RunStatus parses known wire values`() {
        assertEquals(RunStatus.QUEUED, RunStatus.fromWire("queued"))
        assertEquals(RunStatus.IN_PROGRESS, RunStatus.fromWire("in_progress"))
        assertEquals(RunStatus.COMPLETED, RunStatus.fromWire("completed"))
        assertEquals(RunStatus.WAITING, RunStatus.fromWire("waiting"))
        assertEquals(RunStatus.PENDING, RunStatus.fromWire("pending"))
    }

    @Test
    fun `RunStatus returns UNKNOWN for unrecognized wire value`() {
        assertEquals(RunStatus.UNKNOWN, RunStatus.fromWire("not_a_real_status"))
    }

    @Test
    fun `RunConclusion parses known wire values and handles null`() {
        assertEquals(RunConclusion.SUCCESS, RunConclusion.fromWire("success"))
        assertEquals(RunConclusion.FAILURE, RunConclusion.fromWire("failure"))
        assertEquals(RunConclusion.CANCELLED, RunConclusion.fromWire("cancelled"))
        assertEquals(RunConclusion.SKIPPED, RunConclusion.fromWire("skipped"))
        assertEquals(RunConclusion.TIMED_OUT, RunConclusion.fromWire("timed_out"))
        assertEquals(RunConclusion.NEUTRAL, RunConclusion.fromWire("neutral"))
        assertEquals(RunConclusion.ACTION_REQUIRED, RunConclusion.fromWire("action_required"))
        assertNull(RunConclusion.fromWire(null))
        assertEquals(RunConclusion.UNKNOWN, RunConclusion.fromWire("startle"))
    }

    @Test
    fun `wireValue round-trip works`() {
        for (s in RunStatus.entries.filter { it != RunStatus.UNKNOWN }) {
            assertEquals(s, RunStatus.fromWire(s.wireValue))
        }
        for (c in RunConclusion.entries.filter { it != RunConclusion.UNKNOWN }) {
            assertEquals(c, RunConclusion.fromWire(c.wireValue))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.domain.StatusesTest"`
Expected: FAIL with "unresolved reference: RunStatus".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.domain

/**
 * Workflow run lifecycle state, per GitHub Actions API. The wire values are GitHub's;
 * the enum names are ours. [UNKNOWN] is a sink for future GitHub additions so we never
 * crash on unrecognized strings.
 */
enum class RunStatus(val wireValue: String) {
    QUEUED("queued"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    WAITING("waiting"),
    REQUESTED("requested"),
    PENDING("pending"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String?): RunStatus =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * Final outcome of a completed run. `null` while a run is still in progress —
 * GitHub returns `null` (not "" or "unknown") for not-yet-concluded runs, and we
 * mirror that.
 */
enum class RunConclusion(val wireValue: String) {
    SUCCESS("success"),
    FAILURE("failure"),
    CANCELLED("cancelled"),
    SKIPPED("skipped"),
    TIMED_OUT("timed_out"),
    NEUTRAL("neutral"),
    ACTION_REQUIRED("action_required"),
    STALE("stale"),
    UNKNOWN("");

    companion object {
        /** Returns null for a null wire value (in-progress run). [UNKNOWN] for unrecognized strings. */
        fun fromWire(value: String?): RunConclusion? = when (value) {
            null -> null
            else -> entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.domain.StatusesTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/domain/Statuses.kt \
        src/test/kotlin/com/example/ghactions/domain/StatusesTest.kt
git commit -m "feat: add RunStatus and RunConclusion enums"
```

---

## Task 3: Domain ID value classes and entities (`Ids.kt`, `Run.kt`, `Job.kt`, `Step.kt`)

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/domain/Ids.kt`
- Create: `src/main/kotlin/com/example/ghactions/domain/Run.kt`
- Create: `src/main/kotlin/com/example/ghactions/domain/Job.kt`
- Create: `src/main/kotlin/com/example/ghactions/domain/Step.kt`
- Test: `src/test/kotlin/com/example/ghactions/domain/EntitiesTest.kt`

GitHub IDs are `long`s, but using a `Long` everywhere risks accidentally passing a job id where a run id was expected. Inline value classes are zero-overhead at runtime and give compile-time safety.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.domain

import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EntitiesTest {
    @Test
    fun `RunId and JobId are distinct types around the same Long`() {
        val r = RunId(123L)
        val j = JobId(123L)
        assertEquals(123L, r.value)
        assertEquals(123L, j.value)
        // Compile-time: RunId and JobId cannot be assigned to each other (covered by typecheck, not runtime).
        // At runtime they're just Long, so this just smoke-checks construction.
        assertNotEquals<Any>(r, j)
    }

    @Test
    fun `Run holds expected fields`() {
        val run = Run(
            id = RunId(1L),
            workflowName = "CI",
            status = RunStatus.IN_PROGRESS,
            conclusion = null,
            headBranch = "main",
            headSha = "abc1234",
            event = "push",
            actorLogin = "octocat",
            createdAt = Instant.parse("2026-04-29T10:00:00Z"),
            updatedAt = Instant.parse("2026-04-29T10:01:00Z"),
            htmlUrl = "https://github.com/octocat/repo/actions/runs/1",
            runNumber = 42,
            displayTitle = "Update README"
        )
        assertEquals(RunId(1L), run.id)
        assertEquals("CI", run.workflowName)
        assertEquals(RunStatus.IN_PROGRESS, run.status)
        assertEquals(null, run.conclusion)
    }

    @Test
    fun `Job holds expected fields including step list`() {
        val step = Step(number = 1, name = "Checkout", status = RunStatus.COMPLETED, conclusion = RunConclusion.SUCCESS)
        val job = Job(
            id = JobId(7L),
            runId = RunId(1L),
            name = "build",
            status = RunStatus.COMPLETED,
            conclusion = RunConclusion.FAILURE,
            startedAt = Instant.parse("2026-04-29T10:00:05Z"),
            completedAt = Instant.parse("2026-04-29T10:01:00Z"),
            htmlUrl = "https://github.com/octocat/repo/actions/runs/1/job/7",
            steps = listOf(step)
        )
        assertEquals(JobId(7L), job.id)
        assertEquals(RunId(1L), job.runId)
        assertEquals(1, job.steps.size)
        assertEquals("Checkout", job.steps[0].name)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.domain.EntitiesTest"`
Expected: FAIL with unresolved references.

- [ ] **Step 3: Write minimal implementation**

`Ids.kt`:

```kotlin
package com.example.ghactions.domain

@JvmInline
value class RunId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class JobId(val value: Long) {
    override fun toString(): String = value.toString()
}
```

`Run.kt`:

```kotlin
package com.example.ghactions.domain

import java.time.Instant

/**
 * One workflow run on a GitHub repo. Roughly mirrors the slice of GitHub's
 * "workflow run" object we surface in the UI. [conclusion] is null while in flight.
 */
data class Run(
    val id: RunId,
    val workflowName: String,
    val status: RunStatus,
    val conclusion: RunConclusion?,
    val headBranch: String?,
    val headSha: String,
    val event: String,
    val actorLogin: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val htmlUrl: String,
    val runNumber: Int,
    val displayTitle: String?
)
```

`Job.kt`:

```kotlin
package com.example.ghactions.domain

import java.time.Instant

data class Job(
    val id: JobId,
    val runId: RunId,
    val name: String,
    val status: RunStatus,
    val conclusion: RunConclusion?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val htmlUrl: String,
    val steps: List<Step>
)
```

`Step.kt`:

```kotlin
package com.example.ghactions.domain

data class Step(
    val number: Int,
    val name: String,
    val status: RunStatus,
    val conclusion: RunConclusion?
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.domain.EntitiesTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/domain/ \
        src/test/kotlin/com/example/ghactions/domain/EntitiesTest.kt
git commit -m "feat: add Run, Job, Step domain entities and Id value classes"
```

---

## Task 4: User DTO + List response envelope

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/api/dto/UserDto.kt`
- Create: `src/main/kotlin/com/example/ghactions/api/dto/ListResponses.kt`

These two are tiny shared DTOs used by Tasks 5 and 6. No tests for these alone — they're exercised by Tasks 5 and 6's tests.

- [ ] **Step 1: Create `UserDto.kt`**

```kotlin
package com.example.ghactions.api.dto

import kotlinx.serialization.Serializable

/**
 * The minimal subset of GitHub's user object we surface — used for the run actor.
 * GitHub returns far more fields; [Json.ignoreUnknownKeys] in [GitHubHttp] handles the rest.
 */
@Serializable
data class UserDto(
    val login: String,
    val id: Long
)
```

- [ ] **Step 2: Create `ListResponses.kt`**

```kotlin
package com.example.ghactions.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GitHub's list-runs envelope: `{ total_count, workflow_runs: [...] }`. */
@Serializable
data class ListRunsResponse(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("workflow_runs") val workflowRuns: List<RunDto>
)

/** GitHub's list-jobs envelope: `{ total_count, jobs: [...] }`. */
@Serializable
data class ListJobsResponse(
    @SerialName("total_count") val totalCount: Int,
    val jobs: List<JobDto>
)
```

- [ ] **Step 3: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: FAIL — `RunDto` and `JobDto` don't exist yet (Tasks 5, 6). That's fine; commit and move on.

> **If you prefer green-build commits**, you can defer this commit until after Tasks 5 and 6 land. The plan keeps it here to mirror the file-structure ordering.

Actually, **swap to a build-green flow**: hold off on this commit. Skip Step 4 and Step 5 below; the files get committed together with Task 5.

- [ ] **Step 4: (skipped — see note above)**

- [ ] **Step 5: (skipped — see note above)**

> Continue to Task 5 with `UserDto.kt` and `ListResponses.kt` on disk but uncommitted.

---

## Task 5: `RunDto` and mapper

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/api/dto/RunDto.kt`
- Test: `src/test/kotlin/com/example/ghactions/api/dto/RunDtoMappingTest.kt`
- Modify (commit-only): `src/main/kotlin/com/example/ghactions/api/dto/UserDto.kt`, `ListResponses.kt` (carried from Task 4)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.api.dto

import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunDtoMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val sample = """
        {
          "id": 30433642,
          "name": "Build",
          "head_branch": "main",
          "head_sha": "009b8a3a9ccbb128af87f9b1c0f4c62e8a304f6d",
          "path": ".github/workflows/build.yml",
          "display_title": "Update README",
          "run_number": 562,
          "event": "push",
          "status": "completed",
          "conclusion": "success",
          "workflow_id": 159038,
          "url": "https://api.github.com/repos/octo/Hello-World/actions/runs/30433642",
          "html_url": "https://github.com/octo/Hello-World/actions/runs/30433642",
          "created_at": "2020-01-22T19:33:08Z",
          "updated_at": "2020-01-22T19:33:08Z",
          "actor": { "login": "octocat", "id": 1 }
        }
    """.trimIndent()

    @Test
    fun `RunDto deserializes a real GitHub response`() {
        val dto = json.decodeFromString<RunDto>(sample)
        assertEquals(30433642L, dto.id)
        assertEquals("Build", dto.name)
        assertEquals("main", dto.headBranch)
        assertEquals("completed", dto.status)
        assertEquals("success", dto.conclusion)
        assertEquals("octocat", dto.actor?.login)
        assertEquals("Update README", dto.displayTitle)
    }

    @Test
    fun `RunDto handles in-progress runs with null conclusion`() {
        val running = sample.replace(""""conclusion": "success"""", """"conclusion": null""")
            .replace(""""status": "completed"""", """"status": "in_progress"""")
        val dto = json.decodeFromString<RunDto>(running)
        assertEquals("in_progress", dto.status)
        assertNull(dto.conclusion)
    }

    @Test
    fun `RunDto toDomain produces correct Run`() {
        val dto = json.decodeFromString<RunDto>(sample)
        val run = dto.toDomain()
        assertEquals(RunId(30433642L), run.id)
        assertEquals("Build", run.workflowName)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(RunConclusion.SUCCESS, run.conclusion)
        assertEquals("main", run.headBranch)
        assertEquals("009b8a3a9ccbb128af87f9b1c0f4c62e8a304f6d", run.headSha)
        assertEquals("push", run.event)
        assertEquals("octocat", run.actorLogin)
        assertEquals(Instant.parse("2020-01-22T19:33:08Z"), run.createdAt)
        assertEquals(562, run.runNumber)
        assertEquals("Update README", run.displayTitle)
    }

    @Test
    fun `unknown status value maps to UNKNOWN, not exception`() {
        val weird = sample.replace(""""status": "completed"""", """"status": "frobnicating"""")
        val dto = json.decodeFromString<RunDto>(weird)
        assertEquals(RunStatus.UNKNOWN, dto.toDomain().status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.dto.RunDtoMappingTest"`
Expected: FAIL with "unresolved reference: RunDto".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.api.dto

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class RunDto(
    val id: Long,
    val name: String,
    @SerialName("head_branch") val headBranch: String? = null,
    @SerialName("head_sha") val headSha: String,
    val path: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("run_number") val runNumber: Int,
    val event: String,
    val status: String,
    val conclusion: String? = null,
    @SerialName("workflow_id") val workflowId: Long,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val actor: UserDto? = null
) {
    fun toDomain(): Run = Run(
        id = RunId(id),
        workflowName = name,
        status = RunStatus.fromWire(status),
        conclusion = RunConclusion.fromWire(conclusion),
        headBranch = headBranch,
        headSha = headSha,
        event = event,
        actorLogin = actor?.login,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
        htmlUrl = htmlUrl,
        runNumber = runNumber,
        displayTitle = displayTitle
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.dto.RunDtoMappingTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit (carries Task 4's two files)**

```bash
git add src/main/kotlin/com/example/ghactions/api/dto/UserDto.kt \
        src/main/kotlin/com/example/ghactions/api/dto/ListResponses.kt \
        src/main/kotlin/com/example/ghactions/api/dto/RunDto.kt \
        src/test/kotlin/com/example/ghactions/api/dto/RunDtoMappingTest.kt
git commit -m "feat: add RunDto with kotlinx-serialization and domain mapping"
```

---

## Task 6: `JobDto` and mapper (with embedded `StepDto`)

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/api/dto/JobDto.kt`
- Test: `src/test/kotlin/com/example/ghactions/api/dto/JobDtoMappingTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.api.dto

import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobDtoMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val sample = """
        {
          "id": 399444496,
          "run_id": 29679449,
          "name": "build",
          "status": "completed",
          "conclusion": "success",
          "started_at": "2020-01-20T17:42:40Z",
          "completed_at": "2020-01-20T17:44:39Z",
          "html_url": "https://github.com/octo/Hello-World/runs/399444496",
          "steps": [
            {
              "name": "Set up job",
              "status": "completed",
              "conclusion": "success",
              "number": 1,
              "started_at": "2020-01-20T09:42:40Z",
              "completed_at": "2020-01-20T09:42:41Z"
            },
            {
              "name": "Run actions/checkout@v2",
              "status": "completed",
              "conclusion": "success",
              "number": 2,
              "started_at": "2020-01-20T09:42:41Z",
              "completed_at": "2020-01-20T09:42:45Z"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `JobDto deserializes a real GitHub response`() {
        val dto = json.decodeFromString<JobDto>(sample)
        assertEquals(399444496L, dto.id)
        assertEquals(29679449L, dto.runId)
        assertEquals("build", dto.name)
        assertEquals("completed", dto.status)
        assertEquals("success", dto.conclusion)
        assertEquals(2, dto.steps.size)
    }

    @Test
    fun `JobDto handles still-running jobs with null timestamps and conclusion`() {
        val running = sample
            .replace(""""status": "completed"""", """"status": "in_progress"""")
            .replace(""""conclusion": "success"""", """"conclusion": null""")
            .replace(""""completed_at": "2020-01-20T17:44:39Z"""", """"completed_at": null""")
        val dto = json.decodeFromString<JobDto>(running)
        assertEquals("in_progress", dto.status)
        assertNull(dto.conclusion)
        assertNull(dto.completedAt)
    }

    @Test
    fun `JobDto toDomain produces correct Job and steps`() {
        val dto = json.decodeFromString<JobDto>(sample)
        val job = dto.toDomain()
        assertEquals(JobId(399444496L), job.id)
        assertEquals(RunId(29679449L), job.runId)
        assertEquals("build", job.name)
        assertEquals(RunStatus.COMPLETED, job.status)
        assertEquals(RunConclusion.SUCCESS, job.conclusion)
        assertEquals(Instant.parse("2020-01-20T17:42:40Z"), job.startedAt)
        assertEquals(2, job.steps.size)
        assertEquals(1, job.steps[0].number)
        assertEquals("Set up job", job.steps[0].name)
        assertEquals(RunStatus.COMPLETED, job.steps[0].status)
        assertEquals(RunConclusion.SUCCESS, job.steps[0].conclusion)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.dto.JobDtoMappingTest"`
Expected: FAIL with "unresolved reference: JobDto".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.api.dto

import com.example.ghactions.domain.Job
import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.domain.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class JobDto(
    val id: Long,
    @SerialName("run_id") val runId: Long,
    val name: String,
    val status: String,
    val conclusion: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val steps: List<StepDto> = emptyList()
) {
    fun toDomain(): Job = Job(
        id = JobId(id),
        runId = RunId(runId),
        name = name,
        status = RunStatus.fromWire(status),
        conclusion = RunConclusion.fromWire(conclusion),
        startedAt = startedAt?.let(Instant::parse),
        completedAt = completedAt?.let(Instant::parse),
        htmlUrl = htmlUrl,
        steps = steps.map { it.toDomain() }
    )
}

@Serializable
data class StepDto(
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val number: Int,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null
) {
    fun toDomain(): Step = Step(
        number = number,
        name = name,
        status = RunStatus.fromWire(status),
        conclusion = RunConclusion.fromWire(conclusion)
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.dto.JobDtoMappingTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/dto/JobDto.kt \
        src/test/kotlin/com/example/ghactions/api/dto/JobDtoMappingTest.kt
git commit -m "feat: add JobDto and StepDto with mappers"
```

---

## Task 7: `GitHubHttp` — Ktor client factory

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/api/GitHubHttp.kt`
- Test: `src/test/kotlin/com/example/ghactions/api/GitHubHttpTest.kt`

`GitHubHttp` is the low-level Ktor wiring: it builds a `HttpClient` configured with auth, JSON serialization, and a base URL. Tests cover that the auth header is computed correctly and that the client can be created with a `MockEngine` for downstream use.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubHttpTest {

    private fun mockClient(capture: MutableList<io.ktor.client.request.HttpRequestData>) =
        GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "ghp_test"),
            engine = MockEngine { request ->
                capture += request
                respond(
                    content = """{"ok": true}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        )

    @Test
    fun `Pat auth source produces 'token <pat>' Authorization header`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = mockClient(captured)
        client.get("/user")
        assertEquals(1, captured.size)
        assertEquals("token ghp_test", captured[0].headers[HttpHeaders.Authorization])
    }

    @Test
    fun `default Accept header is application vnd github plus json`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = mockClient(captured)
        client.get("/user")
        val accept = captured[0].headers[HttpHeaders.Accept]!!
        assertTrue(accept.contains("application/vnd.github+json"), "Accept was: $accept")
    }

    @Test
    fun `User-Agent identifies our plugin`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = mockClient(captured)
        client.get("/user")
        val ua = captured[0].headers[HttpHeaders.UserAgent]!!
        assertTrue(ua.startsWith("gh-actions-monitor/"), "User-Agent was: $ua")
    }

    @Test
    fun `relative path resolves against base URL`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = mockClient(captured)
        client.get("/user")
        assertEquals("https://api.github.com/user", captured[0].url.toString())
    }

    @Test
    fun `body deserializes via configured JSON`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = mockClient(captured)
        val response = client.get("/user")
        assertEquals("""{"ok": true}""", response.bodyAsText())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubHttpTest"`
Expected: FAIL with "unresolved reference: GitHubHttp".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Builds the Ktor HTTP client used by [GitHubClient]. Pulled out so tests can inject a [MockEngine].
 */
object GitHubHttp {

    /** User agent string sent with every request. Bumped manually when the plugin version changes. */
    private const val USER_AGENT = "gh-actions-monitor/0.1"

    /**
     * Constructs a client. Pass [engine]=null in production (the CIO engine is used) or a
     * `MockEngine` from tests. [baseUrl] is e.g. `https://api.github.com` (no trailing slash).
     */
    fun create(
        baseUrl: String,
        auth: AuthSource,
        engine: HttpClientEngine? = null
    ): HttpClient {
        val cleanBase = baseUrl.trimEnd('/')

        val factory: () -> HttpClient = {
            val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    })
                }
                install(HttpTimeout) {
                    connectTimeoutMillis = 5_000
                    requestTimeoutMillis = 30_000
                    socketTimeoutMillis = 30_000
                }
                defaultRequest {
                    // Resolve relative paths against the base URL.
                    url.takeFrom(URLBuilder().takeFrom(cleanBase).apply {
                        encodedPath = "/" + encodedPath.trimStart('/')
                    })
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2022-11-28")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    header(HttpHeaders.Authorization, authHeader(auth))
                }
            }
            if (engine != null) HttpClient(engine, config) else HttpClient(CIO, config)
        }
        return factory()
    }

    private fun authHeader(auth: AuthSource): String = when (auth) {
        is AuthSource.Pat -> "token ${auth.token}"
        // IdeAccount tokens are looked up at request time by [GitHubClient]'s caller; for now
        // we treat IdeAccount as a placeholder until Plan 2 wires the IDE-account credential
        // lookup. See Task 8 for the temporary fall-through.
        is AuthSource.IdeAccount -> "token <pending-ide-account-credentials>"
    }

    /**
     * Helper for tests that need to compute the same default-request headers without a network call.
     * Kept package-internal.
     */
    internal fun defaultHeaders(auth: AuthSource): Map<String, String> = mapOf(
        HttpHeaders.Accept to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        HttpHeaders.UserAgent to USER_AGENT,
        HttpHeaders.Authorization to authHeader(auth)
    )
}

// Small helper to keep the [defaultRequest] block readable.
private fun HttpRequestBuilder.header(name: String, value: String) {
    headers.append(name, value)
}
```

> **About the `IdeAccount` auth temp-stub.** The bundled GitHub plugin stores credentials behind `GHAccountManager.findCredentials(account)`, a `suspend` function. Threading a suspend call through a non-suspend default-request block is awkward; we defer the real wiring to a follow-up task (in this plan, *Task 8: GitHubClient lookup*) that passes the resolved token directly to `GitHubHttp.create(...)`. Until then, IDE-account auth produces a clearly-labeled placeholder and an HTTP 401 if you try to use it. PAT auth is fully functional — that's what the smoke test in Plan 1 already verified end-to-end.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubHttpTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/GitHubHttp.kt \
        src/test/kotlin/com/example/ghactions/api/GitHubHttpTest.kt
git commit -m "feat: add GitHubHttp Ktor client factory"
```

---

## Task 8: `GitHubClient.listRunsForRepo`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`
- Create: `src/test/kotlin/com/example/ghactions/api/GitHubClientListRunsTest.kt`
- Create: `src/test/resources/github-fixtures/runs-list-200.json`

The client is the high-level method surface. In Plan 2 it has three methods: `listRunsForRepo`, `listJobs`, `getJobLogs`. This task adds the first.

`GitHubClient` takes a `BoundRepo` and an already-resolved `String` token, *not* an `AuthSource`. This sidesteps the IDE-account `suspend findCredentials` issue (see Task 7 note): callers (Task 11's `RunRepository`) resolve credentials first via a synchronous helper, then construct the client.

- [ ] **Step 1: Create the fixture file**

`src/test/resources/github-fixtures/runs-list-200.json`:

```json
{
  "total_count": 2,
  "workflow_runs": [
    {
      "id": 30433642,
      "name": "Build",
      "head_branch": "main",
      "head_sha": "009b8a3a9ccbb128af87f9b1c0f4c62e8a304f6d",
      "path": ".github/workflows/build.yml",
      "display_title": "Update README",
      "run_number": 562,
      "event": "push",
      "status": "in_progress",
      "conclusion": null,
      "workflow_id": 159038,
      "url": "https://api.github.com/repos/octo/Hello-World/actions/runs/30433642",
      "html_url": "https://github.com/octo/Hello-World/actions/runs/30433642",
      "created_at": "2020-01-22T19:33:08Z",
      "updated_at": "2020-01-22T19:33:08Z",
      "actor": { "login": "octocat", "id": 1 }
    },
    {
      "id": 30433500,
      "name": "Lint",
      "head_branch": "feat/x",
      "head_sha": "abcd1234567890abcdef",
      "path": ".github/workflows/lint.yml",
      "display_title": "Refactor",
      "run_number": 14,
      "event": "pull_request",
      "status": "completed",
      "conclusion": "success",
      "workflow_id": 159039,
      "url": "https://api.github.com/repos/octo/Hello-World/actions/runs/30433500",
      "html_url": "https://github.com/octo/Hello-World/actions/runs/30433500",
      "created_at": "2020-01-21T18:30:00Z",
      "updated_at": "2020-01-21T18:35:00Z",
      "actor": { "login": "hubot", "id": 2 }
    }
  ]
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubClientListRunsTest {

    private val auth = AuthSource.Pat("https://api.github.com", "ghp_x")
    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private fun fixture(name: String): String =
        this::class.java.getResource("/github-fixtures/$name")!!.readText()

    @Test
    fun `listRunsForRepo issues GET to actions runs endpoint`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { req ->
            captured += req
            respond(fixture("runs-list-200.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.listRunsForRepo(repo, perPage = 30)

        assertEquals("GET", captured[0].method.value)
        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/actions/runs?per_page=30",
            captured[0].url.toString()
        )
    }

    @Test
    fun `listRunsForRepo returns mapped domain runs`() = runTest {
        val engine = MockEngine { _ ->
            respond(fixture("runs-list-200.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        val runs = client.listRunsForRepo(repo, perPage = 30)

        assertEquals(2, runs.size)
        assertEquals("Build", runs[0].workflowName)
        assertEquals(RunStatus.IN_PROGRESS, runs[0].status)
        assertNull(runs[0].conclusion)
        assertEquals("Lint", runs[1].workflowName)
        assertEquals(RunStatus.COMPLETED, runs[1].status)
        assertEquals(RunConclusion.SUCCESS, runs[1].conclusion)
        assertEquals("octocat", runs[0].actorLogin)
    }

    @Test
    fun `listRunsForRepo throws on non-2xx`() = runTest {
        val engine = MockEngine { _ -> respond("Unauthorized", HttpStatusCode.Unauthorized) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.listRunsForRepo(repo, perPage = 30)
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(401, e.status)
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubClientListRunsTest"`
Expected: FAIL with "unresolved reference: GitHubClient" (and `GitHubApiException`).

- [ ] **Step 4: Write minimal implementation**

```kotlin
package com.example.ghactions.api

import com.example.ghactions.api.dto.ListRunsResponse
import com.example.ghactions.domain.Run
import com.example.ghactions.events.BoundRepo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level GitHub REST surface. Methods are `suspend` and run on [Dispatchers.IO].
 *
 * Construct one per active credential; the underlying [HttpClient] is owned by this object
 * and closed via [close] (or by leaving it to GC for short-lived test instances).
 */
class GitHubClient(private val http: HttpClient) : AutoCloseable {

    /**
     * `GET /repos/{owner}/{repo}/actions/runs?per_page=...`. Returns the most recent runs first.
     * Caller is responsible for paging — Plan 2 only fetches the first page.
     */
    suspend fun listRunsForRepo(repo: BoundRepo, perPage: Int = 30): List<Run> = withContext(Dispatchers.IO) {
        val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/runs") {
            parameter("per_page", perPage)
        }
        if (!response.status.isSuccess()) {
            throw GitHubApiException(
                status = response.status.value,
                message = "GET runs failed: ${response.bodyAsText().take(200)}"
            )
        }
        response.body<ListRunsResponse>().workflowRuns.map { it.toDomain() }
    }

    override fun close() {
        http.close()
    }
}

/** Thrown for any non-2xx response from GitHub. The message is truncated for log safety. */
class GitHubApiException(
    val status: Int,
    message: String
) : RuntimeException(message)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubClientListRunsTest"`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/GitHubClient.kt \
        src/test/kotlin/com/example/ghactions/api/GitHubClientListRunsTest.kt \
        src/test/resources/github-fixtures/runs-list-200.json
git commit -m "feat: add GitHubClient.listRunsForRepo with MockEngine tests"
```

---

## Task 9: `GitHubClient.listJobs`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt` (add method)
- Create: `src/test/kotlin/com/example/ghactions/api/GitHubClientListJobsTest.kt`
- Create: `src/test/resources/github-fixtures/jobs-list-200.json`

- [ ] **Step 1: Create the fixture file**

`src/test/resources/github-fixtures/jobs-list-200.json`:

```json
{
  "total_count": 2,
  "jobs": [
    {
      "id": 399444496,
      "run_id": 30433642,
      "name": "build",
      "status": "completed",
      "conclusion": "success",
      "started_at": "2020-01-22T19:33:10Z",
      "completed_at": "2020-01-22T19:35:08Z",
      "html_url": "https://github.com/octo/Hello-World/runs/399444496",
      "steps": [
        { "name": "Set up job", "status": "completed", "conclusion": "success", "number": 1, "started_at": "2020-01-22T19:33:10Z", "completed_at": "2020-01-22T19:33:11Z" },
        { "name": "Run actions/checkout", "status": "completed", "conclusion": "success", "number": 2, "started_at": "2020-01-22T19:33:11Z", "completed_at": "2020-01-22T19:33:14Z" }
      ]
    },
    {
      "id": 399444497,
      "run_id": 30433642,
      "name": "test",
      "status": "in_progress",
      "conclusion": null,
      "started_at": "2020-01-22T19:33:15Z",
      "completed_at": null,
      "html_url": "https://github.com/octo/Hello-World/runs/399444497",
      "steps": []
    }
  ]
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubClientListJobsTest {

    private val auth = AuthSource.Pat("https://api.github.com", "ghp_x")
    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private fun fixture(name: String): String =
        this::class.java.getResource("/github-fixtures/$name")!!.readText()

    @Test
    fun `listJobs issues GET to runs jobs endpoint`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { req ->
            captured += req
            respond(fixture("jobs-list-200.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.listJobs(repo, RunId(30433642L))

        assertEquals("GET", captured[0].method.value)
        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/actions/runs/30433642/jobs",
            captured[0].url.toString()
        )
    }

    @Test
    fun `listJobs returns mapped jobs and steps`() = runTest {
        val engine = MockEngine { _ ->
            respond(fixture("jobs-list-200.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        val jobs = client.listJobs(repo, RunId(30433642L))

        assertEquals(2, jobs.size)
        assertEquals("build", jobs[0].name)
        assertEquals(RunConclusion.SUCCESS, jobs[0].conclusion)
        assertEquals(2, jobs[0].steps.size)
        assertEquals("test", jobs[1].name)
        assertEquals(RunStatus.IN_PROGRESS, jobs[1].status)
        assertNull(jobs[1].conclusion)
        assertNull(jobs[1].completedAt)
    }

    @Test
    fun `listJobs throws on non-2xx`() = runTest {
        val engine = MockEngine { _ -> respond("Not found", HttpStatusCode.NotFound) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.listJobs(repo, RunId(99L))
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(404, e.status)
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubClientListJobsTest"`
Expected: FAIL with "unresolved reference: listJobs".

- [ ] **Step 4: Add the method to `GitHubClient.kt`**

Append inside the `class GitHubClient` body (between `listRunsForRepo` and `close`):

```kotlin
    /** `GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs`. */
    suspend fun listJobs(repo: BoundRepo, runId: com.example.ghactions.domain.RunId): List<com.example.ghactions.domain.Job> =
        withContext(Dispatchers.IO) {
            val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/jobs")
            if (!response.status.isSuccess()) {
                throw GitHubApiException(
                    status = response.status.value,
                    message = "GET jobs failed: ${response.bodyAsText().take(200)}"
                )
            }
            response.body<com.example.ghactions.api.dto.ListJobsResponse>().jobs.map { it.toDomain() }
        }
```

> The fully-qualified `RunId`, `Job`, `ListJobsResponse` references avoid touching the existing import block; the next task moves them to top-of-file imports as part of a small cleanup.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubClientListJobsTest"`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/GitHubClient.kt \
        src/test/kotlin/com/example/ghactions/api/GitHubClientListJobsTest.kt \
        src/test/resources/github-fixtures/jobs-list-200.json
git commit -m "feat: add GitHubClient.listJobs"
```

---

## Task 10: `GitHubClient.getJobLogs`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`
- Create: `src/test/kotlin/com/example/ghactions/api/GitHubClientGetLogsTest.kt`
- Create: `src/test/resources/github-fixtures/job-logs-200.txt`

- [ ] **Step 1: Create the fixture file**

`src/test/resources/github-fixtures/job-logs-200.txt`:

```
2026-04-29T19:33:10.0000000Z ##[group]Run actions/checkout@v4
2026-04-29T19:33:10.1234567Z with:
2026-04-29T19:33:10.2345678Z   ref: main
2026-04-29T19:33:10.3456789Z ##[endgroup]
2026-04-29T19:33:11.0000000Z Cloning repository https://github.com/octo/Hello-World
2026-04-29T19:33:14.5000000Z Done.
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.JobId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubClientGetLogsTest {

    private val auth = AuthSource.Pat("https://api.github.com", "ghp_x")
    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private fun fixture(name: String): String =
        this::class.java.getResource("/github-fixtures/$name")!!.readText()

    @Test
    fun `getJobLogs issues GET to logs endpoint`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { req ->
            captured += req
            respond(fixture("job-logs-200.txt"), HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.getJobLogs(repo, JobId(399444496L))

        assertEquals("GET", captured[0].method.value)
        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/actions/jobs/399444496/logs",
            captured[0].url.toString()
        )
    }

    @Test
    fun `getJobLogs returns the response body as plain text`() = runTest {
        val engine = MockEngine { _ ->
            respond(fixture("job-logs-200.txt"), HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        val logs = client.getJobLogs(repo, JobId(399444496L))

        assertTrue(logs.contains("##[group]Run actions/checkout@v4"))
        assertTrue(logs.contains("Done."))
    }

    @Test
    fun `getJobLogs follows a 302 redirect to download URL`() = runTest {
        // GitHub typically returns a 302 with Location pointing at a signed S3 URL.
        // Ktor's MockEngine doesn't follow redirects by default, so the test asserts that
        // our client treats 200 (post-follow) as success — the redirect is handled by
        // Ktor's followRedirects (default true).
        var called = 0
        val engine = MockEngine { req ->
            called++
            when (called) {
                1 -> respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(
                        "Location" to listOf("https://signed.example.com/dl"),
                        "Content-Type" to listOf("text/plain")
                    )
                )
                else -> respond(
                    fixture("job-logs-200.txt"),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "text/plain")
                )
            }
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        val logs = client.getJobLogs(repo, JobId(399444496L))

        assertTrue(logs.contains("Done."))
        assertEquals(2, called)
    }

    @Test
    fun `getJobLogs throws on non-2xx`() = runTest {
        val engine = MockEngine { _ -> respond("server error", HttpStatusCode.InternalServerError) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.getJobLogs(repo, JobId(1L))
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(500, e.status)
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubClientGetLogsTest"`
Expected: FAIL with "unresolved reference: getJobLogs".

- [ ] **Step 4: Add the method to `GitHubClient.kt`**

Append inside the `class GitHubClient` body, before `close`:

```kotlin
    /**
     * `GET /repos/{owner}/{repo}/actions/jobs/{job_id}/logs`. Returns the full plain-text
     * log. GitHub typically responds with `302 Found` redirecting to a signed download URL;
     * Ktor follows redirects by default, so the body we return is the final 200 body.
     */
    suspend fun getJobLogs(repo: BoundRepo, jobId: com.example.ghactions.domain.JobId): String =
        withContext(Dispatchers.IO) {
            val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/jobs/${jobId.value}/logs")
            if (!response.status.isSuccess()) {
                throw GitHubApiException(
                    status = response.status.value,
                    message = "GET logs failed: ${response.bodyAsText().take(200)}"
                )
            }
            response.bodyAsText()
        }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubClientGetLogsTest"`
Expected: PASS, 4 tests.

> If the redirect-follow test fails because Ktor's MockEngine does not auto-follow, add `followRedirects = true` explicitly in `GitHubHttp.create(...)` (it's the Ktor default but worth being explicit). If the test still fails — say, because MockEngine emits the redirect and Ktor doesn't surface a follow against the same engine — change the redirect test to assert that we *threw* on the 302 and add a separate handling path. The redirect mechanic is documented in `https://ktor.io/docs/client-redirect.html`. **Do not** rewrite the production code to manually follow redirects without first confirming Ktor's default doesn't already cover the real GitHub redirect.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/GitHubClient.kt \
        src/test/kotlin/com/example/ghactions/api/GitHubClientGetLogsTest.kt \
        src/test/resources/github-fixtures/job-logs-200.txt
git commit -m "feat: add GitHubClient.getJobLogs"
```

---

## Task 11: `RunRepository`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`
- Test: `src/test/kotlin/com/example/ghactions/repo/RunRepositoryTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (register service)

The repository owns three flows:
- `runsState: StateFlow<RunListState>` — runs for the current repo.
- `jobsState(runId): StateFlow<JobsState>` — jobs+steps for a specific run.
- `logsState(jobId): StateFlow<LogState>` — text logs for a specific job.

Each flow has `Idle`, `Loading`, `Loaded(data)`, `Error(message)` variants. The repository never throws — failures land in `Error` states.

The repository accepts a *factory* for `GitHubClient` rather than the client itself, because credentials may change after the repository is constructed (auth-changed event). The factory is invoked on each refresh to pick up the latest credentials.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.events.BoundRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RunRepositoryTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private fun aRun(id: Long, status: RunStatus = RunStatus.COMPLETED): Run = Run(
        id = RunId(id),
        workflowName = "CI",
        status = status,
        conclusion = if (status == RunStatus.COMPLETED) RunConclusion.SUCCESS else null,
        headBranch = "main",
        headSha = "deadbee" + id,
        event = "push",
        actorLogin = "octocat",
        createdAt = Instant.parse("2026-04-29T10:00:00Z"),
        updatedAt = Instant.parse("2026-04-29T10:01:00Z"),
        htmlUrl = "https://example.com/$id",
        runNumber = id.toInt(),
        displayTitle = "title $id"
    )

    @Test
    fun `initial runs state is Idle`() {
        val client = mockk<GitHubClient>(relaxed = true)
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client })
        assertIs<RunListState.Idle>(repository.runsState.value)
    }

    @Test
    fun `refreshRuns transitions Idle to Loading to Loaded`() = runTest(StandardTestDispatcher()) {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.listRunsForRepo(repo, any()) } returns listOf(aRun(1), aRun(2))
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client })

        val transitions = mutableListOf<RunListState>()
        val collectorJob = kotlinx.coroutines.GlobalScope.launch {
            repository.runsState.collect { transitions += it }
        }

        repository.refreshRuns()
        advanceUntilIdle()
        collectorJob.cancel()

        assertTrue(transitions.any { it is RunListState.Loading })
        val final = transitions.last()
        assertIs<RunListState.Loaded>(final)
        assertEquals(2, final.runs.size)
    }

    @Test
    fun `refreshRuns surfaces api errors as Error state`() = runTest(StandardTestDispatcher()) {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.listRunsForRepo(repo, any()) } throws GitHubApiException(401, "Unauthorized")
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client })

        repository.refreshRuns()
        advanceUntilIdle()

        val state = repository.runsState.value
        assertIs<RunListState.Error>(state)
        assertEquals(401, state.httpStatus)
    }

    @Test
    fun `refreshJobs populates jobsState for given runId`() = runTest(StandardTestDispatcher()) {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.listJobs(repo, RunId(7L)) } returns emptyList()
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client })

        repository.refreshJobs(RunId(7L))
        advanceUntilIdle()

        assertIs<JobsState.Loaded>(repository.jobsState(RunId(7L)).value)
        coVerify { client.listJobs(repo, RunId(7L)) }
    }

    @Test
    fun `refreshLogs populates logsState for given jobId`() = runTest(StandardTestDispatcher()) {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getJobLogs(repo, JobId(99L)) } returns "log text"
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client })

        repository.refreshLogs(JobId(99L))
        advanceUntilIdle()

        val state = repository.logsState(JobId(99L)).value
        assertIs<LogState.Loaded>(state)
        assertEquals("log text", state.text)
    }

    @Test
    fun `refreshRuns is a no-op when boundRepo is null`() = runTest(StandardTestDispatcher()) {
        val client = mockk<GitHubClient>(relaxed = true)
        val repository = RunRepository(boundRepo = { null }, clientFactory = { client })

        repository.refreshRuns()
        advanceUntilIdle()

        assertIs<RunListState.Idle>(repository.runsState.value)
        coVerify(exactly = 0) { client.listRunsForRepo(any(), any()) }
    }
}
```

> **MockK dependency.** The tests above use [MockK](https://mockk.io). Add to `build.gradle.kts` test deps:
> ```kotlin
>     testImplementation("io.mockk:mockk:1.13.13")
> ```
> Step 0 of the task: edit `build.gradle.kts` to add this line in the test block, then run `./gradlew compileTestKotlin` once to verify resolution.

- [ ] **Step 0: Add MockK dep**

Edit `build.gradle.kts`. After `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")` (added in Task 1), append:

```kotlin
    testImplementation("io.mockk:mockk:1.13.13")
```

Run: `./gradlew --no-daemon compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.repo.RunRepositoryTest"`
Expected: FAIL with "unresolved reference: RunRepository" (and `RunListState`, `JobsState`, `LogState`).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.Job
import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** State of the runs list for the bound repo. */
sealed class RunListState {
    data object Idle : RunListState()
    data object Loading : RunListState()
    data class Loaded(val runs: List<Run>) : RunListState()
    data class Error(val httpStatus: Int?, val message: String) : RunListState()
}

/** State of the jobs list for one specific run. */
sealed class JobsState {
    data object Idle : JobsState()
    data object Loading : JobsState()
    data class Loaded(val jobs: List<Job>) : JobsState()
    data class Error(val httpStatus: Int?, val message: String) : JobsState()
}

/** State of the log text for one specific job. */
sealed class LogState {
    data object Idle : LogState()
    data object Loading : LogState()
    data class Loaded(val text: String) : LogState()
    data class Error(val httpStatus: Int?, val message: String) : LogState()
}

/**
 * Project-scoped state cache. Single source of truth for run, job, and log data.
 * Never throws — errors land in the appropriate StateFlow's [Error] variant.
 *
 * Production code obtains an instance via `project.getService(RunRepository::class.java)`.
 * Tests construct it directly with explicit dependencies (this avoids
 * [BasePlatformTestCase]'s plugin.xml-loading quirk).
 *
 * @param boundRepo  supplier of the current repo binding (or null when unbound)
 * @param clientFactory  supplier of a GitHubClient configured with current credentials
 *                       (called fresh on each refresh so credential changes are picked up)
 * @param scope  coroutine scope for refresh tasks; defaults to a project-tied SupervisorJob
 */
@Service(Service.Level.PROJECT)
class RunRepository(
    private val boundRepo: () -> BoundRepo?,
    private val clientFactory: () -> GitHubClient?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Disposable {

    private val log = Logger.getInstance(RunRepository::class.java)

    /** Project-service constructor used by IntelliJ's DI container. */
    @Suppress("unused")
    constructor(project: Project) : this(
        boundRepo = { project.getService(com.example.ghactions.repo.RepoBinding::class.java).current },
        clientFactory = { ProductionClientFactory.create(project) }
    )

    private val _runsState = MutableStateFlow<RunListState>(RunListState.Idle)
    val runsState: StateFlow<RunListState> = _runsState.asStateFlow()

    private val _jobsByRun = ConcurrentHashMap<RunId, MutableStateFlow<JobsState>>()
    fun jobsState(runId: RunId): StateFlow<JobsState> =
        _jobsByRun.computeIfAbsent(runId) { MutableStateFlow(JobsState.Idle) }.asStateFlow()

    private val _logsByJob = ConcurrentHashMap<JobId, MutableStateFlow<LogState>>()
    fun logsState(jobId: JobId): StateFlow<LogState> =
        _logsByJob.computeIfAbsent(jobId) { MutableStateFlow(LogState.Idle) }.asStateFlow()

    fun refreshRuns(perPage: Int = 30): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            _runsState.value = RunListState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        _runsState.value = RunListState.Loading
        _runsState.value = try {
            RunListState.Loaded(client.listRunsForRepo(repo, perPage))
        } catch (e: GitHubApiException) {
            log.warn("listRunsForRepo failed: status=${e.status}", e)
            RunListState.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("listRunsForRepo threw unexpectedly", e)
            RunListState.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    fun refreshJobs(runId: RunId): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            jobsFlow(runId).value = JobsState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        jobsFlow(runId).value = JobsState.Loading
        jobsFlow(runId).value = try {
            JobsState.Loaded(client.listJobs(repo, runId))
        } catch (e: GitHubApiException) {
            log.warn("listJobs failed: status=${e.status}", e)
            JobsState.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("listJobs threw unexpectedly", e)
            JobsState.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    fun refreshLogs(jobId: JobId): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            logsFlow(jobId).value = LogState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        logsFlow(jobId).value = LogState.Loading
        logsFlow(jobId).value = try {
            LogState.Loaded(client.getJobLogs(repo, jobId))
        } catch (e: GitHubApiException) {
            log.warn("getJobLogs failed: status=${e.status}", e)
            LogState.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("getJobLogs threw unexpectedly", e)
            LogState.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    fun invalidateAll() {
        _runsState.value = RunListState.Idle
        _jobsByRun.values.forEach { it.value = JobsState.Idle }
        _logsByJob.values.forEach { it.value = LogState.Idle }
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun jobsFlow(runId: RunId): MutableStateFlow<JobsState> =
        _jobsByRun.computeIfAbsent(runId) { MutableStateFlow(JobsState.Idle) }

    private fun logsFlow(jobId: JobId): MutableStateFlow<LogState> =
        _logsByJob.computeIfAbsent(jobId) { MutableStateFlow(LogState.Idle) }
}

/** Holder for the production GitHubClient factory — kept out of the [RunRepository] body for clarity. */
internal object ProductionClientFactory {
    fun create(project: Project): GitHubClient? {
        val binding = project.getService(com.example.ghactions.repo.RepoBinding::class.java).current ?: return null
        val settings = com.example.ghactions.auth.PluginSettings.getInstance().state

        val resolver = com.example.ghactions.auth.GitHubAccountResolver(
            ideSource = com.example.ghactions.auth.BundledGithubAccountSource(),
            patLookup = object : com.example.ghactions.auth.PatLookup {
                override fun getToken(host: String) = com.example.ghactions.auth.PatStorage().getToken(host)
            },
            preferredAccountId = settings.preferredAccountId
        )
        val auth = resolver.resolve(binding.host) ?: return null

        // For Plan 2: only PAT auth produces a usable client.
        // IDE-account auth would require an async findCredentials() call — deferred to Plan 3.
        val token = when (auth) {
            is com.example.ghactions.auth.AuthSource.Pat -> auth.token
            is com.example.ghactions.auth.AuthSource.IdeAccount -> {
                Logger.getInstance(ProductionClientFactory::class.java)
                    .warn("IDE-account credentials not yet wired in Plan 2; user must use a PAT for now.")
                return null
            }
        }
        val patAsAuth = com.example.ghactions.auth.AuthSource.Pat(host = binding.host, token = token)
        val http = com.example.ghactions.api.GitHubHttp.create(binding.host, patAsAuth)
        return GitHubClient(http)
    }
}
```

- [ ] **Step 4: Register the service in `plugin.xml`**

Modify `src/main/resources/META-INF/plugin.xml`. The existing `<extensions>` block should now contain *five* entries — the four from Plan 1 plus the new RunRepository:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.example.ghactions.auth.PluginSettings"/>
        <projectService serviceImplementation="com.example.ghactions.repo.RepoBinding"/>
        <projectService serviceImplementation="com.example.ghactions.repo.RunRepository"/>
        <applicationConfigurable
            id="com.example.ghactions.settings"
            displayName="GitHub Actions Monitor"
            instance="com.example.ghactions.settings.GhActionsConfigurable"
            parentId="tools"/>
        <toolWindow
            id="GitHubActions"
            anchor="right"
            icon="AllIcons.Vcs.Vendors.Github"
            factoryClass="com.example.ghactions.ui.GhActionsToolWindowFactory"/>
    </extensions>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.repo.RunRepositoryTest"`
Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/repo/RunRepository.kt \
        src/test/kotlin/com/example/ghactions/repo/RunRepositoryTest.kt \
        src/main/resources/META-INF/plugin.xml \
        build.gradle.kts
git commit -m "feat: add RunRepository with state flows for runs/jobs/logs"
```

---

## Task 12: Publish `AuthChanged` from settings

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/settings/GhActionsConfigurable.kt`
- Test: `src/test/kotlin/com/example/ghactions/settings/AuthChangedPublishTest.kt`

When the user saves settings, the resolver might now find different credentials. The repository needs to know so it can `invalidateAll()`. Plan 1 declared the `AUTH_CHANGED` topic but never published.

This is application-scoped, so we publish on the application MessageBus. Subscribers (the `RunRepository` per project) react in their service constructor.

> Plan 2 doesn't yet have RunRepository subscribing — that wiring lives inside Task 13's tool window factory rewrite. The publish-side is decoupled and lands here.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.settings

import com.example.ghactions.events.AuthChangedListener
import com.example.ghactions.events.Topics
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AuthChangedPublishTest : BasePlatformTestCase() {

    fun testApplyPublishesAuthChanged() {
        var received = false
        val conn = ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
        conn.subscribe(Topics.AUTH_CHANGED, AuthChangedListener { received = true })

        // Simulate the configurable's apply() — instantiate and call.
        val configurable = GhActionsConfigurable()
        configurable.createComponent()
        configurable.apply()
        configurable.disposeUIResources()

        assertTrue("Expected AUTH_CHANGED to fire when settings.apply() is called", received)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.settings.AuthChangedPublishTest"`
Expected: FAIL — assertion error (the topic isn't published yet).

- [ ] **Step 3: Modify `GhActionsConfigurable.kt`**

Replace the file's `apply()` method. Read the current file first; then change it to:

```kotlin
    override fun apply() {
        panel?.apply()
        // Notify subscribers (RunRepository, EmptyStatePanel) that credentials may have changed.
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .syncPublisher(com.example.ghactions.events.Topics.AUTH_CHANGED)
            .onAuthChanged()
    }
```

The full file becomes:

```kotlin
package com.example.ghactions.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class GhActionsConfigurable : Configurable {
    private var panel: GhActionsSettingsPanel? = null

    override fun getDisplayName(): String = "GitHub Actions Monitor"

    override fun createComponent(): JComponent {
        val p = GhActionsSettingsPanel()
        panel = p
        return p.component
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
        // Notify subscribers (RunRepository, EmptyStatePanel) that credentials may have changed.
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .syncPublisher(com.example.ghactions.events.Topics.AUTH_CHANGED)
            .onAuthChanged()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.settings.AuthChangedPublishTest"`
Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/settings/GhActionsConfigurable.kt \
        src/test/kotlin/com/example/ghactions/settings/AuthChangedPublishTest.kt
git commit -m "feat: publish AuthChanged event when settings are applied"
```

---

## Task 13: `LogViewerPanel`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/LogViewerPanel.kt`

The panel is a thin wrapper over an IntelliJ `EditorEx` configured for read-only display of plain text. It exposes `setText(String)` and `clear()`. ANSI parsing and step-section folding are deferred to Plan 3 — Plan 2 just shows the raw text.

- [ ] **Step 1: Create the panel**

```kotlin
package com.example.ghactions.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.Disposable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Read-only viewer for GitHub Actions job logs. Plan 2 displays raw text; Plan 3 will add
 * ANSI color parsing and step-section folding.
 */
class LogViewerPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val editorFactory = EditorFactory.getInstance()
    private val document = editorFactory.createDocument("")
    private val editor: EditorEx = editorFactory.createViewer(document, project) as EditorEx

    init {
        editor.setHighlighter(
            com.intellij.openapi.editor.ex.util.LexerEditorHighlighter(
                com.intellij.openapi.editor.ex.util.EmptyEditorHighlighter(
                    com.intellij.openapi.editor.colors.EditorColorsManager.getInstance()
                        .globalScheme.getAttributes(com.intellij.openapi.editor.HighlighterColors.TEXT)
                ),
                com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
            )
        )
        editor.settings.apply {
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isLineNumbersShown = false
            isCaretRowShown = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
        editor.headerComponent = null
        editor.gutterComponentEx.isPaintBackground = false

        border = JBUI.Borders.empty()
        add(editor.component, BorderLayout.CENTER)
    }

    /** Replace the displayed text. Safe to call from any thread; marshals to EDT. */
    fun setText(text: String) {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                document.setText(text)
            }
            // Auto-scroll to end so users see the latest output.
            editor.caretModel.moveToOffset(document.textLength)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        }
    }

    fun clear() = setText("")

    override fun dispose() {
        editorFactory.releaseEditor(editor)
    }
}
```

> **About the highlighter setup.** `EditorEx` requires a highlighter; the empty/plain-text highlighter is the lightest option. The deeply-nested constructor calls in `setHighlighter(...)` are the canonical pattern for "no syntax highlighting" — JetBrains' own `BinaryFileViewer` and similar plugins use the same shape. If the `EmptyEditorHighlighter` constructor has shifted in 2024.3, the simplest fallback is to use `com.intellij.openapi.editor.ex.util.LexerEditorHighlighter` with `null`-fileType — the `setHighlighter` call can be removed entirely if the editor still renders plain text correctly.

- [ ] **Step 2: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/LogViewerPanel.kt
git commit -m "feat: add LogViewerPanel (read-only log display)"
```

---

## Task 14: `RunListPanel`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/RunListPanel.kt`

A `JBList<Run>` with a custom cell renderer showing status icon · workflow · branch · age · actor. Selection callback fires on click. A toolbar above the list provides a *Refresh* action. Loading/error states render as a centered label.

- [ ] **Step 1: Create the panel**

```kotlin
package com.example.ghactions.ui

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.repo.RunListState
import com.example.ghactions.repo.RunRepository
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.time.Duration
import java.time.Instant
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.ListCellRenderer

/**
 * Top half of the tool window: the list of recent runs for the bound repo, with a refresh
 * action above. Selection changes call back via [onRunSelected].
 */
class RunListPanel(
    private val project: Project,
    private val onRunSelected: (Run) -> Unit
) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(RunRepository::class.java)

    private val listModel = DefaultListModel<Run>()
    private val list = JBList(listModel).apply {
        cellRenderer = RunCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                selectedValue?.let(onRunSelected)
            }
        }
    }

    private val statusLabel = JBLabel("").apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        foreground = UIUtil.getContextHelpForeground()
    }

    private val cardLayout = CardLayout()
    private val cardsPanel = JPanel(cardLayout).apply {
        add(JBScrollPane(list), CARD_LIST)
        add(statusLabel, CARD_STATUS)
    }

    init {
        add(buildToolbar().component, BorderLayout.NORTH)
        add(cardsPanel, BorderLayout.CENTER)
        showStatus("Click Refresh to load runs.")
        observeRepository()
    }

    private fun buildToolbar(): com.intellij.openapi.actionSystem.ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Refresh", "Reload runs from GitHub", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    repository.refreshRuns()
                }
            })
        }
        val tb = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        tb.targetComponent = this
        return tb
    }

    private fun observeRepository() {
        scope.launch {
            repository.runsState.collect { state -> render(state) }
        }
    }

    private fun render(state: RunListState) {
        when (state) {
            is RunListState.Idle -> showStatus("Click Refresh to load runs.")
            is RunListState.Loading -> showStatus("Loading…")
            is RunListState.Loaded -> {
                listModel.clear()
                state.runs.forEach { listModel.addElement(it) }
                cardLayout.show(cardsPanel, CARD_LIST)
            }
            is RunListState.Error -> showStatus(
                "Failed${state.httpStatus?.let { " ($it)" } ?: ""}: ${state.message}"
            )
        }
    }

    private fun showStatus(text: String) {
        statusLabel.text = text
        cardLayout.show(cardsPanel, CARD_STATUS)
    }

    override fun dispose() {
        scope.cancel()
    }

    private companion object {
        const val CARD_LIST = "list"
        const val CARD_STATUS = "status"
    }
}

/** Single-row renderer: status icon · workflow name · branch · actor · age. */
private class RunCellRenderer : ListCellRenderer<Run> {
    private val template = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        border = JBUI.Borders.empty(4, 8)
    }
    private val icon = JBLabel()
    private val workflow = JBLabel().apply { font = font.deriveFont(java.awt.Font.BOLD) }
    private val branch = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val age = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val actor = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        template.add(icon)
        template.add(workflow)
        template.add(branch)
        template.add(actor)
        template.add(age)
    }

    override fun getListCellRendererComponent(
        list: JList<out Run>?,
        value: Run?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val run = value ?: return template
        icon.icon = iconFor(run)
        workflow.text = run.workflowName + (run.displayTitle?.let { " — $it" } ?: "")
        branch.text = run.headBranch?.let { "[$it]" } ?: ""
        actor.text = run.actorLogin?.let { "@$it" } ?: ""
        age.text = humanAge(run.updatedAt)
        template.background = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
        return template
    }

    private fun iconFor(run: Run) = when {
        run.status == RunStatus.IN_PROGRESS || run.status == RunStatus.QUEUED || run.status == RunStatus.PENDING ->
            AllIcons.Actions.Play_forward
        run.conclusion == RunConclusion.SUCCESS -> AllIcons.RunConfigurations.TestPassed
        run.conclusion == RunConclusion.FAILURE -> AllIcons.RunConfigurations.TestFailed
        run.conclusion == RunConclusion.CANCELLED -> AllIcons.Actions.Cancel
        else -> AllIcons.RunConfigurations.TestNotRan
    }

    private fun humanAge(instant: Instant): String {
        val d = Duration.between(instant, Instant.now())
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
            d.toHours() < 24 -> "${d.toHours()}h ago"
            else -> "${d.toDays()}d ago"
        }
    }
}
```

> **About the icon names.** `AllIcons.RunConfigurations.TestPassed` / `TestFailed` exist in 2024.3+. If they're not found, substitute any green-check / red-X icon — `AllIcons.General.InspectionsOK` and `AllIcons.General.Error` are universal fallbacks.

- [ ] **Step 2: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/RunListPanel.kt
git commit -m "feat: add RunListPanel with refresh action and cell renderer"
```

---

## Task 15: `RunDetailPanel`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt`

The detail panel has two regions: a jobs/steps tree on top, and the `LogViewerPanel` below in a splitter. Selecting a job loads its logs. Selecting a step does nothing in Plan 2 (Plan 3 will navigate the log viewer to that step's section).

- [ ] **Step 1: Create the panel**

```kotlin
package com.example.ghactions.ui

import com.example.ghactions.domain.Job
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.domain.Step
import com.example.ghactions.repo.JobsState
import com.example.ghactions.repo.LogState
import com.example.ghactions.repo.RunRepository
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Bottom half of the tool window: jobs/steps tree on top, log viewer below.
 * Plan 2 implements the *Logs* sub-view only — annotations/summary/artifacts tabs
 * are added in Plans 5 and 6.
 */
class RunDetailPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(RunRepository::class.java)

    private val rootNode = DefaultMutableTreeNode("(no run selected)")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        cellRenderer = JobStepCellRenderer()
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        addTreeSelectionListener { e ->
            val node = e.path.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val payload = node.userObject) {
                is Job -> showJobLogs(payload)
                else -> Unit // Step selection is a no-op in Plan 2.
            }
        }
        isRootVisible = false
    }

    private val logViewer = LogViewerPanel(project).also { com.intellij.openapi.util.Disposer.register(this, it) }

    private val emptyMessage = JBLabel("Select a run to see its jobs.").apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = UIUtil.getContextHelpForeground()
    }

    private val splitter = OnePixelSplitter(true, 0.4f).apply {
        firstComponent = JPanel(BorderLayout()).apply {
            add(com.intellij.ui.components.JBScrollPane(tree), BorderLayout.CENTER)
        }
        secondComponent = logViewer
    }

    private var currentRunFlowJob: CJob? = null
    private var currentLogFlowJob: CJob? = null

    init {
        border = JBUI.Borders.empty()
        add(splitter, BorderLayout.CENTER)
    }

    fun showRun(run: Run) {
        rootNode.userObject = run
        rootNode.removeAllChildren()
        treeModel.reload()
        repository.refreshJobs(run.id)
        currentRunFlowJob?.cancel()
        currentRunFlowJob = scope.launch {
            repository.jobsState(run.id).collect { state -> renderJobs(state) }
        }
    }

    private fun renderJobs(state: JobsState) {
        when (state) {
            is JobsState.Idle, is JobsState.Loading -> {
                rootNode.removeAllChildren()
                rootNode.add(DefaultMutableTreeNode("(loading jobs…)"))
                treeModel.reload()
            }
            is JobsState.Loaded -> {
                rootNode.removeAllChildren()
                state.jobs.forEach { job ->
                    val jobNode = DefaultMutableTreeNode(job)
                    job.steps.forEach { step -> jobNode.add(DefaultMutableTreeNode(step)) }
                    rootNode.add(jobNode)
                }
                treeModel.reload()
                expandAllJobs()
            }
            is JobsState.Error -> {
                rootNode.removeAllChildren()
                rootNode.add(DefaultMutableTreeNode("Failed: ${state.message}"))
                treeModel.reload()
            }
        }
    }

    private fun showJobLogs(job: Job) {
        repository.refreshLogs(job.id)
        currentLogFlowJob?.cancel()
        currentLogFlowJob = scope.launch {
            repository.logsState(job.id).collect { state ->
                when (state) {
                    is LogState.Idle -> logViewer.clear()
                    is LogState.Loading -> logViewer.setText("(loading logs…)")
                    is LogState.Loaded -> logViewer.setText(state.text)
                    is LogState.Error -> logViewer.setText("Failed${state.httpStatus?.let { " ($it)" } ?: ""}: ${state.message}")
                }
            }
        }
    }

    private fun expandAllJobs() {
        for (i in 0 until rootNode.childCount) {
            tree.expandPath(TreePath((rootNode.getChildAt(i) as DefaultMutableTreeNode).path))
        }
    }

    override fun dispose() {
        currentRunFlowJob?.cancel()
        currentLogFlowJob?.cancel()
        scope.cancel()
    }
}

private class JobStepCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode
        when (val payload = node?.userObject) {
            is Job -> {
                text = payload.name
                icon = iconFor(payload.status, payload.conclusion)
            }
            is Step -> {
                text = "${payload.number}. ${payload.name}"
                icon = iconFor(payload.status, payload.conclusion)
            }
            else -> {
                text = node?.userObject?.toString() ?: ""
                icon = null
            }
        }
        return this
    }

    private fun iconFor(status: RunStatus, conclusion: RunConclusion?) = when {
        status == RunStatus.IN_PROGRESS || status == RunStatus.QUEUED -> AllIcons.Actions.Play_forward
        conclusion == RunConclusion.SUCCESS -> AllIcons.RunConfigurations.TestPassed
        conclusion == RunConclusion.FAILURE -> AllIcons.RunConfigurations.TestFailed
        conclusion == RunConclusion.CANCELLED -> AllIcons.Actions.Cancel
        else -> AllIcons.RunConfigurations.TestNotRan
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt
git commit -m "feat: add RunDetailPanel with jobs/steps tree and log viewer"
```

---

## Task 16: Wire panels into the tool window

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt`

The factory now picks one of two panels based on whether `RepoBinding.current` and credentials exist:
- **Empty state** (`EmptyStatePanel` from Plan 1) — when binding is null, when no credentials are available, OR when binding/creds are present but the user has not yet refreshed (initial state shows a friendly hint to click *Refresh*).
- **Run view** — a vertical split: `RunListPanel` on top, `RunDetailPanel` on bottom.

The factory subscribes to `AUTH_CHANGED` and `REPO_BINDING_CHANGED` and rebuilds the tool window content when either fires.

- [ ] **Step 1: Replace `GhActionsToolWindowFactory.kt`**

```kotlin
package com.example.ghactions.ui

import com.example.ghactions.auth.BundledGithubAccountSource
import com.example.ghactions.auth.GitHubAccountResolver
import com.example.ghactions.auth.PatLookup
import com.example.ghactions.auth.PatStorage
import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.events.AuthChangedListener
import com.example.ghactions.events.RepoBindingChangedListener
import com.example.ghactions.events.Topics
import com.example.ghactions.repo.RepoBinding
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.OnePixelSplitter
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

class GhActionsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val controller = ToolWindowController(project, toolWindow)
        controller.refresh()

        val appBus = ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable)
        appBus.subscribe(Topics.AUTH_CHANGED, AuthChangedListener { controller.refresh() })

        val projectBus = project.messageBus.connect(toolWindow.disposable)
        projectBus.subscribe(Topics.REPO_BINDING_CHANGED, RepoBindingChangedListener { controller.refresh() })
    }

    companion object {
        const val ID = "GitHubActions"
    }
}

private class ToolWindowController(
    private val project: Project,
    private val toolWindow: ToolWindow
) {
    fun refresh() {
        val binding = project.getService(RepoBinding::class.java).current
        val hasCreds = binding != null && hasCredentialsFor(binding.host)

        toolWindow.contentManager.removeAllContents(true)

        val panel: JComponent = when {
            binding == null || !hasCreds -> EmptyStatePanel(project)
            else -> buildRunView()
        }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun buildRunView(): JComponent {
        val detail = RunDetailPanel(project)
        val list = RunListPanel(project) { run -> detail.showRun(run) }
        return OnePixelSplitter(true, 0.35f).apply {
            firstComponent = list
            secondComponent = detail
        }
    }

    private fun hasCredentialsFor(host: String): Boolean {
        val settings = PluginSettings.getInstance().state
        val resolver = GitHubAccountResolver(
            ideSource = BundledGithubAccountSource(),
            patLookup = object : PatLookup {
                override fun getToken(host: String) = PatStorage().getToken(host)
            },
            preferredAccountId = settings.preferredAccountId
        )
        return resolver.resolve(host) != null
    }
}
```

> The previous `companion object { const val ID = "GitHubActions" }` is preserved so the existing `ToolWindowFactoryTest` (which references `GhActionsToolWindowFactory.ID`) keeps passing.

- [ ] **Step 2: Run existing tests to confirm nothing regressed**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS — every prior test passes plus the Plan 2 ones.

- [ ] **Step 3: Build the plugin**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt
git commit -m "feat: switch tool window between empty state and run view"
```

---

## Task 17: Manual smoke test (`./gradlew runIde`)

**Files:** none.

Plan 1's smoke test verified the empty states. Plan 2 verifies the data path: open a project bound to a real GitHub repo with a working PAT in settings, and confirm the runs list renders.

- [ ] **Step 1: Launch a development IDE**

Run: `./gradlew --no-daemon runIde`
Expected: sandbox IDE opens.

- [ ] **Step 2: Open a project bound to a real GitHub repo**

In the dev IDE, open any GitHub-cloned project (the same one used in Plan 1's smoke test is fine). Confirm Settings → Tools → GitHub Actions Monitor still shows the saved PAT.

- [ ] **Step 3: Click *Refresh* in the tool window**

The runs list should populate within a few seconds. Each row should show a status icon, workflow name, branch tag, actor, and human-readable age.

- [ ] **Step 4: Click a run**

The detail panel should show the jobs tree and start loading. Click a job. The log viewer should display the job's text logs.

- [ ] **Step 5: Click *Refresh* again**

Should re-fetch and re-render without errors.

- [ ] **Step 6: Test rate-limit recovery (manual)**

Disconnect from the network briefly, click Refresh. The list should show *"Failed: …"* with an explanatory message rather than hanging or crashing.

- [ ] **Step 7: Document deviations**

If anything misbehaves, note it under a *"Smoke test deviations"* heading at the bottom of this plan and commit. We address it in Plan 3 unless it blocks usability.

- [ ] **Step 8: Commit any documentation changes**

```bash
git add docs/superpowers/plans/2026-04-29-read-only-runs.md
git commit -m "docs: record smoke test results for read-only runs plan"
```

If no deviations, skip this commit.

---

## Task 18: Final sweep

**Files:** none.

- [ ] **Step 1: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS. Plan 2 adds approximately 30 new tests across `StatusesTest` (4), `EntitiesTest` (3), `RunDtoMappingTest` (4), `JobDtoMappingTest` (3), `GitHubHttpTest` (5), `GitHubClientListRunsTest` (3), `GitHubClientListJobsTest` (3), `GitHubClientGetLogsTest` (4), `RunRepositoryTest` (6), `AuthChangedPublishTest` (1) — total ~36 new, on top of Plan 1's 25.

- [ ] **Step 2: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL. Updated zip at `build/distributions/`.

- [ ] **Step 3: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED. Acceptable warnings: experimental API uses (e.g. `OnePixelSplitter`, JBList constructors), the existing `SegmentedButton.whenItemSelectedFromUi` from Plan 1, and any new internal-API calls flagged.

- [ ] **Step 4: Fixups if needed**

If the verifier flags actual incompatibilities (not just experimental warnings), fix them. Common patterns:
- A class moved between IDE versions: adjust the import.
- An API became internal: switch to the public alternative.

```bash
git commit -am "fix: address plugin verifier findings"
```

If clean, skip.

---

## Plan-level verification (run after Task 18)

- [ ] All 18 tasks have green check-marks.
- [ ] `./gradlew test` passes (~61 tests total).
- [ ] `./gradlew buildPlugin` produces a zip.
- [ ] `./gradlew verifyPlugin` passes.
- [ ] Manual smoke test (Task 17) all green.
- [ ] `git log --oneline | head -25` shows the expected commit sequence.

---

## What ships at the end of Plan 2

A working IDE plugin that:

1. Lists the most recent 30 workflow runs for the bound repo, fetched on demand via a *Refresh* button.
2. Lets the user click a run to see its jobs/steps tree.
3. Lets the user click a job to see its full text logs in a read-only editor.
4. Reuses Plan 1's auth resolution (PAT preferred; IDE-account auth deferred to Plan 3 wiring).
5. Falls back to clean error states (no creds → empty state, API error → message in the list panel) instead of crashing.
6. Republishes `AuthChanged` so the next plan's polling coordinator can react.

What it does **not** yet do:
- Live polling — every refresh is a single manual click. Plan 3.
- Rate-limit awareness or graceful slowdown. Plan 3.
- Filters (status, branch, actor, …). Plan 4.
- Three view modes (PR-centric, tabbed, tree). Plan 4.
- Annotations, summaries, artifacts. Plans 5–6.
- Run / re-run actions. Plan 7.
- Notifications + status bar widget. Plan 8.

---

## Open questions / risks

1. **IDE-account auth.** Plan 2 only supports PAT auth in the data path. The settings dropdown for "Use IDE-configured GitHub account" is wired (Plan 1) but selecting an account doesn't currently produce a usable client because the bundled `GHAccountManager.findCredentials(account)` is a `suspend` function we don't invoke yet. The factory in `RunRepository.ProductionClientFactory` warns and returns null for `IdeAccount` auth. **Plan 3 should add a `suspend` credentials lookup wired through the polling coordinator's coroutine scope.**
2. **Ktor MockEngine redirect handling.** Task 10 step 5 depends on Ktor's default `followRedirects = true`. If the test fails on this, we have a fallback in the task notes but it's worth verifying early.
3. **`EmptyEditorHighlighter` constructor drift.** Task 13's `LogViewerPanel` initialization uses a particular sequence of nested constructors that has shifted between platform versions. If it doesn't compile, the simplest fix is to remove the explicit `setHighlighter(...)` call entirely.
4. **`AllIcons.RunConfigurations.TestPassed` / `TestFailed`** — these have existed since at least 2021.x and should resolve. Fallbacks are in the task notes.
5. **MockK vs MockEngine.** I'm using both (MockK for `RunRepository` tests where we mock `GitHubClient`, MockEngine for `GitHubClient` tests where we mock the HTTP transport). Both are well-maintained but the dual dependency means slightly more dep weight. If the build feels heavy in Plan 3, we can replace MockK with hand-written stubs.
