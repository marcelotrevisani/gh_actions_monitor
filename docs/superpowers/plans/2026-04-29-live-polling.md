# Plan 6 — Adaptive Live Polling + Rate-Limit Awareness

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-refresh the runs/PR tree on an adaptive cadence (fast while a run is active, slow when everything is terminal, paused when the tool window is hidden or the user toggled polling off). Honor GitHub's rate limits — back off entirely on `429` and on `403 + X-RateLimit-Remaining: 0` until the reset timestamp; surface `Retry-After` when present.

**Architecture:** Three pure components plus one project service. (1) `RateLimitInfo` parsed from response headers and held in `RateLimitTracker` (project service, `StateFlow<RateLimitInfo>`); fed by a Ktor `ResponseObserver` installed in `GitHubHttp`. (2) `RateLimitedException` thrown by `GitHubClient` when the wire response indicates a hard limit so calling code can react. (3) `BackoffPoller` — a pure `computeNextDelay(state)` function plus a tiny suspend `loop()` driver so virtual-time tests with `StandardTestDispatcher` cover the cadence logic without real waits. (4) `PollingCoordinator` (`@Service(Service.Level.PROJECT)`) wires it together: subscribes to `ToolWindowManagerListener` for visibility, polls `RunRepository` + `PullRequestRepository` on each tick, reads `PluginSettings.livePollingEnabled` live, and consults `RateLimitTracker` before each tick.

**Tech Stack:** Same as Plans 1–5 — Kotlin 2.0.21, Ktor 2.3.13 with kotlinx-coroutines `compileOnly`, kotlinx-serialization 1.7.3, JUnit 5 + MockK + Ktor MockEngine + `kotlinx-coroutines-test` (already on the classpath as a test dep) for virtual-time tests, IntelliJ Platform 2024.3.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`). Sections covered:
- *Polling & rate-limit strategy* — simplified to a 2-tier model (`ACTIVE` / `IDLE`) for v1; the 4-tier table in the spec (Foreground / Tracked / Visible-list / Idle) is a future refinement.
- *Components → `polling/PollingCoordinator`* — implemented here.
- *Components → `api/RateLimitInterceptor`* — implemented as a Ktor `ResponseObserver` feeding `RateLimitTracker`.
- *Settings → Live polling enabled* — already exposed in `PluginSettings`; this plan makes it functional.

**Sections deliberately deferred:**
- 4-tier polling (Foreground 3s logs, Tracked 15s, Visible-list 30s, Idle 60s) — **Plan 8 / Plan 9**. Plan 6 ships 2 tiers.
- Tool-window banner ("Live updates paused — rate-limited") — Plan 9.
- Status-bar widget surfacing rate-limit state — Plan 9+.
- Conditional `If-None-Match` / 304 handling — future optimization plan.
- Foreground (in-progress job log) tailing — Plan 8.

**Plans 1–5 → Plan 6 carry-overs (lessons that still apply):**
- `kotlinx-coroutines-core` stays **`compileOnly`**, with `excludeCoroutines()` on every Ktor `implementation`. Test code can still depend on `kotlinx-coroutines-test` at `testImplementation`; that does NOT trigger the LinkageError because it never ships in the plugin distribution.
- Production `Dispatchers.IO`; tests inject `Dispatchers.Unconfined` *or* `StandardTestDispatcher` with virtual time. Don't flip the production default.
- `service<X>()` extension, never `X.getInstance()` for project-level services. App-level `PluginSettings.getInstance()` stays as it is.
- Tool-window panels register with the `Content`'s disposer in `GhActionsToolWindowFactory`. Plan 6 doesn't add new panels but the new `PollingCoordinator` service registers as a `Disposable` via `@Service(Service.Level.PROJECT)` and is disposed by the platform when the project closes.
- `clientFactory` is now `suspend () -> GitHubClient?` after Plan 5 — `PollingCoordinator` reuses the same factory, so its tick code is suspend.
- Commits one per task, type-prefixed message, **no `Co-Authored-By` trailer**.

---

## File Structure

Created or modified by this plan:

```
gh_actions_pycharm/
├── src/
│   ├── main/
│   │   └── kotlin/com/example/ghactions/
│   │       ├── api/
│   │       │   ├── RateLimitInfo.kt              (new — pure model + header parser)
│   │       │   ├── RateLimitTracker.kt           (new — project service + StateFlow)
│   │       │   ├── GitHubHttp.kt                 (modify — install ResponseObserver fed into the tracker)
│   │       │   └── GitHubClient.kt               (modify — throw RateLimitedException on hard limits)
│   │       ├── polling/
│   │       │   ├── BackoffPoller.kt              (new — pure computeNextDelay + loop driver)
│   │       │   └── PollingCoordinator.kt         (new — project service wiring everything together)
│   │       └── ui/
│   │           └── GhActionsToolWindowFactory.kt (modify — start coordinator when first content created; track visibility)
│   └── test/
│       └── kotlin/com/example/ghactions/
│           ├── api/
│           │   ├── RateLimitInfoTest.kt          (new — header parsing)
│           │   └── GitHubClientRateLimitTest.kt  (new — 429 and 403+remaining=0 paths)
│           └── polling/
│               └── BackoffPollerTest.kt          (new — virtual-time tests for cadence)
└── build.gradle.kts                              (modify — add kotlinx-coroutines-test if missing)
```

**File responsibility notes:**
- `RateLimitInfo` is a pure data class with no IDE deps — kept in `api/` next to other on-the-wire types. The parser is a top-level function in the same file (`fun fromHeaders(headers: io.ktor.http.Headers): RateLimitInfo`), one responsibility, easy to unit test.
- `RateLimitTracker` is the IDE-facing facade. Pure model lives in `RateLimitInfo.kt`; the service lives separately so tests on the parser need no IDE platform.
- `BackoffPoller` separates the policy (pure `computeNextDelay`) from the driver (suspend `loop`). The loop is small enough to live in the same file.
- `PollingCoordinator` owns the project-scoped `CoroutineScope` (`SupervisorJob() + Dispatchers.IO`). Visibility, settings, and rate-limit-state reads happen here. No business logic — that's all in `BackoffPoller`.
- `GhActionsToolWindowFactory` already runs at first tool-window open. We add a single line that starts the coordinator (which auto-pauses if not visible/disabled).

---

## Conventions

- **Tests** stay JUnit 5 (`@Test`, `kotlin.test.*`). The poller tests use `runTest { }` from `kotlinx.coroutines.test` with `StandardTestDispatcher`; `advanceTimeBy(...)` and `runCurrent()` simulate elapsed seconds without waiting. The `GitHubClient` rate-limit tests use Ktor's `MockEngine` (already in use across Plans 2–4).
- **No platform tests in this plan.** Everything testable is testable as pure logic. The coordinator's wiring is verified by the manual smoke (Task 7).
- **One commit per task**, type-prefixed (`feat:`, `test:`, `fix:` etc.), no `Co-Authored-By`.
- **Cumulative test count target after Plan 6:** 103 (Plan 5) + 4 (`RateLimitInfo` parser) + 3 (`GitHubClient` rate-limit) + 5 (`BackoffPoller`) = ~115. The plan's Step "verify it passes" assertions name an exact count for each task; the final sweep checks the cumulative total.

---

## Task 1: `RateLimitInfo` model + header parser

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/api/RateLimitInfo.kt`
- Test: `src/test/kotlin/com/example/ghactions/api/RateLimitInfoTest.kt`

This is the pure on-the-wire model. No coroutines, no IDE deps. Used by both the response observer (Task 2) and the poller (Task 5).

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/api/RateLimitInfoTest.kt`:

```kotlin
package com.example.ghactions.api

import io.ktor.http.Headers
import io.ktor.http.headersOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitInfoTest {

    @Test
    fun `fromHeaders parses standard rate-limit triple`() {
        val headers = headersOf(
            "X-RateLimit-Limit" to listOf("5000"),
            "X-RateLimit-Remaining" to listOf("4321"),
            "X-RateLimit-Reset" to listOf("1714427200")
        )
        val info = RateLimitInfo.fromHeaders(headers)
        assertEquals(5000, info.limit)
        assertEquals(4321, info.remaining)
        assertEquals(1714427200L, info.resetEpochSeconds)
        assertNull(info.retryAfterSeconds)
        assertFalse(info.isHardLimited)
    }

    @Test
    fun `fromHeaders parses Retry-After (seconds)`() {
        val headers = headersOf(
            "Retry-After" to listOf("60"),
            "X-RateLimit-Remaining" to listOf("0"),
            "X-RateLimit-Reset" to listOf("1714427200")
        )
        val info = RateLimitInfo.fromHeaders(headers)
        assertEquals(60, info.retryAfterSeconds)
        assertEquals(0, info.remaining)
        assertTrue(info.isHardLimited)
    }

    @Test
    fun `fromHeaders returns NONE when no rate-limit headers present`() {
        val info = RateLimitInfo.fromHeaders(Headers.Empty)
        assertEquals(RateLimitInfo.NONE, info)
        assertFalse(info.isHardLimited)
    }

    @Test
    fun `isHardLimited true when remaining is zero, even without Retry-After`() {
        val headers = headersOf(
            "X-RateLimit-Limit" to listOf("5000"),
            "X-RateLimit-Remaining" to listOf("0"),
            "X-RateLimit-Reset" to listOf("1714427200")
        )
        val info = RateLimitInfo.fromHeaders(headers)
        assertNotNull(info.resetEpochSeconds)
        assertTrue(info.isHardLimited)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.api.RateLimitInfoTest`
Expected: FAIL — compilation error "unresolved reference: RateLimitInfo".

- [ ] **Step 3: Implement `RateLimitInfo`**

Create `src/main/kotlin/com/example/ghactions/api/RateLimitInfo.kt`:

```kotlin
package com.example.ghactions.api

import io.ktor.http.Headers

/**
 * Snapshot of GitHub's rate-limit headers from the most recent response.
 *
 * Pure value type — no IDE deps. Parsed via [fromHeaders]; held by `RateLimitTracker`
 * (a project service) so the poller can consult it before scheduling each tick.
 *
 * `isHardLimited` is the polling-relevant predicate: true means "do not call the API
 * again before [resetEpochSeconds] (or until the [retryAfterSeconds] window has passed)".
 */
data class RateLimitInfo(
    val limit: Int? = null,
    val remaining: Int? = null,
    val resetEpochSeconds: Long? = null,
    val retryAfterSeconds: Int? = null
) {
    val isHardLimited: Boolean
        get() = retryAfterSeconds != null || remaining == 0

    companion object {
        val NONE = RateLimitInfo()

        fun fromHeaders(headers: Headers): RateLimitInfo {
            val limit = headers["X-RateLimit-Limit"]?.toIntOrNull()
            val remaining = headers["X-RateLimit-Remaining"]?.toIntOrNull()
            val reset = headers["X-RateLimit-Reset"]?.toLongOrNull()
            val retryAfter = headers["Retry-After"]?.toIntOrNull()
            if (limit == null && remaining == null && reset == null && retryAfter == null) {
                return NONE
            }
            return RateLimitInfo(
                limit = limit,
                remaining = remaining,
                resetEpochSeconds = reset,
                retryAfterSeconds = retryAfter
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.api.RateLimitInfoTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/RateLimitInfo.kt \
        src/test/kotlin/com/example/ghactions/api/RateLimitInfoTest.kt
git commit -m "feat(api): add RateLimitInfo with header parser"
```

---

## Task 2: `RateLimitTracker` service + Ktor `ResponseObserver`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/api/RateLimitTracker.kt`
- Modify: `src/main/kotlin/com/example/ghactions/api/GitHubHttp.kt`

The tracker holds the latest `RateLimitInfo` for a project; the observer plugin updates it on every HTTP response. No automated tests for this task — its surface area is two lines of plumbing; behavior is verified through `BackoffPollerTest` (which feeds a `RateLimitInfo` directly) and through the smoke test.

- [ ] **Step 1: Read the existing `GitHubHttp.kt`**

Run: `cat src/main/kotlin/com/example/ghactions/api/GitHubHttp.kt`
Expected: confirm the current factory signature is `fun create(baseUrl, auth, engine = null): HttpClient`.

- [ ] **Step 2: Create `RateLimitTracker.kt`**

Create `src/main/kotlin/com/example/ghactions/api/RateLimitTracker.kt`:

```kotlin
package com.example.ghactions.api

import com.intellij.openapi.components.Service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Project-scoped holder for the most recent [RateLimitInfo] observed on any HTTP
 * response. The poller consults [state] before every tick; if `isHardLimited` is true,
 * the tick is skipped until the reset window passes.
 *
 * Updated by the Ktor `ResponseObserver` installed in `GitHubHttp.create`.
 */
@Service(Service.Level.PROJECT)
class RateLimitTracker {
    private val _state = MutableStateFlow(RateLimitInfo.NONE)
    val state: StateFlow<RateLimitInfo> = _state.asStateFlow()

    fun update(info: RateLimitInfo) {
        // Only overwrite when the response actually carried headers — a 200 from
        // a non-rate-limited endpoint without RL headers (rare for GitHub but possible
        // for redirects) shouldn't blow away our last-known good state.
        if (info != RateLimitInfo.NONE) {
            _state.value = info
        }
    }
}
```

- [ ] **Step 3: Wire the observer into `GitHubHttp`**

Modify `src/main/kotlin/com/example/ghactions/api/GitHubHttp.kt`. Change the `create` signature to take an optional `onResponse` callback, and have `GitHubClient`'s caller (in `ProductionClientFactory`, Task 3 below) supply a callback that forwards into the project's tracker.

Replace the entire file with:

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.http.HttpHeaders
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Builds the Ktor HTTP client used by [GitHubClient]. Pulled out so tests can inject a [MockEngine].
 */
object GitHubHttp {

    private const val USER_AGENT = "gh-actions-monitor/0.1"

    /**
     * Constructs a client. Pass [engine]=null in production (the CIO engine is used) or a
     * `MockEngine` from tests. [baseUrl] is e.g. `https://api.github.com` (no trailing slash).
     *
     * [onResponse] is invoked on the IO dispatcher for every response (success or failure)
     * and is the integration point for `RateLimitTracker`. Tests typically pass `null`.
     */
    fun create(
        baseUrl: String,
        auth: AuthSource,
        engine: HttpClientEngine? = null,
        onResponse: ((RateLimitInfo) -> Unit)? = null
    ): HttpClient {
        val cleanBase = baseUrl.trimEnd('/')

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
            if (onResponse != null) {
                install(ResponseObserver) {
                    onResponse { response ->
                        onResponse(RateLimitInfo.fromHeaders(response.headers))
                    }
                }
            }
            defaultRequest {
                url.takeFrom(cleanBase)
                headers.append(HttpHeaders.Accept, "application/vnd.github+json")
                headers.append("X-GitHub-Api-Version", "2022-11-28")
                headers.append(HttpHeaders.UserAgent, USER_AGENT)
                headers.append(HttpHeaders.Authorization, authHeader(auth))
            }
        }
        return if (engine != null) HttpClient(engine, config) else HttpClient(CIO, config)
    }

    private fun authHeader(auth: AuthSource): String = when (auth) {
        is AuthSource.Pat -> "token ${auth.token}"
        is AuthSource.IdeAccount -> "token <pending-ide-account-credentials>"
    }
}
```

- [ ] **Step 4: Wire the callback in `ProductionClientFactory`**

Modify `src/main/kotlin/com/example/ghactions/repo/ProductionClientFactory.kt`. Pass `tracker::update` as the `onResponse` callback so production traffic feeds the project's tracker.

Replace the body of `create` (the line starting `val http = GitHubHttp.create(...)`):

```kotlin
val tracker = project.getService(com.example.ghactions.api.RateLimitTracker::class.java)
val http = GitHubHttp.create(
    baseUrl = binding.host,
    auth = patAsAuth,
    onResponse = tracker::update
)
return GitHubClient(http)
```

- [ ] **Step 5: Verify compile**

Run: `./gradlew --no-daemon compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run all existing tests to confirm no regression**

Run: `./gradlew --no-daemon test`
Expected: PASS — 107 tests (103 from Plan 5 + 4 from Task 1).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/RateLimitTracker.kt \
        src/main/kotlin/com/example/ghactions/api/GitHubHttp.kt \
        src/main/kotlin/com/example/ghactions/repo/ProductionClientFactory.kt
git commit -m "feat(api): track rate-limit headers via Ktor ResponseObserver"
```

---

## Task 3: `RateLimitedException` thrown by `GitHubClient`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`
- Test: `src/test/kotlin/com/example/ghactions/api/GitHubClientRateLimitTest.kt`

When the response is `429` or `403` with `X-RateLimit-Remaining: 0`, callers shouldn't see a generic `GitHubApiException`. They need to know it's the rate-limit case so they can back off (the poller) or display a specific message (the UI). The exception carries a `RateLimitInfo` so the poller can compute the backoff window.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/api/GitHubClientRateLimitTest.kt`:

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitHubClientRateLimitTest {

    private val repo = BoundRepo(
        host = "https://api.github.com",
        owner = "octocat",
        repo = "hello-world"
    )

    @Test
    fun `429 throws RateLimitedException carrying Retry-After`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = "{}",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(
                    "Retry-After" to listOf("90"),
                    "X-RateLimit-Remaining" to listOf("0"),
                    "X-RateLimit-Reset" to listOf("1714427200")
                )
            )
        }
        val http = GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "x"),
            engine = engine
        )
        val client = GitHubClient(http)

        val ex = assertFailsWith<RateLimitedException> { client.listRunsForRepo(repo) }
        assertEquals(429, ex.status)
        assertEquals(90, ex.info.retryAfterSeconds)
        assertEquals(0, ex.info.remaining)
        assertTrue(ex.info.isHardLimited)
    }

    @Test
    fun `403 with Remaining 0 throws RateLimitedException`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"message": "API rate limit exceeded"}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(
                    "X-RateLimit-Remaining" to listOf("0"),
                    "X-RateLimit-Reset" to listOf("1714427200")
                )
            )
        }
        val http = GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "x"),
            engine = engine
        )
        val client = GitHubClient(http)

        val ex = assertFailsWith<RateLimitedException> { client.listRunsForRepo(repo) }
        assertEquals(403, ex.status)
        assertNotNull(ex.info.resetEpochSeconds)
        assertTrue(ex.info.isHardLimited)
    }

    @Test
    fun `403 without Remaining 0 stays a plain GitHubApiException`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"message": "Resource not accessible"}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf("X-RateLimit-Remaining" to listOf("4999"))
            )
        }
        val http = GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "x"),
            engine = engine
        )
        val client = GitHubClient(http)

        val ex = assertFailsWith<GitHubApiException> { client.listRunsForRepo(repo) }
        assertEquals(403, ex.status)
        // We deliberately do NOT promote this to RateLimitedException —
        // it's a permission problem, not a rate-limit problem.
        assertTrue(ex !is RateLimitedException)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.api.GitHubClientRateLimitTest`
Expected: FAIL — `RateLimitedException` unresolved.

- [ ] **Step 3: Add `RateLimitedException` and the throw-site logic**

Modify `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`. At the bottom of the file (after `GitHubApiException`), add:

```kotlin
/**
 * Specialised [GitHubApiException] for the rate-limit cases — `429`, or `403` with
 * `X-RateLimit-Remaining: 0`. Callers (esp. `PollingCoordinator`) catch this to back
 * off until the reset window passes.
 */
class RateLimitedException(
    status: Int,
    message: String,
    val info: RateLimitInfo
) : GitHubApiException(status = status, message = message)
```

Then, in **every** `if (!response.status.isSuccess())` block in `GitHubClient` (there are five — `listRunsForRepo`, `listJobs`, `getJobLogs`, `getRunLogsArchive`, `listPullRequests`), replace the existing `throw GitHubApiException(...)` with a small helper. First, add this private helper inside the `GitHubClient` class:

```kotlin
private fun fail(response: io.ktor.client.statement.HttpResponse, label: String, body: String): Nothing {
    val info = RateLimitInfo.fromHeaders(response.headers)
    val status = response.status.value
    val isRateLimited = status == 429 || (status == 403 && info.remaining == 0)
    val message = "GET $label failed: ${body.take(200)}"
    if (isRateLimited) throw RateLimitedException(status = status, message = message, info = info)
    throw GitHubApiException(status = status, message = message)
}
```

Then update each error-handling block. For example, `listRunsForRepo`:

```kotlin
suspend fun listRunsForRepo(repo: BoundRepo, perPage: Int = 30): List<Run> = withContext(Dispatchers.IO) {
    val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/runs") {
        parameter("per_page", perPage)
    }
    if (!response.status.isSuccess()) fail(response, "runs", response.bodyAsText())
    response.body<ListRunsResponse>().workflowRuns.map { it.toDomain() }
}
```

Apply the same pattern to the other four methods. (Pass an apt `label` per call site: `"jobs"`, `"logs"`, `"run logs archive"`, `"pulls"`.)

The existing `GitHubApiException` class needs `open` so the new subclass can extend it. Change:

```kotlin
class GitHubApiException(
    val status: Int,
    message: String
) : RuntimeException(message)
```

to:

```kotlin
open class GitHubApiException(
    val status: Int,
    message: String
) : RuntimeException(message)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.api.GitHubClientRateLimitTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the full test suite to confirm no regression**

Run: `./gradlew --no-daemon test`
Expected: PASS — 110 tests (107 from end-of-Task-2 + 3 new).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/GitHubClient.kt \
        src/test/kotlin/com/example/ghactions/api/GitHubClientRateLimitTest.kt
git commit -m "feat(api): throw RateLimitedException on 429 and 403+remaining=0"
```

---

## Task 4: `BackoffPoller` (pure logic + virtual-time tests)

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/polling/BackoffPoller.kt`
- Test: `src/test/kotlin/com/example/ghactions/polling/BackoffPollerTest.kt`

The pure policy. No IDE deps, no `RunRepository` dependency — just data in, data out (`computeNextDelay`) plus a tiny driver loop that calls a suspend lambda once per tick. The driver is what virtual-time tests exercise.

- [ ] **Step 1: Confirm `kotlinx-coroutines-test` is available**

Run: `grep -n "kotlinx-coroutines-test" build.gradle.kts`
Expected: a `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")` line is already present (added in Plan 1's foundation setup). If, somehow, it isn't, add it next to the existing `kotlinx-coroutines-core` `compileOnly` line — the version **must** match `kotlinx-coroutines-core`'s version (currently `1.9.0`); mismatching versions cause `LinkageError` at test time. Otherwise, skip the edit.

- [ ] **Step 2: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/polling/BackoffPollerTest.kt`:

```kotlin
package com.example.ghactions.polling

import com.example.ghactions.api.RateLimitInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BackoffPollerTest {

    private fun snapshot(
        enabled: Boolean = true,
        visible: Boolean = true,
        anyActive: Boolean = false,
        rateLimit: RateLimitInfo = RateLimitInfo.NONE,
        nowEpochSeconds: Long = 0L
    ) = PollerSnapshot(
        livePollingEnabled = enabled,
        toolWindowVisible = visible,
        anyRunActive = anyActive,
        rateLimit = rateLimit,
        nowEpochSeconds = nowEpochSeconds
    )

    @Test
    fun `computeNextDelay returns ACTIVE interval when a run is in progress`() {
        val d = BackoffPoller.computeNextDelay(snapshot(anyActive = true))
        assertEquals(BackoffPoller.ACTIVE_INTERVAL, d)
    }

    @Test
    fun `computeNextDelay returns IDLE interval when no run is active`() {
        val d = BackoffPoller.computeNextDelay(snapshot(anyActive = false))
        assertEquals(BackoffPoller.IDLE_INTERVAL, d)
    }

    @Test
    fun `computeNextDelay returns PAUSE_INTERVAL when polling disabled or hidden`() {
        val disabled = BackoffPoller.computeNextDelay(snapshot(enabled = false))
        val hidden = BackoffPoller.computeNextDelay(snapshot(visible = false))
        assertEquals(BackoffPoller.PAUSE_INTERVAL, disabled)
        assertEquals(BackoffPoller.PAUSE_INTERVAL, hidden)
    }

    @Test
    fun `computeNextDelay waits until reset when hard rate-limited`() {
        val rl = RateLimitInfo(remaining = 0, resetEpochSeconds = 100L)
        val d = BackoffPoller.computeNextDelay(snapshot(rateLimit = rl, nowEpochSeconds = 90L))
        // 100 - 90 = 10s, plus a small jitter floor (we just check >= 10s and reasonable upper bound).
        assertTrue(d >= 10.seconds, "expected at least 10s, got $d")
        assertTrue(d <= 30.seconds, "expected at most 30s, got $d")
    }

    @Test
    fun `loop invokes tick on cadence and respects state changes`() = runTest {
        val ticks = mutableListOf<Long>()
        val state: MutableStateFlow<PollerSnapshot> = MutableStateFlow(snapshot(anyActive = true))
        val poller = BackoffPoller(state) {
            ticks += currentTime
        }

        val job = launch { poller.loop() }

        // First tick should fire immediately (or after the first delay, depending on impl).
        advanceTimeBy(BackoffPoller.ACTIVE_INTERVAL.inWholeMilliseconds + 50)
        // Now flip to IDLE.
        state.value = snapshot(anyActive = false)
        advanceTimeBy(BackoffPoller.IDLE_INTERVAL.inWholeMilliseconds + 50)
        // Now disable polling — no further ticks should occur.
        state.value = snapshot(enabled = false)
        val ticksBeforePause = ticks.size
        advanceTimeBy(BackoffPoller.IDLE_INTERVAL.inWholeMilliseconds * 5)
        assertEquals(ticksBeforePause, ticks.size, "no ticks should fire while paused")

        job.cancel()
    }
}
```

(Note: this test imports `kotlinx.coroutines.launch` from `runTest`'s scope — it's already in scope. Don't add an explicit import.)

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.polling.BackoffPollerTest`
Expected: FAIL — `BackoffPoller` and `PollerSnapshot` unresolved.

- [ ] **Step 4: Implement `BackoffPoller`**

Create `src/main/kotlin/com/example/ghactions/polling/BackoffPoller.kt`:

```kotlin
package com.example.ghactions.polling

import com.example.ghactions.api.RateLimitInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pure inputs to [BackoffPoller.computeNextDelay]. The poller never reads the IDE platform
 * directly — `PollingCoordinator` assembles a snapshot per tick and feeds it in.
 */
data class PollerSnapshot(
    val livePollingEnabled: Boolean,
    val toolWindowVisible: Boolean,
    val anyRunActive: Boolean,
    val rateLimit: RateLimitInfo,
    val nowEpochSeconds: Long
)

/**
 * Adaptive cadence policy + suspend driver loop.
 *
 * **Policy ([computeNextDelay]):**
 * - Polling disabled or tool window hidden → `PAUSE_INTERVAL` (the loop wakes periodically
 *   and re-checks state; this is *also* how the user's "live polling enabled" toggle is
 *   honoured live without restart).
 * - Hard rate-limited → wait until the reset timestamp + small jitter floor.
 * - Any run in `in_progress` / `queued` → `ACTIVE_INTERVAL` (5 s).
 * - Otherwise → `IDLE_INTERVAL` (60 s).
 *
 * **Driver ([loop]):** simple "delay then tick" — *not* "tick then delay" — so a paused
 * poller doesn't fire a stale request the moment the user toggles back on. The first
 * legitimate tick happens after the initial computed delay.
 */
class BackoffPoller(
    private val state: StateFlow<PollerSnapshot>,
    private val tick: suspend () -> Unit
) {
    suspend fun loop() {
        while (true) {
            val snap = state.value
            val delayMs = computeNextDelay(snap).inWholeMilliseconds
            delay(delayMs)
            val freshSnap = state.value
            if (shouldFire(freshSnap)) {
                tick()
            }
        }
    }

    companion object {
        val ACTIVE_INTERVAL: Duration = 5.seconds
        val IDLE_INTERVAL: Duration = 60.seconds
        val PAUSE_INTERVAL: Duration = 10.seconds
        private const val RATE_LIMIT_FLOOR_SECONDS = 5L
        private const val RATE_LIMIT_CEILING_SECONDS = 30L

        fun computeNextDelay(snap: PollerSnapshot): Duration {
            if (!snap.livePollingEnabled || !snap.toolWindowVisible) return PAUSE_INTERVAL
            if (snap.rateLimit.isHardLimited) return rateLimitWait(snap)
            return if (snap.anyRunActive) ACTIVE_INTERVAL else IDLE_INTERVAL
        }

        private fun shouldFire(snap: PollerSnapshot): Boolean {
            if (!snap.livePollingEnabled || !snap.toolWindowVisible) return false
            if (snap.rateLimit.isHardLimited) return false
            return true
        }

        private fun rateLimitWait(snap: PollerSnapshot): Duration {
            val retryAfter = snap.rateLimit.retryAfterSeconds
            val resetEpoch = snap.rateLimit.resetEpochSeconds
            val raw = when {
                retryAfter != null -> retryAfter.toLong()
                resetEpoch != null -> (resetEpoch - snap.nowEpochSeconds).coerceAtLeast(0)
                else -> RATE_LIMIT_FLOOR_SECONDS
            }
            val clamped = raw.coerceIn(RATE_LIMIT_FLOOR_SECONDS, RATE_LIMIT_CEILING_SECONDS)
            return clamped.seconds
        }
    }
}
```

Note: the test asserts `d <= 30.seconds`, matching `RATE_LIMIT_CEILING_SECONDS`. Don't bump that ceiling without updating the test.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.polling.BackoffPollerTest`
Expected: PASS, 5 tests.

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 115 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/polling/BackoffPoller.kt \
        src/test/kotlin/com/example/ghactions/polling/BackoffPollerTest.kt
git commit -m "feat(polling): add BackoffPoller with adaptive cadence policy"
```

(Step 1 should be a no-op against the existing `build.gradle.kts`. If you actually had to edit it, add it to the `git add`.)

---

## Task 5: `PollingCoordinator` project service

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/polling/PollingCoordinator.kt`

Wires the pure poller to the project: owns a coroutine scope, builds a `StateFlow<PollerSnapshot>` from `RateLimitTracker` + `RunRepository.runsState` + an injected visibility flag + `PluginSettings`, and on each tick fires `RunRepository.refreshRuns()` and `PullRequestRepository.refreshPullRequests(stateFilter)`.

The `stateFilter` problem: `PullRequestPanel` owns the filter state and calls `repository.refreshPullRequests(stateFilter)` on radio change. The coordinator needs to know which filter to use. Simplest answer: poll only `RunRepository` from the coordinator; the PR list refresh comes for free because `PullRequestRepository.refreshPullRequests` already fetches runs as a side-effect of computing per-PR latest run, and stale PR entries are tolerable for v1. Foreground refresh of PRs still happens whenever the user clicks the *Refresh* button or changes radios. **This is intentional v1 scope.** (A future plan can add a `currentStateFilter` getter on `PullRequestPanel` and have the coordinator call into it.)

- [ ] **Step 1: Create the file**

Create `src/main/kotlin/com/example/ghactions/polling/PollingCoordinator.kt`:

```kotlin
package com.example.ghactions.polling

import com.example.ghactions.api.RateLimitTracker
import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.repo.RunListState
import com.example.ghactions.repo.RunRepository
import com.example.ghactions.domain.RunStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Project-scoped poll loop. Started by [start] (called from `GhActionsToolWindowFactory`
 * on the first tool-window open) and stopped by [Disposable.dispose] (registered as a
 * project service, so the platform disposes it on project close).
 *
 * Visibility is fed in by [setToolWindowVisible] from `ToolWindowManagerListener`. The
 * coordinator never asks the IDE about visibility itself — keeps the loop deterministic
 * and IDE-test-friendly.
 */
@Service(Service.Level.PROJECT)
class PollingCoordinator(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PollingCoordinator::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val visibility = MutableStateFlow(false)
    private var loopJob: Job? = null

    fun start() {
        if (loopJob != null) return
        val runRepo = project.getService(RunRepository::class.java)
        val tracker = project.getService(RateLimitTracker::class.java)

        val snapshot: StateFlow<PollerSnapshot> = combineSnapshot(runRepo, tracker)
        loopJob = scope.launch {
            BackoffPoller(snapshot) {
                try {
                    runRepo.refreshRuns().join()
                } catch (t: Throwable) {
                    log.warn("Poll tick failed", t)
                }
            }.loop()
        }
    }

    fun setToolWindowVisible(visible: Boolean) {
        visibility.value = visible
    }

    private fun combineSnapshot(
        runRepo: RunRepository,
        tracker: RateLimitTracker
    ): StateFlow<PollerSnapshot> {
        val out = MutableStateFlow(initialSnapshot())
        // Recompute on any input change. Settings is read each combine — there's no
        // observable State for it on the platform, so we sample it per emission.
        combine(runRepo.runsState, tracker.state, visibility) { runs, rl, vis ->
            PollerSnapshot(
                livePollingEnabled = PluginSettings.getInstance().state.livePollingEnabled,
                toolWindowVisible = vis,
                anyRunActive = anyActive(runs),
                rateLimit = rl,
                nowEpochSeconds = System.currentTimeMillis() / 1000L
            )
        }.onEach { out.value = it }.launchIn(scope)
        return out
    }

    private fun initialSnapshot(): PollerSnapshot = PollerSnapshot(
        livePollingEnabled = PluginSettings.getInstance().state.livePollingEnabled,
        toolWindowVisible = false,
        anyRunActive = false,
        rateLimit = com.example.ghactions.api.RateLimitInfo.NONE,
        nowEpochSeconds = System.currentTimeMillis() / 1000L
    )

    private fun anyActive(runs: RunListState): Boolean = when (runs) {
        is RunListState.Loaded -> runs.runs.any {
            it.status == RunStatus.IN_PROGRESS || it.status == RunStatus.QUEUED
        }
        else -> false
    }

    override fun dispose() {
        scope.cancel()
        loopJob = null
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/polling/PollingCoordinator.kt
git commit -m "feat(polling): add PollingCoordinator project service"
```

---

## Task 6: Wire visibility tracking from `GhActionsToolWindowFactory`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt`

Start the coordinator on first content creation, register a `ToolWindowManagerListener` to feed visibility into it.

- [ ] **Step 1: Replace the file body to start the coordinator and observe visibility**

Modify `src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt`. Insert these lines at the end of `createToolWindowContent` (right before the closing `}`):

```kotlin
val coordinator = project.getService(com.example.ghactions.polling.PollingCoordinator::class.java)
coordinator.setToolWindowVisible(toolWindow.isVisible)
coordinator.start()

val listenerBus = project.messageBus.connect(toolWindow.disposable)
listenerBus.subscribe(
    com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
    object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
        override fun toolWindowShown(window: com.intellij.openapi.wm.ToolWindow) {
            if (window.id == ID) coordinator.setToolWindowVisible(true)
        }

        override fun stateChanged(manager: com.intellij.openapi.wm.ToolWindowManager) {
            // Hidden state is reported here, not via toolWindowShown — sample isVisible
            // for our window each transition.
            val tw = manager.getToolWindow(ID) ?: return
            coordinator.setToolWindowVisible(tw.isVisible)
        }
    }
)
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — still 115 tests.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt
git commit -m "feat(ui): start polling coordinator and feed tool-window visibility"
```

---

## Task 7: Manual smoke test (`./gradlew runIde`)

**Files:** none.

This plan has thorough automated coverage of the cadence policy and the rate-limit parser. The pieces that **only** the smoke test verifies are: tool-window visibility wiring, the settings checkbox flipping live polling, and the actual API ticks landing on a real GitHub host.

- [ ] **Step 1: Launch the dev IDE**

Run: `./gradlew --no-daemon runIde`
Expected: sandbox IDE opens.

- [ ] **Step 2: Open the GitHub Actions tool window**

Verify a refresh fires within ~5 s of opening (active runs cadence) or ~60 s (idle cadence). Watch `build/idea-sandbox/IC-2024.3/log/idea.log` for `RunRepository`/`PullRequestRepository` lines, or watch the tree refresh visually.

```bash
tail -f build/idea-sandbox/IC-2024.3/log/idea.log | grep -i "ghactions\|listRunsForRepo"
```

Expected: ticks at the cadence specified by `BackoffPoller.ACTIVE_INTERVAL` / `IDLE_INTERVAL`.

- [ ] **Step 3: Hide the tool window (collapse it)**

Expected: log lines for the next ~30 s show **no** new requests. (`PAUSE_INTERVAL` ticks fire but `shouldFire` returns false, so no API call.)

- [ ] **Step 4: Reopen the tool window**

Expected: ticks resume on cadence within ~10 s.

- [ ] **Step 5: Disable polling via Settings → Tools → GitHub Actions Monitor → "Live polling enabled"**

Toggle off, click *Apply*. Expected: log shows no further requests until you re-enable.

- [ ] **Step 6: Trigger an active run**

In the bound repo, push a commit so a workflow run starts. Within ~5 s of the next idle tick, the run should appear and the cadence should switch to `ACTIVE_INTERVAL`. The tree updates progressively as the run advances.

- [ ] **Step 7: Force a rate-limit (optional)**

Hard to reproduce on demand without a low-quota PAT. If you happen to hit `429` during the test, confirm the log shows a back-off (no API calls for ~5–30 s) and ticks resume after the window. If unable to test, document in the deviations file.

- [ ] **Step 8: Document any deviations**

If any step fails, write the deviation in `docs/superpowers/notes/2026-04-29-live-polling-smoke-deviations.md` and commit:

```bash
git add docs/superpowers/notes/
git commit -m "docs: smoke test deviations for plan 6"
```

If no deviations, skip the commit.

---

## Task 8: Final sweep + merge

**Files:** none — verification and merge only.

- [ ] **Step 1: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS — 115 tests (103 from Plan 5 + 4 + 3 + 5).

- [ ] **Step 2: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL. Updated zip at `build/distributions/`.

- [ ] **Step 3: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED against IDEA 2024.3 + 2025.1. Acceptable warnings: experimental-API uses (carry-over from Plan 4's `SegmentedButton`).

- [ ] **Step 4: Fast-forward merge to `main`**

```bash
git checkout main
git merge --ff-only feat/plan-6-live-polling
git log --oneline | head -10
```

Expected: clean fast-forward; the seven Plan 6 commits ride in.

- [ ] **Step 5: Plan-level verification**

- All 8 tasks have green check-marks.
- `./gradlew test` passes (~115 tests).
- `./gradlew buildPlugin` succeeds.
- `./gradlew verifyPlugin` succeeds.
- Manual smoke test (Task 7) all green.
- `git log --oneline` on `main` shows the expected sequence.

---

## What ships at the end of Plan 6

- The runs/PR tree auto-refreshes on an adaptive cadence: ~5 s while a run is active, ~60 s when idle.
- Polling pauses when the tool window is hidden and resumes when it's reopened.
- The "Live polling enabled" checkbox now actually does something — toggling it off stops the loop within `PAUSE_INTERVAL` (~10 s).
- The plugin honours GitHub's rate limits: a 429 or a 403-with-`Remaining: 0` response triggers a 5–30 s back-off (clamped) until the reset window passes.
- Manual *Refresh* still works exactly as before — it bypasses the cadence and fires immediately.

What it does **not** yet do (deferred to later plans):
- 4-tier polling (per-tab Foreground 3 s log tail, Tracked 15 s, Visible-list 30 s, Idle 60 s) — Plan 8.
- Tool-window banner / status-bar widget for the rate-limit state — Plan 9.
- ETag / `If-None-Match` conditional requests — future optimization plan.

---

## Open questions / risks

1. **Visibility events on minimised IDE.** `ToolWindowManagerListener.stateChanged` fires for many transitions; the smoke test confirms our condition (sampling `isVisible`) actually covers minimise vs. unfocus. If minimised-IDE traffic is observed during the smoke test, document it as a Plan 8 follow-up rather than blocking Plan 6.
2. **`combine(...).launchIn(scope)` lifetime.** When `dispose()` cancels `scope`, the combine flow stops emitting. Good. But the platform may dispose the project service mid-tick — that's harmless because `RunRepository` is also project-scoped and will swallow the cancellation in its own `try`/`catch`.
3. **Rate-limit ceiling of 30 s.** Real GitHub resets can be up to an hour. We deliberately clamp to 30 s so the loop wakes regularly — the next tick re-checks the live `RateLimitInfo`, and if the reset hasn't passed yet, it backs off again. This avoids a hung loop if the system clock skews.
4. **Settings change without restart.** `PluginSettings.getInstance().state.livePollingEnabled` is sampled every `combine` cycle (driven by run/rl/visibility flow updates). Settings changes that don't trigger any of those will land within `PAUSE_INTERVAL` (10 s) at worst, which matches the smoke-test expectation in Task 7 Step 5.
