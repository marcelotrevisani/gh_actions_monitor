# Plan 12 — Status bar widget

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface aggregate CI state for the active branch in the IDE status bar — a glance at the bottom of the window tells the user whether their latest build passed, failed, or is running. Click → focus the GitHub Actions tool window.

**Architecture:** A pure `StatusAggregator` reduces a list of runs (already cached in `RunRepository.runsState`) to a single `StatusSummary(state, tooltip)`. `GhActionsStatusBarWidget` (`StatusBarWidget` + `StatusBarWidget.IconPresentation`) reads the project's `RepoBinding.currentBranch`, filters runs to that branch, runs the aggregator, and re-renders on every emission. Registered via `StatusBarWidgetFactory` in `plugin.xml` so the IDE creates exactly one widget per project.

**Tech Stack:** Same as Plans 1–11 — Kotlin 2.0.21, IntelliJ Platform 2024.3, JUnit 5 + `kotlin.test.*`. No new gradle deps.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`):
- *IDE integrations → Status bar widget*.

**Sections deliberately deferred:**
- Right-click context menu (Refresh / Disable polling / Plugin settings) — out of scope; the spec's full menu is a separate concern.
- In-progress numeric badge overlay — YAGNI for v1; the icon alone communicates state.
- `RateLimitChanged` dimming — separate plan if/when needed.

**Plans 1–11 → Plan 12 carry-overs:**
- `RunRepository.runsState: StateFlow<RunListState>` from Plan 2 is the single source of truth.
- `RepoBinding.currentBranch: String?` from Plans 4/5 gives us the branch to filter by.
- `Run.headBranch: String` is the field we filter against.
- Notifications + polling already use a `Service.Level.PROJECT` + `Disposable` pattern; the widget follows the same shape.
- Commits one per task, type-prefixed; **no `Co-Authored-By` trailer**.

---

## File Structure

```
gh_actions_pycharm/
└── src/
    ├── main/
    │   ├── kotlin/com/example/ghactions/
    │   │   └── statusbar/
    │   │       ├── StatusAggregator.kt           (new — pure reducer)
    │   │       ├── GhActionsStatusBarWidget.kt   (new — StatusBarWidget impl)
    │   │       └── GhActionsStatusBarWidgetFactory.kt (new — factory)
    │   └── resources/META-INF/
    │       └── plugin.xml                        (modify — register factory)
    └── test/kotlin/com/example/ghactions/
        └── statusbar/
            └── StatusAggregatorTest.kt           (new)
```

**File responsibility notes:**
- `StatusAggregator` is pure: takes a list of runs (already filtered to the active branch's latest-per-workflow), returns a `StatusSummary`. Tested exhaustively.
- The widget is project-scoped wiring: subscribes to `RunRepository.runsState`, computes the summary on each emission, calls `WidgetPresentationFactory.update(...)` (or its equivalent — see Task 2 for the API).
- Click handler: `ToolWindowManager.getInstance(project).getToolWindow("GitHubActions")?.activate(null)`.
- The factory is the platform's required entry point; one per status-bar widget.

---

## Conventions

- Tests: JUnit 5, `kotlin.test.*`. Pure aggregator only.
- **Cumulative test target:** 147 (Plan 11) + 5 (aggregator tests) = ~152.
- Commits one per task, type-prefixed.

---

## Task 1: Pure `StatusAggregator`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/statusbar/StatusAggregator.kt`
- Create: `src/test/kotlin/com/example/ghactions/statusbar/StatusAggregatorTest.kt`

The reducer:
- Empty input → `IDLE` ("No runs for branch")
- Any run with status `IN_PROGRESS` or `QUEUED` → `RUNNING` (count in tooltip)
- All terminal, all `SUCCESS` → `SUCCESS`
- Any non-success terminal → `FAILURE`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/statusbar/StatusAggregatorTest.kt`:

```kotlin
package com.example.ghactions.statusbar

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusAggregatorTest {

    private fun run(
        id: Long,
        status: RunStatus,
        conclusion: RunConclusion? = null,
        workflowName: String = "CI"
    ): Run = Run(
        id = RunId(id), workflowName = workflowName,
        status = status, conclusion = conclusion,
        headBranch = "main", headSha = "sha-$id", event = "push",
        actorLogin = "octocat",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        htmlUrl = "https://example.com/run/$id", runNumber = id.toInt(),
        displayTitle = "title $id"
    )

    @Test
    fun `empty list yields IDLE`() {
        val s = StatusAggregator.summarize(emptyList())
        assertEquals(StatusState.IDLE, s.state)
        assertTrue(s.tooltip.contains("No runs", ignoreCase = true))
    }

    @Test
    fun `all success yields SUCCESS`() {
        val s = StatusAggregator.summarize(listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.SUCCESS),
            run(2, RunStatus.COMPLETED, RunConclusion.SUCCESS, workflowName = "Lint")
        ))
        assertEquals(StatusState.SUCCESS, s.state)
    }

    @Test
    fun `any failure yields FAILURE even if others succeeded`() {
        val s = StatusAggregator.summarize(listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.SUCCESS),
            run(2, RunStatus.COMPLETED, RunConclusion.FAILURE, workflowName = "Lint")
        ))
        assertEquals(StatusState.FAILURE, s.state)
    }

    @Test
    fun `any in-progress run yields RUNNING regardless of others`() {
        val s = StatusAggregator.summarize(listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.SUCCESS),
            run(2, RunStatus.IN_PROGRESS, workflowName = "Lint")
        ))
        assertEquals(StatusState.RUNNING, s.state)
        assertTrue(s.tooltip.contains("1 in progress", ignoreCase = true))
    }

    @Test
    fun `queued counts as running for the badge`() {
        val s = StatusAggregator.summarize(listOf(
            run(1, RunStatus.QUEUED)
        ))
        assertEquals(StatusState.RUNNING, s.state)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.statusbar.StatusAggregatorTest`
Expected: FAIL — `StatusAggregator`, `StatusState`, `StatusSummary` unresolved.

- [ ] **Step 3: Implement the aggregator**

Create `src/main/kotlin/com/example/ghactions/statusbar/StatusAggregator.kt`:

```kotlin
package com.example.ghactions.statusbar

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunStatus

/** Aggregate state shown by the status bar widget. */
enum class StatusState { IDLE, RUNNING, SUCCESS, FAILURE }

/** Composed result of [StatusAggregator.summarize]. */
data class StatusSummary(val state: StatusState, val tooltip: String)

/**
 * Pure reducer: turn a list of runs (already filtered to the active branch) into a
 * single [StatusSummary] for the status-bar widget.
 *
 * **Precedence**
 * - Empty → `IDLE`
 * - Any non-terminal (`IN_PROGRESS` or `QUEUED`) → `RUNNING`
 * - Otherwise: any non-`SUCCESS` conclusion → `FAILURE`; else `SUCCESS`.
 */
object StatusAggregator {

    fun summarize(runs: List<Run>): StatusSummary {
        if (runs.isEmpty()) return StatusSummary(StatusState.IDLE, "No runs for this branch")

        val inProgress = runs.count { it.status == RunStatus.IN_PROGRESS || it.status == RunStatus.QUEUED }
        if (inProgress > 0) {
            val total = runs.size
            return StatusSummary(
                StatusState.RUNNING,
                "$inProgress in progress · $total total"
            )
        }

        val anyFailure = runs.any { it.conclusion != RunConclusion.SUCCESS }
        return if (anyFailure) {
            StatusSummary(StatusState.FAILURE, "${runs.size} run(s); at least one failed")
        } else {
            StatusSummary(StatusState.SUCCESS, "${runs.size} run(s); all succeeded")
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.statusbar.StatusAggregatorTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 152 tests (147 + 5).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/statusbar/StatusAggregator.kt \
        src/test/kotlin/com/example/ghactions/statusbar/StatusAggregatorTest.kt
git commit -m "feat(statusbar): add StatusAggregator reducer"
```

---

## Task 2: `GhActionsStatusBarWidget` + factory

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/statusbar/GhActionsStatusBarWidget.kt`
- Create: `src/main/kotlin/com/example/ghactions/statusbar/GhActionsStatusBarWidgetFactory.kt`

The widget subscribes to `RunRepository.runsState`, filters to the current branch (via `RepoBinding.currentBranch`), runs the aggregator, and exposes the result as `StatusBarWidget.IconPresentation`. Click → activate the GitHub Actions tool window.

- [ ] **Step 1: Create the widget**

Create `src/main/kotlin/com/example/ghactions/statusbar/GhActionsStatusBarWidget.kt`:

```kotlin
package com.example.ghactions.statusbar

import com.example.ghactions.repo.RepoBinding
import com.example.ghactions.repo.RunListState
import com.example.ghactions.repo.RunRepository
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Status-bar icon summarising the active branch's CI state. Click → focuses the
 * GitHub Actions tool window. Updates on every emission of `RunRepository.runsState`.
 */
class GhActionsStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.IconPresentation {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusBar: StatusBar? = null
    private var observerJob: CJob? = null
    private var summary: StatusSummary = StatusSummary(StatusState.IDLE, "No runs yet")

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val runRepo = project.getService(RunRepository::class.java)
        observerJob = scope.launch {
            runRepo.runsState.collect { state -> recompute(state) }
        }
    }

    override fun dispose() {
        observerJob?.cancel()
        scope.cancel()
        statusBar = null
    }

    private fun recompute(state: RunListState) {
        val all = (state as? RunListState.Loaded)?.runs.orEmpty()
        val branch = project.getService(RepoBinding::class.java).currentBranch
        val matching = if (branch == null) emptyList() else all.filter { it.headBranch == branch }
        // Take the latest run per workflow so the aggregate doesn't double-count old retries.
        val latestPerWorkflow = matching
            .groupBy { it.workflowName }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.updatedAt } }
        summary = StatusAggregator.summarize(latestPerWorkflow)
        statusBar?.updateWidget(WIDGET_ID)
    }

    override fun getIcon(): Icon = when (summary.state) {
        StatusState.IDLE -> AllIcons.RunConfigurations.TestNotRan
        StatusState.RUNNING -> AllIcons.Actions.Play_forward
        StatusState.SUCCESS -> AllIcons.RunConfigurations.TestPassed
        StatusState.FAILURE -> AllIcons.RunConfigurations.TestFailed
    }

    override fun getTooltipText(): String = "GitHub Actions: ${summary.tooltip}"

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent> =
        com.intellij.util.Consumer {
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        }

    companion object {
        const val WIDGET_ID = "GhActionsStatusBarWidget"
        const val TOOL_WINDOW_ID = "GitHubActions"
    }
}
```

- [ ] **Step 2: Create the factory**

Create `src/main/kotlin/com/example/ghactions/statusbar/GhActionsStatusBarWidgetFactory.kt`:

```kotlin
package com.example.ghactions.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class GhActionsStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = GhActionsStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "GitHub Actions Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = GhActionsStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: com.intellij.openapi.wm.StatusBar): Boolean = true
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/statusbar/GhActionsStatusBarWidget.kt \
        src/main/kotlin/com/example/ghactions/statusbar/GhActionsStatusBarWidgetFactory.kt
git commit -m "feat(statusbar): add status-bar widget with click-to-focus"
```

---

## Task 3: Register factory in `plugin.xml`

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add the extension**

Inside the existing `<extensions defaultExtensionNs="com.intellij">`, add (after the `<notificationGroup …>` from Plan 11):

```xml
<statusBarWidgetFactory
    id="GhActionsStatusBarWidget"
    implementation="com.example.ghactions.statusbar.GhActionsStatusBarWidgetFactory"/>
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 152 tests, no regressions.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(statusbar): register status-bar widget factory"
```

---

## Task 4: Smoke test (deferred-okay) + final sweep + merge

**Files:** none.

- [ ] **Step 1: Manual smoke (deferred is fine)**

Run: `./gradlew --no-daemon runIde`

Verify:
- Bottom status bar shows a small GitHub Actions icon for the bound project. (May require enabling via *View → Appearance → Status Bar Widgets → GitHub Actions Status* depending on platform default.)
- Hover → tooltip reads "GitHub Actions: …" with the count summary.
- Trigger a refresh of runs (open the tool window, click *Refresh*, then close it). Tooltip + icon update on the next emission.
- Click the icon → GitHub Actions tool window comes to focus.
- Switch projects in the IDE; the widget's data follows the project (each project has its own widget instance).

If any step fails, write the deviation to `docs/superpowers/notes/2026-04-30-status-bar-widget-smoke-deviations.md` and commit:

```bash
git add docs/superpowers/notes/
git commit -m "docs: smoke test deviations for plan 12"
```

If no deviations, skip the commit.

- [ ] **Step 2: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS — 152 tests.

- [ ] **Step 3: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED. The existing 1 `SegmentedButton` warning is acceptable; `StatusBarWidget`/`StatusBarWidgetFactory` are stable APIs since 2020.

- [ ] **Step 5: Fast-forward merge**

```bash
git checkout main
git merge --ff-only feat/plan-12-status-bar
git log --oneline | head -10
```

- [ ] **Step 6: Plan-level verification**

- All 4 tasks have green check-marks (Step 1 may be deferred).
- `./gradlew test` passes (152).
- `./gradlew buildPlugin` and `verifyPlugin` succeed.

---

## What ships at the end of Plan 12

- A status-bar icon at the bottom of the IDE for any project bound to a GitHub repo.
- Icon reflects aggregate CI state of the latest run *per workflow* on the project's current branch:
  ✓ green (`TestPassed`) · ✗ red (`TestFailed`) · ▶ (`Play_forward`) for in-progress · ◯ (`TestNotRan`) for no data.
- Tooltip details the count (e.g. "2 in progress · 3 total" or "4 run(s); all succeeded").
- Click the icon to bring the GitHub Actions tool window to focus.
- Updates live on every `RunRepository.runsState` emission (i.e. each adaptive polling tick from Plan 6, plus manual *Refresh* clicks).

What it does **not** yet do:
- Right-click context menu (Refresh now / Disable polling / Plugin settings).
- Numeric in-progress count overlay.
- Rate-limit dimming.

---

## Open questions / risks

1. **Visibility default.** Some platform versions hide newly-registered status-bar widgets behind an opt-in toggle. The smoke test confirms the widget is visible by default; if not, document the *View → Appearance → Status Bar Widgets* path for users.
2. **`RepoBinding.currentBranch` synchronicity.** It reads from `GitRepositoryManager.repositories.firstOrNull()?.currentBranch?.name`, which is updated by the platform on a worker thread. Stale reads are acceptable: the next polling tick (≤60 s) will refresh.
3. **Workflow-level dedup.** `recompute` takes the latest run per workflow before aggregating, so a long-failed workflow doesn't poison the badge after a later workflow's success. This matches the PR-tree's per-workflow semantics from Plan 8 / `08e72f3`.
