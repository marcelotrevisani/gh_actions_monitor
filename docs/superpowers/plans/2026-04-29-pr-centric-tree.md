# Plan 4 — PR-Centric Tree View

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat run list in the top half of the tool window with a tree of pull requests, each PR's latest workflow run as its child node, plus filter controls (state · title · branch) and auto-selection of the PR matching the project's currently checked-out branch.

**Architecture:** Add `GitHubClient.listPullRequests(repo, state)` for the new endpoint. Add a `PullRequest` domain entity and a `PullRequestDto` with kotlinx-serialization mapping. Introduce a `PullRequestRepository` project service that fetches PRs and recent runs, then matches each PR to its most recent run by branch name (client-side join — one API call for PRs, one for runs, regardless of PR count). Replace `RunListPanel` with `PullRequestPanel` — a `JTree` with PR-state radio, title-search field, and branch-search field above. Extend `RepoBinding` with a `currentBranch` getter so the panel can preselect the matching PR on first render.

**Tech Stack:** Same as Plans 1–3 — Kotlin 2.0.21, Ktor 2.3.13 (`compileOnly` for kotlinx-coroutines), kotlinx-serialization 1.7.3, JUnit 5 + MockK + Ktor `MockEngine` for tests, IntelliJ Platform 2024.3.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`). Sections covered:
- *UI → View modes → C: PR-centric (default)*.
- *Components → `RepoBinding`* (extends with current-branch reading; the existing implementation already touches `GitRepositoryManager`).
- *Filters → PR / Branch* (the title and branch typeahead inputs).
- The *current PR* concept from the spec's "Definitions" section maps to "PR whose head_ref matches the project's currently checked-out branch".

**Sections deliberately deferred:**
- Live polling, rate-limit awareness — **Plan 5**.
- ANSI color rendering, log viewer polish — **Plan 6**.
- Notifications + status bar widget — **Plan 7+**.
- View modes A (tabbed master/detail) and B (single tree) from the spec — **Plan 8** (only mode C is built here).
- Filters beyond title/branch/state (status, conclusion, actor, event, time range) — **Plan 8** alongside view-mode toggles.

**Plans 1–3 → Plan 4 carry-overs (lessons that still apply):**
- `kotlinx-coroutines-core` stays **`compileOnly`**. Each `implementation`-scoped Ktor dep already runs through the `excludeCoroutines()` helper in `build.gradle.kts`. Don't undo that — the test framework hangs we fixed in Plan 3 came back the moment the platform's coroutines and ours both ended up on the runtime classpath.
- `RunRepository` and any new project-scoped service uses `Dispatchers.IO` by default in production; tests inject `Dispatchers.Unconfined` via the third constructor parameter. **Don't** switch the production default to `Unconfined` (it ran HTTP setup on the EDT and tripped `SlowOperations`).
- Tool-window panels implement `Disposable` and are registered with the `Content`'s disposer in `GhActionsToolWindowFactory`. New panels added in this plan follow the same pattern.
- `BasePlatformTestCase` works again after the kotlinx-coroutines exclusion landed in Plan 2. Use it for tests that need the real project service registry; keep direct-instantiation tests for our project services where possible.
- `service<X>()` (kotlin extension), not `X.getInstance()`.

---

## File Structure

Created or modified by this plan:

```
gh_actions_pycharm/
└── src/
    ├── main/
    │   ├── kotlin/com/example/ghactions/
    │   │   ├── api/
    │   │   │   ├── GitHubClient.kt                       (modify — add listPullRequests)
    │   │   │   └── dto/
    │   │   │       └── PullRequestDto.kt                 (new)
    │   │   ├── domain/
    │   │   │   ├── PullRequest.kt                        (new)
    │   │   │   └── PullRequestState.kt                   (new — OPEN/CLOSED enum + ALL filter sentinel)
    │   │   ├── repo/
    │   │   │   ├── PullRequestRepository.kt              (new — PR list + run matching by branch)
    │   │   │   └── RepoBinding.kt                        (modify — expose currentBranch)
    │   │   └── ui/
    │   │       ├── GhActionsToolWindowFactory.kt         (modify — wire PullRequestPanel)
    │   │       ├── PullRequestPanel.kt                   (new — tree + filter bar)
    │   │       └── RunListPanel.kt                       (delete)
    │   └── resources/
    │       └── META-INF/
    │           └── plugin.xml                            (modify — register PullRequestRepository service)
    └── test/
        └── kotlin/com/example/ghactions/
            ├── api/
            │   ├── dto/
            │   │   └── PullRequestDtoMappingTest.kt      (new)
            │   └── GitHubClientListPullRequestsTest.kt   (new)
            ├── domain/
            │   └── PullRequestEntitiesTest.kt            (new)
            └── repo/
                └── PullRequestRepositoryTest.kt          (new)
```

Files **deleted**: `src/main/kotlin/com/example/ghactions/ui/RunListPanel.kt`. The PR tree is now the only top-panel; if we want a flat-runs view back, it returns alongside the spec's mode-A "All Runs" tab in a later plan.

**File responsibility notes:**
- `PullRequest.kt` and `PullRequestState.kt` stay as separate small files for the same reason `Run.kt` and `Statuses.kt` are split — the domain package is the project's vocabulary, easy to scan when each entity owns a file.
- `PullRequestRepository.kt` is its own service rather than a bolt-on to `RunRepository` because PRs aren't a slice of run state — they have their own lifecycle (state filter, branch match), and conflating them would force `RunRepository` to know about PRs. The repository call sequence is "fetch PRs, fetch runs, match by branch" — that join is the whole reason this class exists.
- `PullRequestPanel.kt` includes the tree and the filter bar in one file. The bar is small (three controls) and tightly coupled to the tree's filtering — splitting them would just shuffle a `FilterState` data class between two tiny files.
- `RepoBinding.kt` change is a surgical addition: a `currentBranch: String?` getter that consults the existing `GitRepositoryManager` instance. No new state, no new threading.

---

## Conventions

- **Tests** stay JUnit 5 (`@Test`, `kotlin.test.*`) for pure logic. `BasePlatformTestCase` for tests that need the real `Project` and `GitRepositoryManager` (the new `RepoBinding.currentBranch` test).
- **HTTP** stays Ktor with `MockEngine` for service-level tests.
- **Coroutines** in `PullRequestRepository` follow the same dispatcher pattern as `RunRepository`: production default `Dispatchers.IO` + `SupervisorJob`; tests inject `Dispatchers.Unconfined`-based scope through the third constructor parameter.
- **Commits** one per task, type-prefixed message, no `Co-Authored-By` trailer.

---

## Task 1: Domain — `PullRequestState` enum

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/domain/PullRequestState.kt`
- Test: covered alongside Task 2's entity tests (no separate test file).

GitHub's API uses lowercase strings (`"open"`, `"closed"`). The plugin also needs an `ALL` value for the filter UI — that's not a wire value, just a UX one.

- [ ] **Step 1: Create the file**

```kotlin
package com.example.ghactions.domain

/**
 * State of a pull request, from GitHub's perspective.
 *
 * The wire values are GitHub's; [ALL] is plugin-only and exists for the filter UI —
 * it tells the API client to omit the `state` parameter (or pass `state=all`).
 */
enum class PullRequestState(val wireValue: String) {
    OPEN("open"),
    CLOSED("closed"),
    ALL("all"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String?): PullRequestState =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/domain/PullRequestState.kt
git commit -m "feat: add PullRequestState enum"
```

---

## Task 2: Domain — `PullRequest` data class

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/domain/PullRequest.kt`
- Test: `src/test/kotlin/com/example/ghactions/domain/PullRequestEntitiesTest.kt`

A small data class plus an `Id` value class — same shape as `Run`/`RunId` from Plan 2.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class PullRequestEntitiesTest {

    @Test
    fun `PullRequestId wraps a long`() {
        val id = PullRequestId(42L)
        assertEquals(42L, id.value)
    }

    @Test
    fun `PullRequest holds the fields we surface`() {
        val pr = PullRequest(
            id = PullRequestId(1L),
            number = 1347,
            state = PullRequestState.OPEN,
            title = "Add login flow",
            authorLogin = "octocat",
            headRef = "feat/login",
            headSha = "abc1234",
            htmlUrl = "https://github.com/octo/repo/pull/1347",
            isDraft = false,
            updatedAt = Instant.parse("2026-04-29T10:00:00Z")
        )
        assertEquals(1347, pr.number)
        assertEquals(PullRequestState.OPEN, pr.state)
        assertEquals("feat/login", pr.headRef)
        assertEquals("octocat", pr.authorLogin)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.domain.PullRequestEntitiesTest"`
Expected: FAIL with `unresolved reference: PullRequest` and `PullRequestId`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.domain

import java.time.Instant

@JvmInline
value class PullRequestId(val value: Long) {
    override fun toString(): String = value.toString()
}

/**
 * One pull request on a GitHub repo. Slimmed to the slice we display in the PR tree.
 *
 * [headRef] is the branch the PR was opened from (i.e., what the local checkout matches when
 * working on the PR). [authorLogin] is null only if GitHub omits the user (deleted account).
 */
data class PullRequest(
    val id: PullRequestId,
    val number: Int,
    val state: PullRequestState,
    val title: String,
    val authorLogin: String?,
    val headRef: String,
    val headSha: String,
    val htmlUrl: String,
    val isDraft: Boolean,
    val updatedAt: Instant
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.domain.PullRequestEntitiesTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/domain/PullRequest.kt \
        src/test/kotlin/com/example/ghactions/domain/PullRequestEntitiesTest.kt
git commit -m "feat: add PullRequest domain entity"
```

---

## Task 3: `PullRequestDto` and mapper

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/api/dto/PullRequestDto.kt`
- Test: `src/test/kotlin/com/example/ghactions/api/dto/PullRequestDtoMappingTest.kt`

GitHub's pulls API returns a nested object (`head: { ref, sha }`, `user: { login, id }`). The DTO mirrors the shape; the mapper flattens to our domain.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.api.dto

import com.example.ghactions.domain.PullRequestId
import com.example.ghactions.domain.PullRequestState
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PullRequestDtoMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val sample = """
        {
          "id": 1,
          "number": 1347,
          "state": "open",
          "title": "Amazing new feature",
          "user": { "login": "octocat", "id": 1 },
          "head": {
            "label": "octocat:new-topic",
            "ref": "new-topic",
            "sha": "6dcb09b5b57875f334f61aebed695e2e4193db5e"
          },
          "base": { "ref": "main", "sha": "abc" },
          "html_url": "https://github.com/octo/repo/pull/1347",
          "created_at": "2020-01-22T19:33:08Z",
          "updated_at": "2020-01-22T19:33:08Z",
          "draft": false
        }
    """.trimIndent()

    @Test
    fun `PullRequestDto deserializes a real GitHub response`() {
        val dto = json.decodeFromString<PullRequestDto>(sample)
        assertEquals(1L, dto.id)
        assertEquals(1347, dto.number)
        assertEquals("open", dto.state)
        assertEquals("octocat", dto.user?.login)
        assertEquals("new-topic", dto.head.ref)
        assertEquals("6dcb09b5b57875f334f61aebed695e2e4193db5e", dto.head.sha)
        assertEquals(false, dto.draft)
    }

    @Test
    fun `PullRequestDto handles closed state and null user`() {
        val closed = sample
            .replace(""""state": "open"""", """"state": "closed"""")
            .replace(""""user": { "login": "octocat", "id": 1 }""", """"user": null""")
        val dto = json.decodeFromString<PullRequestDto>(closed)
        assertEquals("closed", dto.state)
        assertNull(dto.user)
    }

    @Test
    fun `PullRequestDto toDomain produces correct PullRequest`() {
        val dto = json.decodeFromString<PullRequestDto>(sample)
        val pr = dto.toDomain()
        assertEquals(PullRequestId(1L), pr.id)
        assertEquals(1347, pr.number)
        assertEquals(PullRequestState.OPEN, pr.state)
        assertEquals("Amazing new feature", pr.title)
        assertEquals("octocat", pr.authorLogin)
        assertEquals("new-topic", pr.headRef)
        assertEquals(false, pr.isDraft)
        assertEquals(Instant.parse("2020-01-22T19:33:08Z"), pr.updatedAt)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.dto.PullRequestDtoMappingTest"`
Expected: FAIL with `unresolved reference: PullRequestDto`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.api.dto

import com.example.ghactions.domain.PullRequest
import com.example.ghactions.domain.PullRequestId
import com.example.ghactions.domain.PullRequestState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class PullRequestDto(
    val id: Long,
    val number: Int,
    val state: String,
    val title: String,
    val user: UserDto? = null,
    val head: HeadDto,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val draft: Boolean = false
) {
    fun toDomain(): PullRequest = PullRequest(
        id = PullRequestId(id),
        number = number,
        state = PullRequestState.fromWire(state),
        title = title,
        authorLogin = user?.login,
        headRef = head.ref,
        headSha = head.sha,
        htmlUrl = htmlUrl,
        isDraft = draft,
        updatedAt = Instant.parse(updatedAt)
    )
}

/** Minimal head/base shape — we only surface ref + sha. */
@Serializable
data class HeadDto(
    val ref: String,
    val sha: String
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.dto.PullRequestDtoMappingTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/dto/PullRequestDto.kt \
        src/test/kotlin/com/example/ghactions/api/dto/PullRequestDtoMappingTest.kt
git commit -m "feat: add PullRequestDto with kotlinx-serialization mapping"
```

---

## Task 4: `GitHubClient.listPullRequests`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`
- Create: `src/test/kotlin/com/example/ghactions/api/GitHubClientListPullRequestsTest.kt`

Adds a fifth method to `GitHubClient`. The endpoint is paginated; Plan 4 fetches the first page (default 30) — sufficient for the typical workflow of "show me my PRs". A `perPage` parameter lets callers ask for more.

The state parameter accepts `"open"`, `"closed"`, or `"all"`. We pass it through directly using the wire value.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.PullRequestState
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GitHubClientListPullRequestsTest {

    private val auth = AuthSource.Pat("https://api.github.com", "ghp_x")
    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private val sampleResponse = """
        [
          {
            "id": 1,
            "number": 1347,
            "state": "open",
            "title": "Add login flow",
            "user": { "login": "octocat", "id": 1 },
            "head": { "ref": "feat/login", "sha": "abc" },
            "base": { "ref": "main", "sha": "def" },
            "html_url": "https://github.com/octo/Hello-World/pull/1347",
            "created_at": "2020-01-22T19:33:08Z",
            "updated_at": "2020-01-22T19:33:08Z",
            "draft": false
          },
          {
            "id": 2,
            "number": 1348,
            "state": "open",
            "title": "Refactor cache",
            "user": { "login": "hubot", "id": 2 },
            "head": { "ref": "refactor/cache", "sha": "xyz" },
            "base": { "ref": "main", "sha": "ghi" },
            "html_url": "https://github.com/octo/Hello-World/pull/1348",
            "created_at": "2020-01-21T18:30:00Z",
            "updated_at": "2020-01-21T18:35:00Z",
            "draft": true
          }
        ]
    """.trimIndent()

    @Test
    fun `listPullRequests issues GET to pulls endpoint with state and per_page`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { req ->
            captured += req
            respond(sampleResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.listPullRequests(repo, state = PullRequestState.OPEN, perPage = 30)

        assertEquals("GET", captured[0].method.value)
        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/pulls?state=open&per_page=30",
            captured[0].url.toString()
        )
    }

    @Test
    fun `listPullRequests returns mapped domain pull requests`() = runTest {
        val engine = MockEngine { _ ->
            respond(sampleResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        val prs = client.listPullRequests(repo, state = PullRequestState.OPEN)

        assertEquals(2, prs.size)
        assertEquals("Add login flow", prs[0].title)
        assertEquals("feat/login", prs[0].headRef)
        assertEquals(false, prs[0].isDraft)
        assertEquals(true, prs[1].isDraft)
    }

    @Test
    fun `listPullRequests with ALL state passes state=all to the API`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { req ->
            captured += req
            respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.listPullRequests(repo, state = PullRequestState.ALL, perPage = 50)

        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/pulls?state=all&per_page=50",
            captured[0].url.toString()
        )
    }

    @Test
    fun `listPullRequests throws on non-2xx`() = runTest {
        val engine = MockEngine { _ -> respond("Forbidden", HttpStatusCode.Forbidden) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.listPullRequests(repo, state = PullRequestState.OPEN)
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(403, e.status)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubClientListPullRequestsTest"`
Expected: FAIL with `unresolved reference: listPullRequests`.

- [ ] **Step 3: Add the method to `GitHubClient.kt`**

Append inside the `class GitHubClient` body, between `getRunLogsArchive` and `close()`:

```kotlin
    /**
     * `GET /repos/{owner}/{repo}/pulls?state=…&per_page=…`. Paginated; Plan 4 fetches the
     * first page only.
     *
     * `state` accepts the API wire values via [com.example.ghactions.domain.PullRequestState];
     * [PullRequestState.ALL] passes through as `state=all` to GitHub.
     */
    suspend fun listPullRequests(
        repo: BoundRepo,
        state: com.example.ghactions.domain.PullRequestState,
        perPage: Int = 30
    ): List<com.example.ghactions.domain.PullRequest> = withContext(Dispatchers.IO) {
        val response = http.get("/repos/${repo.owner}/${repo.repo}/pulls") {
            parameter("state", state.wireValue.ifEmpty { "all" })
            parameter("per_page", perPage)
        }
        if (!response.status.isSuccess()) {
            throw GitHubApiException(
                status = response.status.value,
                message = "GET pulls failed: ${response.bodyAsText().take(200)}"
            )
        }
        response.body<List<com.example.ghactions.api.dto.PullRequestDto>>().map { it.toDomain() }
    }
```

> Note the `wireValue.ifEmpty { "all" }` — `PullRequestState.UNKNOWN` has an empty wire value, and we shouldn't pass an empty `state=` parameter. Defensive default.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.api.GitHubClientListPullRequestsTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/GitHubClient.kt \
        src/test/kotlin/com/example/ghactions/api/GitHubClientListPullRequestsTest.kt
git commit -m "feat: add GitHubClient.listPullRequests"
```

---

## Task 5: `RepoBinding.currentBranch`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/repo/RepoBinding.kt`
- Modify (optional): `src/test/kotlin/com/example/ghactions/repo/RepoBindingTest.kt` (add a test if straightforward)

A small accessor that returns the local checkout's branch name (or null when detached HEAD / no repo). Used by the PR panel to preselect the matching PR.

- [ ] **Step 1: Read the existing file**

Open `src/main/kotlin/com/example/ghactions/repo/RepoBinding.kt` and find the existing `private fun computeBoundRepo()` method. The class already has access to `GitRepositoryManager.getInstance(project)` — we'll reuse it.

- [ ] **Step 2: Add the accessor**

Add this inside the `RepoBinding` class body, after the existing `recompute()` method (before the `private fun computeBoundRepo`):

```kotlin
    /**
     * The local checkout's current branch name, or null for: detached HEAD, no git
     * repository, or no current branch known. Read each call — the underlying
     * `GitRepository.currentBranch` updates as the user switches branches.
     */
    val currentBranch: String?
        get() {
            val mgr = GitRepositoryManager.getInstance(project)
            val repo = mgr.repositories.firstOrNull() ?: return null
            return repo.currentBranch?.name
        }
```

> `GitRepository.currentBranch` returns `GitLocalBranch?`; `name` is the short branch name (e.g. `"main"`, `"feat/login"`). The IDE listens to git events and updates this — we just read.

- [ ] **Step 3: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/repo/RepoBinding.kt
git commit -m "feat(repo): expose currentBranch for PR auto-selection"
```

---

## Task 6: `PullRequestRepository`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/repo/PullRequestRepository.kt`
- Test: `src/test/kotlin/com/example/ghactions/repo/PullRequestRepositoryTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (register the new project service)

Project-scoped service that does the join: fetch PRs (filtered by state) + fetch recent runs (single call, `perPage=100`), then for each PR walk the runs to find the most recent with `headBranch == pr.headRef`. The result is a `List<PullRequestWithRun>` that the UI renders directly.

The architecture mirrors `RunRepository` from Plan 2: state flows + suspend refresh + injectable scope/factory for tests.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.PullRequest
import com.example.ghactions.domain.PullRequestId
import com.example.ghactions.domain.PullRequestState
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.events.BoundRepo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PullRequestRepositoryTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")
    private fun unconfinedScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun aPr(id: Long, branch: String, state: PullRequestState = PullRequestState.OPEN): PullRequest =
        PullRequest(
            id = PullRequestId(id), number = id.toInt(), state = state, title = "PR $id",
            authorLogin = "octocat", headRef = branch, headSha = "sha-$id",
            htmlUrl = "https://example.com/$id", isDraft = false,
            updatedAt = Instant.parse("2026-04-29T10:00:00Z")
        )

    private fun aRun(id: Long, branch: String, updatedSeconds: Long): Run = Run(
        id = RunId(id), workflowName = "CI",
        status = RunStatus.COMPLETED, conclusion = RunConclusion.SUCCESS,
        headBranch = branch, headSha = "sha-$id", event = "pull_request",
        actorLogin = "octocat",
        createdAt = Instant.parse("2026-04-29T10:00:00Z"),
        updatedAt = Instant.ofEpochSecond(updatedSeconds),
        htmlUrl = "https://example.com/run/$id", runNumber = id.toInt(),
        displayTitle = "title $id"
    )

    @Test
    fun `initial state is Idle`() {
        val client = mockk<GitHubClient>(relaxed = true)
        val repository = PullRequestRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())
        assertIs<PullRequestListState.Idle>(repository.pullRequestsState.value)
    }

    @Test
    fun `refreshPullRequests joins each PR with the most recent matching run by branch`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        val pr1 = aPr(1, branch = "feat/login")
        val pr2 = aPr(2, branch = "feat/cache")
        coEvery { client.listPullRequests(repo, PullRequestState.OPEN, any()) } returns listOf(pr1, pr2)
        coEvery { client.listRunsForRepo(repo, any()) } returns listOf(
            aRun(101, "feat/login", updatedSeconds = 100),
            aRun(102, "feat/login", updatedSeconds = 200), // newer — should win
            aRun(103, "feat/cache", updatedSeconds = 150),
            aRun(104, "main", updatedSeconds = 999)        // unrelated — must be ignored
        )
        val repository = PullRequestRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshPullRequests(PullRequestState.OPEN)

        val state = repository.pullRequestsState.value
        assertIs<PullRequestListState.Loaded>(state)
        assertEquals(2, state.entries.size)
        assertEquals(RunId(102), state.entries[0].latestRun?.id)
        assertEquals(RunId(103), state.entries[1].latestRun?.id)
    }

    @Test
    fun `refreshPullRequests sets latestRun to null when no run matches the PR's branch`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.listPullRequests(repo, PullRequestState.OPEN, any()) } returns listOf(aPr(1, "feat/lonely"))
        coEvery { client.listRunsForRepo(repo, any()) } returns listOf(aRun(101, "main", 100))
        val repository = PullRequestRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshPullRequests(PullRequestState.OPEN)

        val state = repository.pullRequestsState.value
        assertIs<PullRequestListState.Loaded>(state)
        assertEquals(1, state.entries.size)
        assertNull(state.entries[0].latestRun)
    }

    @Test
    fun `refreshPullRequests surfaces API errors as Error state`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.listPullRequests(repo, any(), any()) } throws GitHubApiException(403, "Forbidden")
        val repository = PullRequestRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshPullRequests(PullRequestState.OPEN)

        val state = repository.pullRequestsState.value
        assertIs<PullRequestListState.Error>(state)
        assertEquals(403, state.httpStatus)
    }

    @Test
    fun `refreshPullRequests is a no-op when boundRepo is null`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        val repository = PullRequestRepository(boundRepo = { null }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshPullRequests(PullRequestState.OPEN)

        assertIs<PullRequestListState.Idle>(repository.pullRequestsState.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.repo.PullRequestRepositoryTest"`
Expected: FAIL with unresolved references (`PullRequestRepository`, `PullRequestListState`, `PullRequestWithRun`).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.PullRequest
import com.example.ghactions.domain.PullRequestState
import com.example.ghactions.domain.Run
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

/** State of the PR list (with each PR's matched latest run). */
sealed class PullRequestListState {
    data object Idle : PullRequestListState()
    data object Loading : PullRequestListState()
    data class Loaded(val entries: List<PullRequestWithRun>) : PullRequestListState()
    data class Error(val httpStatus: Int?, val message: String) : PullRequestListState()
}

/** A PR plus the most recent workflow run whose `head_branch` matches [pr.headRef]. */
data class PullRequestWithRun(val pr: PullRequest, val latestRun: Run?)

/**
 * Project-scoped state cache for pull requests. Single source of truth for the PR tree.
 *
 * Production code obtains an instance via `project.getService(PullRequestRepository::class.java)`.
 * Tests construct it directly with explicit dependencies.
 */
@Service(Service.Level.PROJECT)
class PullRequestRepository(
    private val boundRepo: () -> BoundRepo?,
    private val clientFactory: () -> GitHubClient?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Disposable {

    private val log = Logger.getInstance(PullRequestRepository::class.java)

    /** Project-service constructor — used by IntelliJ's DI container. */
    @Suppress("unused")
    constructor(project: Project) : this(
        boundRepo = { project.getService(com.example.ghactions.repo.RepoBinding::class.java).current },
        clientFactory = { com.example.ghactions.repo.ProductionClientFactory.create(project) }
    )

    private val _pullRequestsState = MutableStateFlow<PullRequestListState>(PullRequestListState.Idle)
    val pullRequestsState: StateFlow<PullRequestListState> = _pullRequestsState.asStateFlow()

    /**
     * Fetch PRs filtered by [state] plus the most recent runs for the bound repo, then
     * for each PR pick the most recent run whose `head_branch` matches the PR's head ref.
     */
    fun refreshPullRequests(state: PullRequestState, perPage: Int = 30): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            _pullRequestsState.value =
                PullRequestListState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        _pullRequestsState.value = PullRequestListState.Loading
        _pullRequestsState.value = try {
            val prs = client.listPullRequests(repo, state, perPage)
            // Fetch enough runs that a PR with a recent commit will probably have at least
            // one match; 100 is plenty for typical CI volumes.
            val runs = client.listRunsForRepo(repo, perPage = 100)
            val byBranch = runs.groupBy { it.headBranch }
            val entries = prs.map { pr ->
                val latest = byBranch[pr.headRef].orEmpty()
                    .maxByOrNull { it.updatedAt }
                PullRequestWithRun(pr, latest)
            }
            PullRequestListState.Loaded(entries)
        } catch (e: GitHubApiException) {
            log.warn("listPullRequests failed: status=${e.status}", e)
            PullRequestListState.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("listPullRequests threw unexpectedly", e)
            PullRequestListState.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    fun invalidateAll() {
        _pullRequestsState.value = PullRequestListState.Idle
    }

    override fun dispose() {
        scope.cancel()
    }
}
```

- [ ] **Step 4: Register the service in `plugin.xml`**

Modify `src/main/resources/META-INF/plugin.xml` — extend the existing `<extensions>` block to add the new project service. The block currently has 5 entries (applicationService, RepoBinding projectService, RunRepository projectService, applicationConfigurable, toolWindow). Add the new line right after the existing `RunRepository` registration:

```xml
        <projectService serviceImplementation="com.example.ghactions.repo.RunRepository"/>
        <projectService serviceImplementation="com.example.ghactions.repo.PullRequestRepository"/>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew --no-daemon cleanTest test --tests "com.example.ghactions.repo.PullRequestRepositoryTest"`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/repo/PullRequestRepository.kt \
        src/test/kotlin/com/example/ghactions/repo/PullRequestRepositoryTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: add PullRequestRepository with branch-to-run matching"
```

---

## Task 7: `PullRequestPanel` — tree skeleton

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/PullRequestPanel.kt`

The panel without filtering yet — just the tree structure rendering. Filters land in Task 8. Auto-select-current-branch in Task 9.

- [ ] **Step 1: Create the panel**

```kotlin
package com.example.ghactions.ui

import com.example.ghactions.domain.PullRequest
import com.example.ghactions.domain.PullRequestState
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.repo.PullRequestListState
import com.example.ghactions.repo.PullRequestRepository
import com.example.ghactions.repo.PullRequestWithRun
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Top half of the tool window: a tree of pull requests, each with its latest workflow
 * run as the only child. Selecting a run calls [onRunSelected] so the bottom panel
 * (`RunDetailPanel`) can refresh.
 */
class PullRequestPanel(
    project: Project,
    private val onRunSelected: (Run) -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = project.getService(PullRequestRepository::class.java)

    private val rootNode = DefaultMutableTreeNode("(root)")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        cellRenderer = PrTreeCellRenderer()
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        isRootVisible = false
        addTreeSelectionListener { e ->
            val node = e.path.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            (node.userObject as? Run)?.let(onRunSelected)
        }
    }

    private val statusLabel = JBLabel("").apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        foreground = UIUtil.getContextHelpForeground()
    }

    private val cardLayout = CardLayout()
    private val cardsPanel = JPanel(cardLayout).apply {
        add(JBScrollPane(tree), CARD_TREE)
        add(statusLabel, CARD_STATUS)
    }

    init {
        add(buildToolbar().component, BorderLayout.NORTH)
        add(cardsPanel, BorderLayout.CENTER)
        showStatus("Click Refresh to load pull requests.")
        observeRepository()
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Refresh", "Reload pull requests from GitHub", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    repository.refreshPullRequests(PullRequestState.OPEN)
                }
            })
        }
        val tb = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        tb.targetComponent = this
        return tb
    }

    private fun observeRepository() {
        scope.launch {
            repository.pullRequestsState.collect { state -> render(state) }
        }
    }

    private fun render(state: PullRequestListState) {
        when (state) {
            is PullRequestListState.Idle -> showStatus("Click Refresh to load pull requests.")
            is PullRequestListState.Loading -> showStatus("Loading…")
            is PullRequestListState.Loaded -> {
                rootNode.removeAllChildren()
                state.entries.forEach { entry ->
                    val prNode = DefaultMutableTreeNode(entry.pr)
                    if (entry.latestRun != null) {
                        prNode.add(DefaultMutableTreeNode(entry.latestRun))
                    } else {
                        prNode.add(DefaultMutableTreeNode(NO_RUN_PLACEHOLDER))
                    }
                    rootNode.add(prNode)
                }
                treeModel.reload()
                expandAll()
                cardLayout.show(cardsPanel, CARD_TREE)
            }
            is PullRequestListState.Error -> showStatus(
                "Failed${state.httpStatus?.let { " ($it)" } ?: ""}: ${state.message}"
            )
        }
    }

    private fun showStatus(text: String) {
        statusLabel.text = text
        cardLayout.show(cardsPanel, CARD_STATUS)
    }

    private fun expandAll() {
        for (i in 0 until rootNode.childCount) {
            tree.expandPath(javax.swing.tree.TreePath((rootNode.getChildAt(i) as DefaultMutableTreeNode).path))
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private companion object {
        const val CARD_TREE = "tree"
        const val CARD_STATUS = "status"
        /** Tree-node payload used when a PR has no matching run. */
        const val NO_RUN_PLACEHOLDER = "(no recent runs)"
    }
}

/** Renders PR rows and run rows with status icons. */
private class PrTreeCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode
        when (val payload = node?.userObject) {
            is PullRequest -> {
                val draftBadge = if (payload.isDraft) " [draft]" else ""
                val branch = "[${payload.headRef}]"
                text = "#${payload.number} ${payload.title}$draftBadge $branch"
                icon = AllIcons.Vcs.Vendors.Github
            }
            is Run -> {
                text = "${payload.workflowName} · ${payload.event} · ${humanAge(payload)}"
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

    private fun humanAge(run: Run): String {
        val d = java.time.Duration.between(run.updatedAt, java.time.Instant.now())
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
            d.toHours() < 24 -> "${d.toHours()}h ago"
            else -> "${d.toDays()}d ago"
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/PullRequestPanel.kt
git commit -m "feat(ui): add PullRequestPanel tree skeleton"
```

---

## Task 8: Filter bar (state radio · title · branch)

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/PullRequestPanel.kt`

Adds three controls above the tree: a state radio (Open / Closed / All), a title text field, a branch text field. State changes trigger a refresh (server-side filter); title and branch filter the loaded list client-side as the user types.

- [ ] **Step 1: Add filter state and controls inside `PullRequestPanel`**

Read the current file. After the existing `private val cardsPanel = …` declaration but before the `init { … }` block, add:

```kotlin
    /** UI-side filter state — applied client-side after Loaded transitions. */
    private var titleQuery: String = ""
    private var branchQuery: String = ""
    private var stateFilter: PullRequestState = PullRequestState.OPEN

    /** Most recent Loaded list, kept around so we can re-render on filter changes without re-fetching. */
    private var lastLoaded: List<PullRequestWithRun> = emptyList()

    private val titleField = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = "Filter by title…"
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            private fun onChange() {
                titleQuery = text.trim()
                applyClientFilters()
            }
        })
    }

    private val branchField = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = "Filter by branch…"
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            private fun onChange() {
                branchQuery = text.trim()
                applyClientFilters()
            }
        })
    }

    private val stateRadioOpen = javax.swing.JRadioButton("Open", true)
    private val stateRadioClosed = javax.swing.JRadioButton("Closed")
    private val stateRadioAll = javax.swing.JRadioButton("All")

    init {
        // Group the three radios so only one is selected at a time.
        val group = javax.swing.ButtonGroup()
        listOf(stateRadioOpen, stateRadioClosed, stateRadioAll).forEach { group.add(it) }
        val radioListener = java.awt.event.ActionListener { e ->
            stateFilter = when (e.source) {
                stateRadioOpen -> PullRequestState.OPEN
                stateRadioClosed -> PullRequestState.CLOSED
                stateRadioAll -> PullRequestState.ALL
                else -> stateFilter
            }
            // State changes hit the API.
            repository.refreshPullRequests(stateFilter)
        }
        stateRadioOpen.addActionListener(radioListener)
        stateRadioClosed.addActionListener(radioListener)
        stateRadioAll.addActionListener(radioListener)
    }

    private val filterPanel: JPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 8, 4, 8)
        add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)).apply {
            add(JBLabel("State:"))
            add(stateRadioOpen)
            add(stateRadioClosed)
            add(stateRadioAll)
        })
        add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)).apply {
            add(JBLabel("Title:"))
            titleField.columns = 24
            add(titleField)
            add(JBLabel("Branch:"))
            branchField.columns = 18
            add(branchField)
        })
    }

    private fun applyClientFilters() {
        val visible = lastLoaded.filter { entry ->
            val titleOk = titleQuery.isEmpty() || entry.pr.title.contains(titleQuery, ignoreCase = true)
            val branchOk = branchQuery.isEmpty() || entry.pr.headRef.contains(branchQuery, ignoreCase = true)
            titleOk && branchOk
        }
        renderEntries(visible)
    }

    private fun renderEntries(entries: List<PullRequestWithRun>) {
        rootNode.removeAllChildren()
        entries.forEach { entry ->
            val prNode = DefaultMutableTreeNode(entry.pr)
            if (entry.latestRun != null) {
                prNode.add(DefaultMutableTreeNode(entry.latestRun))
            } else {
                prNode.add(DefaultMutableTreeNode(NO_RUN_PLACEHOLDER))
            }
            rootNode.add(prNode)
        }
        treeModel.reload()
        expandAll()
        cardLayout.show(cardsPanel, CARD_TREE)
    }
```

> Two existing `init { … }` blocks now exist in this class — Kotlin runs them in source order, so the radio-group setup safely happens before the second `init { add(buildToolbar()…) }` block fires.

- [ ] **Step 2: Wire `filterPanel` into the layout**

Find the existing `init { add(buildToolbar().component, BorderLayout.NORTH); add(cardsPanel, BorderLayout.CENTER); … }` block. Replace its first two `add(...)` calls with a top container that stacks the toolbar and the filter panel:

```kotlin
    init {
        val top = JPanel(BorderLayout())
        top.add(buildToolbar().component, BorderLayout.NORTH)
        top.add(filterPanel, BorderLayout.CENTER)
        add(top, BorderLayout.NORTH)
        add(cardsPanel, BorderLayout.CENTER)
        showStatus("Click Refresh to load pull requests.")
        observeRepository()
    }
```

- [ ] **Step 3: Cache `lastLoaded` and route through `applyClientFilters`**

Find the `render` function — specifically the `is PullRequestListState.Loaded` branch. Replace its body so the loaded list is cached and the client filters apply:

```kotlin
            is PullRequestListState.Loaded -> {
                lastLoaded = state.entries
                applyClientFilters()
            }
```

- [ ] **Step 4: Update the Refresh toolbar action to use the current `stateFilter`**

Task 7's `buildToolbar()` hardcoded `PullRequestState.OPEN`. With `stateFilter` now a field, the Refresh button should respect whatever radio is currently selected. Find the existing `actionPerformed` body in `buildToolbar()`:

```kotlin
override fun actionPerformed(e: AnActionEvent) {
    repository.refreshPullRequests(PullRequestState.OPEN)
}
```

Replace with:

```kotlin
override fun actionPerformed(e: AnActionEvent) {
    repository.refreshPullRequests(stateFilter)
}
```

- [ ] **Step 5: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/PullRequestPanel.kt
git commit -m "feat(ui): add filter bar to PullRequestPanel (state radio + title + branch)"
```

---

## Task 9: Auto-select current branch's PR

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/PullRequestPanel.kt`

When the panel renders a Loaded list, if the project's current git branch matches a PR's `headRef`, that PR's run row is auto-selected — which propagates to `RunDetailPanel` and shows the run's logs without the user clicking.

The current branch is read from the existing `RepoBinding.currentBranch` (Task 5). Lookup is on every render so it tracks the user switching branches.

- [ ] **Step 1: Add the auto-select logic**

In `PullRequestPanel.kt`, find the existing field block for `repository`. Below it, add:

```kotlin
    private val repoBinding = project.getService(com.example.ghactions.repo.RepoBinding::class.java)
```

In `renderEntries(entries)`, after the existing `treeModel.reload(); expandAll(); cardLayout.show(...)` lines, add:

```kotlin
        autoSelectCurrentBranch(entries)
```

Add the new method below `renderEntries`:

```kotlin
    /**
     * If a PR's `head_ref` matches the project's current local git branch, select that PR's
     * run row. The selection event fires [onRunSelected], which in turn refreshes the run
     * detail panel below.
     *
     * Called on every render — handles both initial load and filter-driven re-renders. If
     * there's no current branch (detached HEAD), nothing matches the current branch (typical
     * on `main`), or no run is associated with the matching PR, we leave the selection as-is.
     */
    private fun autoSelectCurrentBranch(entries: List<PullRequestWithRun>) {
        val branch = repoBinding.currentBranch ?: return
        val matchIdx = entries.indexOfFirst { it.pr.headRef == branch }
        if (matchIdx < 0) return
        val matched = entries[matchIdx]
        if (matched.latestRun == null) return
        // Path: root → matched PR node → its run child (added at index 0 in renderEntries).
        val prNode = rootNode.getChildAt(matchIdx) as DefaultMutableTreeNode
        val runNode = prNode.getChildAt(0) as DefaultMutableTreeNode
        tree.selectionPath = javax.swing.tree.TreePath(runNode.path)
        tree.scrollPathToVisible(tree.selectionPath)
    }
```

- [ ] **Step 2: Verify build**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/PullRequestPanel.kt
git commit -m "feat(ui): auto-select PR matching the project's current branch"
```

---

## Task 10: Wire `PullRequestPanel` into the tool window

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt`
- Delete: `src/main/kotlin/com/example/ghactions/ui/RunListPanel.kt`

The factory currently builds a split with `RunListPanel` on top and `RunDetailPanel` below. Replace `RunListPanel` with `PullRequestPanel`. The detail panel still needs a `Run` to focus, so the panel callback (run-selected) feeds `detail.showRun(run)` exactly as before.

- [ ] **Step 1: Update `GhActionsToolWindowFactory.kt`**

Find `private fun buildRunView(): Pair<JComponent, List<...Disposable>>`. Replace its body:

```kotlin
    private fun buildRunView(): Pair<JComponent, List<com.intellij.openapi.Disposable>> {
        val detail = RunDetailPanel(project)
        val list = PullRequestPanel(project) { run -> detail.showRun(run) }
        val splitter = OnePixelSplitter(true, 0.35f).apply {
            firstComponent = list
            secondComponent = detail
        }
        return splitter to listOf(list, detail)
    }
```

- [ ] **Step 2: Delete the obsolete `RunListPanel.kt`**

```bash
rm src/main/kotlin/com/example/ghactions/ui/RunListPanel.kt
```

If the deletion breaks compilation (which would mean `RunListPanel` was still referenced somewhere), add the missing reference removal too. As of Task 10's start, `GhActionsToolWindowFactory.kt` was the only consumer.

- [ ] **Step 3: Verify build**

Run: `./gradlew --no-daemon compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt
git add -A src/main/kotlin/com/example/ghactions/ui/  # capture the deletion
git commit -m "feat(ui): replace RunListPanel with PullRequestPanel in tool window"
```

---

## Task 11: Manual smoke test (`./gradlew runIde`)

**Files:** none.

Plan 3's smoke test verified per-step logs. Plan 4's verifies the PR-centric tree, filters, and auto-select.

- [ ] **Step 1: Launch the dev IDE**

Run: `./gradlew --no-daemon runIde`
Expected: sandbox IDE opens.

- [ ] **Step 2: Open a project bound to a real GitHub repo**

The same project used for Plans 1–3 smoke tests is fine, provided it has at least one open pull request. If it doesn't, push a quick branch and open a PR — even a draft works.

- [ ] **Step 3: Click *Refresh* in the tool window**

Expected: the tree populates with one row per open PR. Each row shows `#<num> <title> [<branch>]`. If a PR has a recent workflow run on its head branch, the run appears as a child node with workflow name, event type, and human-readable age (e.g. *"CI · pull_request · 14m ago"*).

- [ ] **Step 4: Verify auto-select**

Look at the PR for whatever local branch you're currently on. If that PR exists in the list, its run child should already be selected — and the bottom panel should already be showing that run's jobs/steps.

- [ ] **Step 5: Test the filters**

- Type a few characters into the *Title* box. The tree filters down to PRs whose titles contain those characters (case-insensitive).
- Type into the *Branch* box. Same, but matching against `head_ref`.
- Click *Closed*. The list re-fetches from GitHub showing only closed PRs.
- Click *All*. Both states.
- Click *Open*. Back to the default.

- [ ] **Step 6: Click a PR's run child**

Expected: `RunDetailPanel` updates to show that run's jobs and steps. Clicking a step shows just that step's output (Plan 3's archive-based extraction).

- [ ] **Step 7: Switch git branches in the project**

In the IDE's git widget, switch to a different branch. The PR panel does NOT auto-refresh by itself — Plan 5 adds that — so click *Refresh*. After refresh, the auto-selected PR should follow the branch.

- [ ] **Step 8: Document deviations**

If anything misbehaves, write a short note at `docs/superpowers/notes/2026-04-29-pr-tree-smoke-deviations.md` and commit it. We address it in Plan 5 unless it's blocking.

```bash
git add docs/superpowers/notes/  # only if the file was created
git commit -m "docs: record PR tree smoke test deviations"
```

If no deviations, skip the commit.

---

## Task 12: Final sweep + merge

**Files:** none — verification and merge only.

- [ ] **Step 1: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS. Plan 4 adds approximately 14 new tests (Task 2: 2, Task 3: 3, Task 4: 4, Task 6: 5). Total cumulative tests should be ~94.

- [ ] **Step 2: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL. Updated zip at `build/distributions/`.

- [ ] **Step 3: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED against IDEA 2024.3 + 2025.1. Acceptable warnings: experimental-API uses (carried forward from Plans 1–3), `JBTextField.emptyText` may be flagged but is a stable API in current platform.

- [ ] **Step 4: Fast-forward merge to `main`**

```bash
git checkout main
git merge --ff-only feat/plan-4-pr-tree
git log --oneline | head -10
```
Expected: clean fast-forward, no merge commit.

- [ ] **Step 5: Plan-level verification**

- All 12 tasks have green check-marks.
- `./gradlew test` passes.
- `./gradlew buildPlugin` succeeds.
- `./gradlew verifyPlugin` succeeds.
- Manual smoke test (Task 11) all green.
- `git log --oneline` on `main` shows the expected commit sequence.

---

## What ships at the end of Plan 4

- A new top panel: `PullRequestPanel` with a tree of pull requests, each showing its latest workflow run as a child node.
- Filters: PR state (Open/Closed/All — server-side via `state=` parameter), title (client-side substring), branch (client-side substring).
- Auto-selection of the PR matching the project's current git branch on each render.
- New API method `GitHubClient.listPullRequests`, new domain types `PullRequest` / `PullRequestId` / `PullRequestState`, new project service `PullRequestRepository`.
- `RunListPanel` retired (replaced).
- Existing `RunDetailPanel` (jobs/steps + per-step logs) continues to receive selection events from the new tree.

What it does **not** yet do (deferred to later plans):
- **Live polling** — every refresh is a manual click on the toolbar. Plan 5.
- **Rate-limit awareness** — Plan 5.
- **ANSI color rendering** in the log viewer — Plan 6.
- **Notifications** when a watched PR's CI completes — Plan 7.
- **View modes A and B** (tabbed master/detail; single tree) from the spec — Plan 8.
- **Filters beyond title/branch/state** (status, conclusion, actor, event, time range) — Plan 8 alongside view-mode toggles.

---

## Open questions / risks

1. **`100`-runs window may miss old PRs.** A long-lived PR whose last commit was weeks ago might not have any of its runs in the most recent 100. The branch-match join would then show "no recent runs" — not technically wrong, but counter-intuitive. Plan 5 (live polling) plus a per-PR explicit `listRunsForBranch` on demand can address this.
2. **Closed-PR filter triggers a fresh fetch** for runs too, even though runs may not have changed. Acceptable for v1; the cache invalidation work in Plan 5's polling-coordinator naturally tightens this.
3. **`JBTextField.emptyText`** — the placeholder API may be flagged "experimental" by the plugin verifier. It's been stable for years; the warning is benign but tracked.
4. **Auto-select does not chase branch switches.** If the user switches the local git branch with the tool window already open, the PR panel only re-runs auto-select on the next refresh. Plan 5 fixes this by subscribing to `GitRepository.MAPPING_CHANGED` (already subscribed by `RepoBinding`) and re-running `autoSelectCurrentBranch` on the existing entries.
