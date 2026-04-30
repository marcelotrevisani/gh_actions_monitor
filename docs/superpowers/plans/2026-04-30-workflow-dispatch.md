# Plan 15 — workflow_dispatch (manual run trigger)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user start a workflow run from the IDE — pick a workflow from the bound repo, pick a ref, click *Run*. GitHub starts the run; the next polling tick shows it in the panel.

**Architecture:** Two new `GitHubClient` endpoints (`listWorkflows`, `dispatchWorkflow`); a `RunRepository.dispatchWorkflow(workflowId, ref): DispatchResult` wrapper that triggers `refreshRuns()` on success; a small `WorkflowPickerDialog` (`DialogWrapper`) in `ui/dispatch/` showing a `JComboBox<Workflow>` + ref `JBTextField` defaulting to `RepoBinding.currentBranch`; a *Run workflow…* toolbar action on `PullRequestPanel`.

**Tech Stack:** same as Plans 1–14. No new gradle deps.

**Spec reference:** *Components → Action classes → `RunWorkflowAction`*; *Triggering a workflow* in the spec.

**Sections deliberately deferred:** workflow `inputs` (parameterized dispatches), debug-logging payload option, dispatch from a tag instead of a branch (technically supported; same `ref` field, just no UI).

**Plans 1–14 → Plan 15 carry-overs:** `RepoBinding.currentBranch` for the default ref · existing `fail()` helper · `friendlyApiError` for error rendering · `DialogWrapper` is the standard modal-form base · commits one per task, no `Co-Authored-By`.

---

## File Structure

```
gh_actions_pycharm/
└── src/
    ├── main/kotlin/com/example/ghactions/
    │   ├── domain/Workflow.kt                 (new)
    │   ├── api/dto/WorkflowDto.kt             (new)
    │   ├── api/GitHubClient.kt                (modify — listWorkflows + dispatchWorkflow)
    │   ├── repo/RunRepository.kt              (modify — dispatchWorkflow + DispatchResult)
    │   └── ui/dispatch/
    │       └── WorkflowPickerDialog.kt        (new)
    └── test/kotlin/com/example/ghactions/
        └── api/
            └── GitHubClientWorkflowsTest.kt   (new — 4 MockEngine tests)
```

Plus minimal modifications:
- `ui/PullRequestPanel.kt` — toolbar action invoking the dialog.

**Cumulative test target:** 161 → 165.

---

## Task 1: Domain + DTO + GitHubClient endpoints

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/domain/Workflow.kt`
- Create: `src/main/kotlin/com/example/ghactions/api/dto/WorkflowDto.kt`
- Modify: `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`
- Create: `src/test/kotlin/com/example/ghactions/api/GitHubClientWorkflowsTest.kt`

GitHub endpoints:
- `GET /repos/{o}/{r}/actions/workflows` — `{ total_count, workflows: [...] }`.
- `POST /repos/{o}/{r}/actions/workflows/{id}/dispatches` with JSON body `{ "ref": "main" }` → 204 on success.

- [ ] **Step 1: Create `Workflow.kt`**

```kotlin
package com.example.ghactions.domain

@JvmInline
value class WorkflowId(val value: Long) {
    override fun toString(): String = value.toString()
}

/**
 * One workflow definition (a `.github/workflows/*.yml` file). [state] is GitHub's
 * lifecycle string ("active", "disabled_manually", etc.). v1 surfaces only "active"
 * workflows in the picker, but the DTO carries the full value for completeness.
 */
data class Workflow(
    val id: WorkflowId,
    val name: String,
    val path: String,
    val state: String
)
```

- [ ] **Step 2: Create `WorkflowDto.kt`**

```kotlin
package com.example.ghactions.api.dto

import com.example.ghactions.domain.Workflow
import com.example.ghactions.domain.WorkflowId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowDto(
    val id: Long,
    val name: String,
    val path: String,
    val state: String
) {
    fun toDomain(): Workflow = Workflow(
        id = WorkflowId(id),
        name = name,
        path = path,
        state = state
    )
}

@Serializable
data class ListWorkflowsResponse(
    @SerialName("total_count") val totalCount: Int,
    val workflows: List<WorkflowDto>
)

/** Request body for the dispatch endpoint. */
@Serializable
data class DispatchRequest(val ref: String)
```

- [ ] **Step 3: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/api/GitHubClientWorkflowsTest.kt`:

```kotlin
package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.WorkflowId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubClientWorkflowsTest {

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
    fun `listWorkflows maps DTO to domain`() = runTest {
        val body = """
            {
              "total_count": 2,
              "workflows": [
                {"id": 1, "name": "CI", "path": ".github/workflows/ci.yml", "state": "active"},
                {"id": 2, "name": "Deploy", "path": ".github/workflows/deploy.yml", "state": "disabled_manually"}
              ]
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(content = body, status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString())))
        }
        val workflows = client(engine).listWorkflows(repo)
        assertEquals(2, workflows.size)
        assertEquals(WorkflowId(1), workflows[0].id)
        assertEquals("CI", workflows[0].name)
        assertEquals("active", workflows[0].state)
    }

    @Test
    fun `listWorkflows surfaces 404 as GitHubApiException`() = runTest {
        val engine = MockEngine { _ -> respond(content = "{}", status = HttpStatusCode.NotFound) }
        val ex = assertFailsWith<GitHubApiException> { client(engine).listWorkflows(repo) }
        assertEquals(404, ex.status)
    }

    @Test
    fun `dispatchWorkflow POSTs ref body and accepts 204`() = runTest {
        var seenMethod: HttpMethod? = null
        var seenUrl: String? = null
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenMethod = request.method
            seenUrl = request.url.toString()
            seenBody = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)
                ?.bytes()?.decodeToString()
                ?: (request.body as? io.ktor.http.content.TextContent)?.text
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        client(engine).dispatchWorkflow(repo, WorkflowId(7), ref = "feat/login")
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("https://api.github.com/repos/o/r/actions/workflows/7/dispatches", seenUrl)
        // Body is JSON {"ref":"feat/login"}.
        assertTrue(seenBody?.contains("\"ref\":\"feat/login\"") == true, "body missing ref: $seenBody")
    }

    @Test
    fun `dispatchWorkflow maps 422 unprocessable to GitHubApiException`() = runTest {
        val engine = MockEngine { _ -> respond(content = "{}", status = HttpStatusCode.UnprocessableEntity) }
        val ex = assertFailsWith<GitHubApiException> {
            client(engine).dispatchWorkflow(repo, WorkflowId(7), "main")
        }
        assertEquals(422, ex.status)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

`./gradlew --no-daemon test --tests com.example.ghactions.api.GitHubClientWorkflowsTest` → FAIL (unresolved).

- [ ] **Step 5: Implement the two methods on `GitHubClient`**

In `src/main/kotlin/com/example/ghactions/api/GitHubClient.kt`, alongside the existing endpoints (before the private `fail` helper), add:

```kotlin
suspend fun listWorkflows(repo: BoundRepo): List<com.example.ghactions.domain.Workflow> = withContext(Dispatchers.IO) {
    val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/workflows")
    if (!response.status.isSuccess()) fail(response, "workflows")
    response.body<com.example.ghactions.api.dto.ListWorkflowsResponse>().workflows.map { it.toDomain() }
}

suspend fun dispatchWorkflow(
    repo: BoundRepo,
    workflowId: com.example.ghactions.domain.WorkflowId,
    ref: String
) = withContext(Dispatchers.IO) {
    val response = http.post("/repos/${repo.owner}/${repo.repo}/actions/workflows/${workflowId.value}/dispatches") {
        contentType(io.ktor.http.ContentType.Application.Json)
        setBody(com.example.ghactions.api.dto.DispatchRequest(ref = ref))
    }
    if (!response.status.isSuccess()) fail(response, "dispatch workflow")
}
```

You'll likely need these imports if missing: `io.ktor.client.request.setBody`, `io.ktor.http.contentType`. (`io.ktor.client.request.post` was added in Plan 13.)

- [ ] **Step 6: Run new tests to verify they pass**

`./gradlew --no-daemon test --tests com.example.ghactions.api.GitHubClientWorkflowsTest` → PASS, 4 tests.

- [ ] **Step 7: Run the full suite**

`./gradlew --no-daemon test` → PASS, 165 tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/domain/Workflow.kt \
        src/main/kotlin/com/example/ghactions/api/dto/WorkflowDto.kt \
        src/main/kotlin/com/example/ghactions/api/GitHubClient.kt \
        src/test/kotlin/com/example/ghactions/api/GitHubClientWorkflowsTest.kt
git commit -m "feat(api): add listWorkflows and dispatchWorkflow"
```

---

## Task 2: `RunRepository.dispatchWorkflow` wrapper

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/repo/RunRepository.kt`

A small suspend wrapper following the Plan 13 `runAction` shape. Returns the existing `ActionResult` (Success / Error) so the dialog reuses the same plumbing. Triggers `refreshRuns()` on success.

- [ ] **Step 1: Add the wrapper**

Inside `RunRepository`, alongside `cancelRun` / `rerunRun`:

```kotlin
suspend fun dispatchWorkflow(workflowId: com.example.ghactions.domain.WorkflowId, ref: String): ActionResult =
    runAction(com.example.ghactions.domain.RunId(0), "dispatchWorkflow") { client, repo ->
        client.dispatchWorkflow(repo, workflowId, ref)
    }

suspend fun listWorkflows(): List<com.example.ghactions.domain.Workflow> {
    val repo = boundRepo() ?: return emptyList()
    val client = clientFactory() ?: return emptyList()
    return try {
        client.listWorkflows(repo)
    } catch (e: Throwable) {
        log.warn("listWorkflows failed", e)
        emptyList()
    }
}
```

The first arg to `runAction` is unused inside the helper but kept since the signature requires it; pass a sentinel `RunId(0)`.

- [ ] **Step 2: Verify compile**

`./gradlew --no-daemon compileKotlin` → SUCCESS.

- [ ] **Step 3: Run the suite**

`./gradlew --no-daemon test` → PASS, 165 tests.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/repo/RunRepository.kt
git commit -m "feat(repo): add dispatchWorkflow + listWorkflows wrappers"
```

---

## Task 3: `WorkflowPickerDialog`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/dispatch/WorkflowPickerDialog.kt`

A `DialogWrapper` with a workflow combo + ref text field. Loads workflows in the background via the repository; disables the OK button until selection is valid.

- [ ] **Step 1: Create the file**

```kotlin
package com.example.ghactions.ui.dispatch

import com.example.ghactions.domain.Workflow
import com.example.ghactions.domain.WorkflowId
import com.example.ghactions.repo.RepoBinding
import com.example.ghactions.repo.RunRepository
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JComboBox

/**
 * Modal picker for workflow_dispatch. Caller awaits `showAndGet()`; on OK, query
 * [chosenWorkflowId] and [chosenRef] and pass to `RunRepository.dispatchWorkflow`.
 *
 * Loads workflows asynchronously after `init()`; the OK button is disabled until at
 * least one is picked. If the load returns nothing (no creds, network error), the
 * combo shows "(no active workflows)" and OK stays disabled.
 */
class WorkflowPickerDialog(private val project: Project) : DialogWrapper(project, true) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val combo = JComboBox<WorkflowChoice>(DefaultComboBoxModel<WorkflowChoice>())
    private val refField = JBTextField()
    private val statusLabel = JBLabel(" ")

    /** The workflow id selected when the user clicked OK, or null if the dialog was cancelled. */
    val chosenWorkflowId: WorkflowId?
        get() = (combo.selectedItem as? WorkflowChoice)?.id

    /** The ref string entered when the user clicked OK. */
    val chosenRef: String
        get() = refField.text.trim()

    init {
        title = "Run Workflow"
        refField.text = project.getService(RepoBinding::class.java).currentBranch ?: "main"
        init()
        scope.launch { loadWorkflows() }
    }

    override fun createCenterPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Workflow:", combo)
            .addLabeledComponent("Ref:", refField)
            .addComponent(statusLabel)
            .panel
        panel.border = JBUI.Borders.empty(8, 12)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val choice = combo.selectedItem as? WorkflowChoice
        if (choice == null || choice.id == null) {
            return ValidationInfo("Pick a workflow.", combo)
        }
        if (refField.text.isBlank()) return ValidationInfo("Ref is required.", refField)
        return null
    }

    private suspend fun loadWorkflows() {
        statusLabel.text = "Loading workflows…"
        val workflows = project.getService(RunRepository::class.java).listWorkflows()
            .filter { it.state == "active" }
        ApplicationManager.getApplication().invokeLater {
            renderChoices(workflows)
        }
    }

    private fun renderChoices(workflows: List<Workflow>) {
        val model = combo.model as DefaultComboBoxModel<WorkflowChoice>
        model.removeAllElements()
        if (workflows.isEmpty()) {
            model.addElement(WorkflowChoice(null, "(no active workflows)"))
            statusLabel.text = "No active workflows in this repo."
        } else {
            workflows.forEach { model.addElement(WorkflowChoice(it.id, "${it.name} (${it.path})")) }
            statusLabel.text = " "
        }
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }

    private data class WorkflowChoice(val id: WorkflowId?, val label: String) {
        override fun toString(): String = label
    }
}
```

- [ ] **Step 2: Verify compile**

`./gradlew --no-daemon compileKotlin` → SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/dispatch/WorkflowPickerDialog.kt
git commit -m "feat(ui): add WorkflowPickerDialog (workflow + ref selector)"
```

---

## Task 4: Toolbar action in `PullRequestPanel`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/PullRequestPanel.kt`

Add a *Run workflow…* toolbar action. On click: open the dialog; on OK: call `repository.dispatchWorkflow(...)` (`PullRequestPanel` doesn't have `RunRepository`, fetch via `project.getService(...)`); show success/error message; on success, refresh runs.

Note: `PullRequestPanel` accesses `RunRepository` via `project.getService(...)` since the existing repository field is the *PullRequestRepository*. The action handler scope is the panel's existing `scope`.

- [ ] **Step 1: Read the existing toolbar**

Find `buildToolbar()` in `PullRequestPanel`. It currently has only the *Refresh* action.

- [ ] **Step 2: Add the *Run workflow…* action and a helper**

Inside `PullRequestPanel`, add a private method that opens the dialog and handles the result:

```kotlin
private fun runWorkflow() {
    val dialog = com.example.ghactions.ui.dispatch.WorkflowPickerDialog(project)
    if (!dialog.showAndGet()) return
    val workflowId = dialog.chosenWorkflowId ?: return
    val ref = dialog.chosenRef
    val runRepo = project.getService(com.example.ghactions.repo.RunRepository::class.java)
    scope.launch {
        val result = runRepo.dispatchWorkflow(workflowId, ref)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            when (result) {
                is com.example.ghactions.repo.ActionResult.Success ->
                    com.intellij.openapi.ui.Messages.showInfoMessage(
                        project, "Workflow dispatched on $ref.", "GitHub Actions"
                    )
                is com.example.ghactions.repo.ActionResult.Error ->
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project,
                        com.example.ghactions.repo.friendlyApiError(result.httpStatus, result.message),
                        "GitHub Actions"
                    )
            }
        }
    }
}
```

Note: `PullRequestPanel` already has a `project` field if it stores the constructor parameter; if not, the constructor signature `class PullRequestPanel(project: Project, ...)` will need `project` promoted to `private val project: Project`. Check the current declaration and adjust.

In `buildToolbar()`, append a second action to the group (after the Refresh action's `add(...)`):

```kotlin
add(object : AnAction("Run Workflow…", "Manually dispatch a workflow on a chosen ref",
    AllIcons.Actions.Execute) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) { runWorkflow() }
})
```

- [ ] **Step 3: Verify compile**

`./gradlew --no-daemon compileKotlin` → SUCCESS.

- [ ] **Step 4: Run the suite**

`./gradlew --no-daemon test` → PASS, 165 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/PullRequestPanel.kt
git commit -m "feat(ui): add Run workflow… toolbar action to pull request panel"
```

---

## Task 5: Smoke + final sweep + merge

- [ ] **Step 1: Manual smoke (deferred OK)**

Run `./gradlew --no-daemon runIde`. Verify:
- *Run workflow…* toolbar button appears next to *Refresh* in the upper PR-list panel.
- Click → dialog opens. Workflows populate after ~1 s; ref pre-fills with current branch.
- Pick one, click OK → success info dialog.
- Within ~5 s the new run shows up in the PR tree (under the PR matching the ref, if any) or in the run list.
- Cancel the dialog → no-op.
- Try with a disabled-only repo → "(no active workflows)" with OK greyed.

- [ ] **Step 2: Full test run** — `./gradlew --no-daemon cleanTest test` → 165 tests.
- [ ] **Step 3: Build distribution** — `./gradlew --no-daemon buildPlugin` → SUCCESS.
- [ ] **Step 4: Plugin verifier** — `./gradlew --no-daemon verifyPlugin` → SUCCESS (existing 1 SegmentedButton warning acceptable).
- [ ] **Step 5: Fast-forward merge to main**

```bash
git checkout main
git merge --ff-only feat/plan-15-workflow-dispatch
```

- [ ] **Step 6: Plan-level verification** — all 5 tasks ✅.

---

## What ships at the end of Plan 15

- Manual workflow dispatch from the IDE: pick workflow + ref, fire.
- Defaults the ref to the project's current branch when known.
- Result toast on success/failure; on success the next polling tick (or manual *Refresh*) shows the new run.

What it does **not** yet do:
- Workflow `inputs` (parameterized dispatches).
- Debug-logging mode.
- Tag picker (the user can type a tag in the ref field — works server-side; just no UI affordance).

---

## Open questions / risks

1. **422 from `dispatchWorkflow`.** GitHub returns 422 if the workflow doesn't have a `workflow_dispatch` trigger. The `friendlyApiError` for 422 isn't customised — we'd see the raw body. Acceptable for v1; users will recognise the message.
2. **Workflow file order.** GitHub returns workflows in undefined order. We display them as listed. If users want sorting, alphabetical-by-name is a future polish.
3. **No persistent state.** The dialog is fresh each open: workflow choice and ref aren't remembered across opens. For occasional dispatch this is fine; if used heavily, persist last-used per workflow in `PluginSettings`.
