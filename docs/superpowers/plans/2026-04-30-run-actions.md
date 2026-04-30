# Plan 13 — Run actions: Re-run / Cancel

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user re-run or cancel a workflow run from inside the IDE — no need to switch to the browser. Three buttons on the run-detail tree toolbar: *Cancel run*, *Re-run all jobs*, *Re-run failed jobs*. Action enablement reflects the current run's status; cancel asks for confirmation.

**Architecture:** Three new POST endpoints on `GitHubClient`, each wrapped by a corresponding `RunRepository` method that invalidates the repository's cached run list on success so the next polling tick (or manual refresh) picks up the new state. The UI exposes three `AnAction`s on the existing tree toolbar in `RunDetailPanel`. After firing, the panel calls `repository.refreshRuns()` so the user sees feedback within ~5 s instead of waiting for the next adaptive tick.

**Tech Stack:** Same as Plans 1–12 — Kotlin 2.0.21, Ktor 2.3.13, IntelliJ Platform 2024.3, JUnit 5 + MockK + Ktor MockEngine. No new gradle deps.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`):
- *Components → Action classes → `RunWorkflowAction`, `RerunAllAction`, `RerunFailedAction`, `CancelRunAction`* — this plan covers the three run-level actions; `RunWorkflowAction` (workflow_dispatch) is deferred.

**Sections deliberately deferred:**
- `RunWorkflowAction` (`POST /actions/workflows/{id}/dispatches`) — needs a workflow picker UI, separate plan.
- Per-job re-run (`POST /actions/jobs/{id}/rerun`) — partial-rerun granularity is uncommon; defer until requested.
- Run with debug logging (`enable_debug_logging: true` payload option) — defer.
- Confirmation dialog for re-run (cheap action, just creates a new run) — only confirm cancel.

**Plans 1–12 → Plan 13 carry-overs:**
- The existing `fail(response, label)` helper in `GitHubClient` already promotes `429` and `403+remaining=0` to `RateLimitedException`; new endpoints reuse it.
- `friendlyApiError` already covers 401/403/404/429 from the user's perspective; success/other-error rendering follows the same pattern.
- `RunRepository.refreshRuns()` returns a `kotlinx.coroutines.Job`; the UI doesn't need to await it.
- Commits one per task, type-prefixed; **no `Co-Authored-By` trailer**.

---

## File Structure

```
gh_actions_pycharm/
└── src/
    ├── main/kotlin/com/example/ghactions/
    │   ├── api/
    │   │   └── GitHubClient.kt              (modify — 3 new POST methods)
    │   ├── repo/
    │   │   └── RunRepository.kt             (modify — 3 wrapper methods returning ActionResult)
    │   └── ui/
    │       └── RunDetailPanel.kt            (modify — 3 actions on the tree toolbar)
    └── test/kotlin/com/example/ghactions/
        ├── api/
        │   └── GitHubClientRunActionsTest.kt    (new — 4 MockEngine tests)
        └── repo/
            └── RunRepositoryRunActionsTest.kt   (new — 2 wrapper tests)
```

**File responsibility notes:**
- `GitHubClient` gets three trivial `suspend fun`s. They use HTTP POST with empty bodies; GitHub returns `204 No Content` on success.
- `RunRepository` wraps each call: returns a sealed `ActionResult { Success ; Error(httpStatus, message) }` so the UI can show a friendly toast either way. On success the repository fires `refreshRuns()` so the UI updates within the active polling cadence.
- `RunDetailPanel` adds three actions to the existing `buildTreeToolbar()` group (Open-in-Window / Expand / Collapse). Each action's `update()` reads the current run from `rootNode.userObject` and sets enabled-state. The action body launches a coroutine on the panel's existing `scope` and shows `Messages.showInfoMessage` / `showErrorDialog` when done.

---

## Conventions

- Tests: JUnit 5, `kotlin.test.*`, MockK + Ktor `MockEngine`.
- **Cumulative test target:** 152 (Plan 12) + 4 (`GitHubClient` POSTs) + 2 (repository wrapper) = ~158.
- Commits one per task, type-prefixed.

---

## Task 1: `GitHubClient.cancelRun`, `rerunRun`, `rerunFailedJobs`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`
- Create: `src/test/kotlin/com/example/ghactions/api/GitHubClientRunActionsTest.kt`

GitHub endpoints (all return `204 No Content` on success):
- `POST /repos/{o}/{r}/actions/runs/{id}/cancel`
- `POST /repos/{o}/{r}/actions/runs/{id}/rerun`
- `POST /repos/{o}/{r}/actions/runs/{id}/rerun-failed-jobs`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/api/GitHubClientRunActionsTest.kt`:

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubClientRunActionsTest {

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
    fun `cancelRun POSTs to the cancel endpoint and accepts 204`() = runTest {
        var seenMethod: HttpMethod? = null
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenMethod = request.method
            seenUrl = request.url.toString()
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        client(engine).cancelRun(repo, RunId(99))
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("https://api.github.com/repos/o/r/actions/runs/99/cancel", seenUrl)
    }

    @Test
    fun `rerunRun POSTs to the rerun endpoint and accepts 201`() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond(content = "", status = HttpStatusCode.Created)
        }
        client(engine).rerunRun(repo, RunId(99))
        assertEquals("https://api.github.com/repos/o/r/actions/runs/99/rerun", seenUrl)
    }

    @Test
    fun `rerunFailedJobs POSTs to the rerun-failed-jobs endpoint`() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond(content = "", status = HttpStatusCode.Created)
        }
        client(engine).rerunFailedJobs(repo, RunId(99))
        assertEquals("https://api.github.com/repos/o/r/actions/runs/99/rerun-failed-jobs", seenUrl)
    }

    @Test
    fun `cancelRun maps 409 conflict (already terminal) to GitHubApiException`() = runTest {
        val engine = MockEngine { _ -> respond(content = "{}", status = HttpStatusCode.Conflict) }
        val ex = assertFailsWith<GitHubApiException> { client(engine).cancelRun(repo, RunId(99)) }
        assertEquals(409, ex.status)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.api.GitHubClientRunActionsTest`
Expected: FAIL — `cancelRun` / `rerunRun` / `rerunFailedJobs` unresolved.

- [ ] **Step 3: Implement the three methods**

Inside the `GitHubClient` class (alongside the other suspend fun methods, before the private `fail(...)` helper), add:

```kotlin
/**
 * `POST /repos/{owner}/{repo}/actions/runs/{run_id}/cancel`. GitHub returns 202 / 204
 * on success; 409 if the run is already terminal (cannot cancel).
 */
suspend fun cancelRun(
    repo: BoundRepo,
    runId: com.example.ghactions.domain.RunId
) = withContext(Dispatchers.IO) {
    val response = http.post("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/cancel")
    if (!response.status.isSuccess()) fail(response, "cancel run")
}

/**
 * `POST /repos/{owner}/{repo}/actions/runs/{run_id}/rerun`. Re-runs every job in the run.
 * GitHub returns 201 on success; 403 if the run is too old or already running.
 */
suspend fun rerunRun(
    repo: BoundRepo,
    runId: com.example.ghactions.domain.RunId
) = withContext(Dispatchers.IO) {
    val response = http.post("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/rerun")
    if (!response.status.isSuccess()) fail(response, "rerun run")
}

/**
 * `POST /repos/{owner}/{repo}/actions/runs/{run_id}/rerun-failed-jobs`. Re-runs only the
 * jobs whose conclusion was non-success. Same status-code pattern as [rerunRun].
 */
suspend fun rerunFailedJobs(
    repo: BoundRepo,
    runId: com.example.ghactions.domain.RunId
) = withContext(Dispatchers.IO) {
    val response = http.post("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/rerun-failed-jobs")
    if (!response.status.isSuccess()) fail(response, "rerun failed jobs")
}
```

You'll also need this import at the top of the file (if missing):

```kotlin
import io.ktor.client.request.post
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.api.GitHubClientRunActionsTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 156 tests (152 + 4).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/api/GitHubClient.kt \
        src/test/kotlin/com/example/ghactions/api/GitHubClientRunActionsTest.kt
git commit -m "feat(api): add cancelRun, rerunRun, rerunFailedJobs"
```

---

## Task 2: `RunRepository` wrapper methods returning `ActionResult`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`
- Create: `src/test/kotlin/com/example/ghactions/repo/RunRepositoryRunActionsTest.kt`

The wrappers translate `GitHubApiException` into a sealed `ActionResult` that the UI can render with `friendlyApiError`. Each successful action calls `refreshRuns()` so the user sees the updated state at the next polling tick (and on the immediate manual refresh).

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/repo/RunRepositoryRunActionsTest.kt`:

```kotlin
package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunRepositoryRunActionsTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "o", repo = "r")
    private val runId = RunId(99)

    private fun newRepo(client: GitHubClient): RunRepository = RunRepository(
        boundRepo = { repo },
        clientFactory = { client },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    @Test
    fun `cancelRun returns Success and triggers a runs refresh`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.cancelRun(repo, runId) } just Runs
        val repository = newRepo(client)

        val result = repository.cancelRun(runId)

        assertTrue(result is ActionResult.Success)
        // refreshRuns hits listRunsForRepo via clientFactory — the relaxed mock returns
        // an empty list, but we just want to confirm we asked.
        coVerify { client.listRunsForRepo(repo, any(), any()) }
    }

    @Test
    fun `rerunRun maps GitHubApiException to ActionResult Error`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.rerunRun(repo, runId) } throws GitHubApiException(403, "Forbidden")
        val repository = newRepo(client)

        val result = repository.rerunRun(runId)

        assertTrue(result is ActionResult.Error)
        assertEquals(403, (result as ActionResult.Error).httpStatus)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.repo.RunRepositoryRunActionsTest`
Expected: FAIL — `ActionResult`, `cancelRun`, `rerunRun` unresolved.

- [ ] **Step 3: Add `ActionResult` and the three wrappers**

Modify `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`.

After the existing `DownloadResult` sealed class, add:

```kotlin
/** Outcome of a write-side workflow run action (cancel / rerun). */
sealed class ActionResult {
    data object Success : ActionResult()
    data class Error(val httpStatus: Int?, val message: String) : ActionResult()
}
```

Inside the `RunRepository` class (alongside the existing `refreshRuns` etc.), add three suspend wrappers:

```kotlin
suspend fun cancelRun(runId: com.example.ghactions.domain.RunId): ActionResult =
    runAction(runId, "cancelRun") { client, repo -> client.cancelRun(repo, runId) }

suspend fun rerunRun(runId: com.example.ghactions.domain.RunId): ActionResult =
    runAction(runId, "rerunRun") { client, repo -> client.rerunRun(repo, runId) }

suspend fun rerunFailedJobs(runId: com.example.ghactions.domain.RunId): ActionResult =
    runAction(runId, "rerunFailedJobs") { client, repo -> client.rerunFailedJobs(repo, runId) }

private suspend fun runAction(
    runId: com.example.ghactions.domain.RunId,
    label: String,
    block: suspend (com.example.ghactions.api.GitHubClient, com.example.ghactions.events.BoundRepo) -> Unit
): ActionResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val repo = boundRepo() ?: return@withContext ActionResult.Error(null, "No bound repo")
    val client = clientFactory() ?: return@withContext ActionResult.Error(
        null, "No credentials available for ${repo.host}"
    )
    try {
        block(client, repo)
        // Trigger an out-of-band refresh so the UI catches up faster than the next
        // polling tick. Fire-and-forget — the action's result is independent of refresh.
        refreshRuns()
        ActionResult.Success
    } catch (e: com.example.ghactions.api.GitHubApiException) {
        log.warn("$label failed: status=${e.status}", e)
        ActionResult.Error(e.status, e.message ?: "API error")
    } catch (e: Throwable) {
        log.warn("$label threw unexpectedly", e)
        ActionResult.Error(null, e.message ?: e::class.java.simpleName)
    }
}
```

- [ ] **Step 4: Run the new tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.repo.RunRepositoryRunActionsTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 158 tests (156 + 2).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/repo/RunRepository.kt \
        src/test/kotlin/com/example/ghactions/repo/RunRepositoryRunActionsTest.kt
git commit -m "feat(repo): add cancelRun, rerunRun, rerunFailedJobs wrappers"
```

---

## Task 3: Toolbar actions in `RunDetailPanel`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt`

Add three actions to the existing `buildTreeToolbar()` group, after Open-in-New-Window / Expand-All / Collapse-All. Each reads the *current run* (`rootNode.userObject as? Run`) for enablement and target.

- [ ] **Step 1: Add the three actions**

Find the existing `buildTreeToolbar()` method. After the *Collapse All* action declaration (and before the closing `apply { … }`), insert three more `add(...)` calls:

```kotlin
addSeparator()
add(object : com.intellij.openapi.actionSystem.AnAction(
    "Cancel Run",
    "Cancel the currently running workflow",
    com.intellij.icons.AllIcons.Actions.Suspend
) {
    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val run = rootNode.userObject as? com.example.ghactions.domain.Run
        e.presentation.isEnabled = run != null
            && (run.status == com.example.ghactions.domain.RunStatus.IN_PROGRESS
                || run.status == com.example.ghactions.domain.RunStatus.QUEUED)
    }
    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val run = rootNode.userObject as? com.example.ghactions.domain.Run ?: return
        val ok = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            "Cancel run #${run.runNumber} (${run.workflowName})?",
            "Cancel run",
            com.intellij.openapi.ui.Messages.getQuestionIcon()
        )
        if (ok != com.intellij.openapi.ui.Messages.YES) return
        scope.launch {
            val result = repository.cancelRun(run.id)
            handleResult(result, success = "Cancellation requested.")
        }
    }
})
add(object : com.intellij.openapi.actionSystem.AnAction(
    "Re-run All Jobs",
    "Re-run every job in this workflow run",
    com.intellij.icons.AllIcons.Actions.Restart
) {
    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val run = rootNode.userObject as? com.example.ghactions.domain.Run
        e.presentation.isEnabled = run != null
            && run.status == com.example.ghactions.domain.RunStatus.COMPLETED
    }
    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val run = rootNode.userObject as? com.example.ghactions.domain.Run ?: return
        scope.launch {
            val result = repository.rerunRun(run.id)
            handleResult(result, success = "Re-run requested.")
        }
    }
})
add(object : com.intellij.openapi.actionSystem.AnAction(
    "Re-run Failed Jobs",
    "Re-run only the jobs whose conclusion wasn't success",
    com.intellij.icons.AllIcons.Actions.RerunFailedTests
) {
    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val run = rootNode.userObject as? com.example.ghactions.domain.Run
        e.presentation.isEnabled = run != null
            && run.status == com.example.ghactions.domain.RunStatus.COMPLETED
            && run.conclusion != null
            && run.conclusion != com.example.ghactions.domain.RunConclusion.SUCCESS
    }
    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val run = rootNode.userObject as? com.example.ghactions.domain.Run ?: return
        scope.launch {
            val result = repository.rerunFailedJobs(run.id)
            handleResult(result, success = "Re-run of failed jobs requested.")
        }
    }
})
```

### Step 2: Add the `handleResult` helper

Inside the `RunDetailPanel` class (anywhere alongside the other private helpers), add:

```kotlin
private fun handleResult(result: com.example.ghactions.repo.ActionResult, success: String) {
    val app = com.intellij.openapi.application.ApplicationManager.getApplication()
    when (result) {
        is com.example.ghactions.repo.ActionResult.Success -> app.invokeLater {
            com.intellij.openapi.ui.Messages.showInfoMessage(project, success, "GitHub Actions")
        }
        is com.example.ghactions.repo.ActionResult.Error -> app.invokeLater {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                com.example.ghactions.repo.friendlyApiError(result.httpStatus, result.message),
                "GitHub Actions"
            )
        }
    }
}
```

### Step 3: Verify compile

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

### Step 4: Run the full test suite

Run: `./gradlew --no-daemon test`
Expected: PASS — 158 tests, no regressions.

### Step 5: Commit

```bash
git add src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt
git commit -m "feat(ui): add Cancel / Re-run / Re-run failed actions to run detail toolbar"
```

---

## Task 4: Smoke test (deferred-okay) + final sweep + merge

**Files:** none.

- [ ] **Step 1: Manual smoke (deferred is fine)**

Run: `./gradlew --no-daemon runIde`

Verify:
- Open a run that's in progress. The tree toolbar's *Cancel Run* button is enabled. Click it → confirmation dialog. Confirm → success toast → next polling tick reflects the cancellation.
- Open a completed-with-failure run. *Re-run Failed Jobs* is enabled. Click → success toast → new run appears in the list within ~5 s (polling cadence).
- Open a completed-with-success run. *Re-run Failed Jobs* is disabled (no failures to re-run). *Re-run All Jobs* is enabled. Click → success toast → new run starts.
- Open an in-progress run and click *Re-run All Jobs* — disabled (action only on completed runs).
- Trigger an action that returns an error (e.g. cancel an already-completed run). The error dialog shows the friendly text from `friendlyApiError`.

If any step fails, document in `docs/superpowers/notes/2026-04-30-run-actions-smoke-deviations.md` and commit:

```bash
git add docs/superpowers/notes/
git commit -m "docs: smoke test deviations for plan 13"
```

If no deviations, skip the commit.

- [ ] **Step 2: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS — 158 tests.

- [ ] **Step 3: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED. Existing 1 `SegmentedButton` warning is acceptable; no new warnings expected.

- [ ] **Step 5: Fast-forward merge**

```bash
git checkout main
git merge --ff-only feat/plan-13-run-actions
git log --oneline | head -10
```

- [ ] **Step 6: Plan-level verification**

- All 4 tasks have green check-marks (Step 1 may be deferred).
- `./gradlew test` passes (158).
- `./gradlew buildPlugin` and `verifyPlugin` succeed.

---

## What ships at the end of Plan 13

- Three new buttons on the run-detail tree toolbar:
  - *Cancel Run* — when the run is in progress / queued. Confirmation dialog before firing.
  - *Re-run All Jobs* — when the run is completed.
  - *Re-run Failed Jobs* — when the run completed with a non-success conclusion.
- Each action confirms outcome via an info or error dialog. Errors are routed through the existing `friendlyApiError` so 401/403/404/429 messages are user-actionable.
- The repository invalidates its cached run list on success and triggers `refreshRuns()`, so the next polling tick (or manual *Refresh*) shows the new state without the user needing to switch to the browser.

What it does **not** yet do:
- Trigger a workflow from scratch (`workflow_dispatch`) — needs a workflow picker.
- Per-job re-run — too granular for v1.
- Re-run with debug logging.

---

## Open questions / risks

1. **403 on re-run for old runs.** GitHub disallows re-running runs older than ~30 days. The error dialog surfaces the API message; the user can read "this run is too old to be re-run" and act.
2. **Race with polling.** The user clicks *Cancel*, polling fires `refreshRuns` ~1 s later, the action returns ~1 s after that and fires *another* `refreshRuns`. Two consecutive refreshes are cheap — the rate-limit budget can comfortably absorb it.
3. **Confirmation only for cancel.** Re-run is reversible (the user can cancel the new run). Cancel is "softly destructive" — losing in-progress work is annoying but not catastrophic. Single confirmation prompt is enough; no per-action toggle in settings.
