# Plan 11 — Notifications on run completion

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fire an IDE balloon notification when a run completes (transitions from non-terminal to terminal). Honor the existing `PluginSettings.notificationLevel` setting (`OFF` / `FAILURES_ONLY` / `ALL`). Click → open the run on github.com.

**Architecture:** A pure `NotificationDecider` computes which notifications should fire given a previous status snapshot, the current run list, and the user's setting. A project-scoped `NotificationCenter` service subscribes to `RunRepository.runsState`, maintains the previous-status map, asks the decider for events, and fires them via IntelliJ's `NotificationGroup`. `plugin.xml` declares the notification group so users can mute/customise it through *Settings → Appearance & Behavior → Notifications*. Started by `GhActionsToolWindowFactory` next to `PollingCoordinator`. Notifications carry a *View on GitHub* action that opens `Run.htmlUrl`.

**Tech Stack:** Same as Plans 1–10 — Kotlin 2.0.21, IntelliJ Platform 2024.3, JUnit 5 + `kotlin.test.*`. No new gradle deps.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`):
- *Components → `Notifier`* — implemented here under the name `NotificationCenter`.
- *Settings → Notification level* — already exposed in `PluginSettings`; this plan makes it functional.

**Sections deliberately deferred:**
- Status-bar widget (`StatusBarWidget.IconPresentation`) showing aggregate CI state — separate plan.
- Notification actions besides *View on GitHub* (Re-run failed / Cancel) — separate plan; depends on write-side endpoints.
- Sound / blink / sticky-balloon escalation — out of scope.
- Per-PR mute toggles — YAGNI for v1.

**Plans 1–10 → Plan 11 carry-overs:**
- `RunRepository.runsState: StateFlow<RunListState>` from Plan 2 is the single source of truth for run lifecycle.
- `PluginSettings.notificationLevel: String` from Plan 1 is the user's toggle.
- Project-scoped service pattern from Plans 5/6 — `@Service(Service.Level.PROJECT)`, `Disposable`, owns a `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- The `ToolWindowController.refresh` in `GhActionsToolWindowFactory` already sits on the EDT (since the EDT-marshaling fix in commit `d4437b7`) and is the right place to start the NotificationCenter alongside `PollingCoordinator`.
- Commits one per task, type-prefixed; **no `Co-Authored-By` trailer**.

---

## File Structure

```
gh_actions_pycharm/
└── src/
    ├── main/
    │   ├── kotlin/com/example/ghactions/
    │   │   └── notifications/
    │   │       ├── NotificationDecider.kt   (new — pure policy)
    │   │       └── NotificationCenter.kt    (new — project service)
    │   ├── resources/META-INF/
    │   │   └── plugin.xml                   (modify — declare notificationGroup)
    │   └── kotlin/com/example/ghactions/ui/
    │       └── GhActionsToolWindowFactory.kt (modify — start NotificationCenter)
    └── test/kotlin/com/example/ghactions/
        └── notifications/
            └── NotificationDeciderTest.kt   (new)
```

**File responsibility notes:**
- `NotificationDecider` is pure: takes data structures, returns data structures, no IDE deps. Easy to unit-test exhaustively.
- `NotificationCenter` is project-scoped wiring: subscribes to `RunRepository.runsState`, maintains an in-memory map of last-seen status by `RunId`, calls the decider, fires `NotificationGroup.createNotification(...)`. Implements `Disposable`; the platform disposes it on project close.
- `plugin.xml` change is one extension point declaration:
  ```xml
  <notificationGroup id="GitHub Actions Monitor"
                     displayType="BALLOON"
                     bundle="messages.GhActionsBundle" key="notification.group.name"/>
  ```
  We use a hardcoded display name in code (no resource bundle), so we'll skip the `bundle/key` attributes and rely on the `id` as the visible name. Keep it simple for v1.

---

## Conventions

- Tests: JUnit 5 + `kotlin.test.*`. Pure functions where possible; the decider gets full coverage.
- Production scope `Dispatchers.IO`; tests don't touch coroutines (the decider isn't `suspend`).
- Commits one per task, type-prefixed.
- **Cumulative test target:** 140 (Plan 10) + 6 (decider tests) = ~146.

---

## Task 1: Pure `NotificationDecider`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/notifications/NotificationDecider.kt`
- Create: `src/test/kotlin/com/example/ghactions/notifications/NotificationDeciderTest.kt`

The policy:
- A run is **completing** if its previous-known status was non-terminal (`QUEUED` or `IN_PROGRESS` — or unseen) and its current status is terminal (`COMPLETED`).
- For `OFF`: no events.
- For `FAILURES_ONLY`: emit only when the conclusion is non-success (`FAILURE`, `CANCELLED`, `TIMED_OUT`, etc).
- For `ALL`: emit every completion, including success.

The decider returns a list of `NotificationEvent(run, kind)` where `kind` is `SUCCESS` or `FAILURE`. The center renders each event into a balloon.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/notifications/NotificationDeciderTest.kt`:

```kotlin
package com.example.ghactions.notifications

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationDeciderTest {

    private fun run(
        id: Long,
        status: RunStatus,
        conclusion: RunConclusion? = null,
        workflowName: String = "CI"
    ) = Run(
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
    fun `OFF emits nothing even when a run completes with failure`() {
        val previous = mapOf(RunId(1) to RunStatus.IN_PROGRESS)
        val current = listOf(run(1, RunStatus.COMPLETED, RunConclusion.FAILURE))
        val events = NotificationDecider.decide(previous, current, NotificationLevel.OFF)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `FAILURES_ONLY emits on failure but not success`() {
        val previous = mapOf(
            RunId(1) to RunStatus.IN_PROGRESS,
            RunId(2) to RunStatus.IN_PROGRESS
        )
        val current = listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.FAILURE),
            run(2, RunStatus.COMPLETED, RunConclusion.SUCCESS)
        )
        val events = NotificationDecider.decide(previous, current, NotificationLevel.FAILURES_ONLY)
        assertEquals(1, events.size)
        assertEquals(RunId(1), events[0].run.id)
        assertEquals(NotificationKind.FAILURE, events[0].kind)
    }

    @Test
    fun `ALL emits on every terminal transition`() {
        val previous = mapOf(
            RunId(1) to RunStatus.IN_PROGRESS,
            RunId(2) to RunStatus.IN_PROGRESS
        )
        val current = listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.FAILURE),
            run(2, RunStatus.COMPLETED, RunConclusion.SUCCESS)
        )
        val events = NotificationDecider.decide(previous, current, NotificationLevel.ALL)
        assertEquals(2, events.size)
        assertTrue(events.any { it.run.id == RunId(1) && it.kind == NotificationKind.FAILURE })
        assertTrue(events.any { it.run.id == RunId(2) && it.kind == NotificationKind.SUCCESS })
    }

    @Test
    fun `runs that were already terminal in the previous snapshot do not fire`() {
        val previous = mapOf(RunId(1) to RunStatus.COMPLETED)
        val current = listOf(run(1, RunStatus.COMPLETED, RunConclusion.SUCCESS))
        val events = NotificationDecider.decide(previous, current, NotificationLevel.ALL)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `runs unseen in the previous snapshot do not fire`() {
        // First time we see a run — even if it's already completed, don't notify (we don't
        // know if the user already saw it elsewhere; only fire on transitions we observed).
        val previous = emptyMap<RunId, RunStatus>()
        val current = listOf(run(1, RunStatus.COMPLETED, RunConclusion.FAILURE))
        val events = NotificationDecider.decide(previous, current, NotificationLevel.ALL)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `cancelled and timed out runs are treated as failures for FAILURES_ONLY`() {
        val previous = mapOf(
            RunId(1) to RunStatus.IN_PROGRESS,
            RunId(2) to RunStatus.IN_PROGRESS
        )
        val current = listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.CANCELLED),
            run(2, RunStatus.COMPLETED, RunConclusion.TIMED_OUT)
        )
        val events = NotificationDecider.decide(previous, current, NotificationLevel.FAILURES_ONLY)
        assertEquals(2, events.size)
        assertTrue(events.all { it.kind == NotificationKind.FAILURE })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.notifications.NotificationDeciderTest`
Expected: FAIL — `NotificationDecider`, `NotificationLevel`, `NotificationKind`, `NotificationEvent` unresolved.

- [ ] **Step 3: Implement the decider**

Create `src/main/kotlin/com/example/ghactions/notifications/NotificationDecider.kt`:

```kotlin
package com.example.ghactions.notifications

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus

/** User's setting for how loud the notifier is. Mirrors `PluginSettings.notificationLevel`. */
enum class NotificationLevel {
    OFF, FAILURES_ONLY, ALL;

    companion object {
        fun fromSetting(value: String?): NotificationLevel = when (value?.uppercase()) {
            "OFF" -> OFF
            "ALL" -> ALL
            else -> FAILURES_ONLY  // sane default — matches the settings default
        }
    }
}

/** What kind of balloon to show for a completed run. */
enum class NotificationKind { SUCCESS, FAILURE }

/** A balloon event the [NotificationCenter] should fire. */
data class NotificationEvent(val run: Run, val kind: NotificationKind)

/**
 * Pure policy: given the previously-seen status of each run, the current run list, and
 * the user's setting, return the notification events that should fire.
 *
 * **Rules**
 * - We only fire on a *transition* we observed: the previous status must have been
 *   non-terminal AND the current status must be terminal.
 * - First-time-seen runs (unseen in [previousStatus]) never fire — we don't have evidence
 *   that the user *missed* the transition; they may have just opened the project.
 * - `OFF` → empty list always.
 * - `FAILURES_ONLY` → fire only when the conclusion isn't [RunConclusion.SUCCESS].
 * - `ALL` → fire on every observed transition.
 */
object NotificationDecider {

    fun decide(
        previousStatus: Map<RunId, RunStatus>,
        currentRuns: List<Run>,
        level: NotificationLevel
    ): List<NotificationEvent> {
        if (level == NotificationLevel.OFF) return emptyList()
        return currentRuns.mapNotNull { run ->
            val previous = previousStatus[run.id] ?: return@mapNotNull null
            if (previous.isTerminal()) return@mapNotNull null
            if (!run.status.isTerminal()) return@mapNotNull null
            val kind = if (run.conclusion == RunConclusion.SUCCESS) {
                NotificationKind.SUCCESS
            } else {
                NotificationKind.FAILURE
            }
            if (level == NotificationLevel.FAILURES_ONLY && kind == NotificationKind.SUCCESS) {
                return@mapNotNull null
            }
            NotificationEvent(run, kind)
        }
    }

    private fun RunStatus.isTerminal(): Boolean = this == RunStatus.COMPLETED
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.notifications.NotificationDeciderTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 146 tests (140 + 6).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/notifications/NotificationDecider.kt \
        src/test/kotlin/com/example/ghactions/notifications/NotificationDeciderTest.kt
git commit -m "feat(notifications): add NotificationDecider policy"
```

---

## Task 2: Register notification group in `plugin.xml`

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`

Declare the group so balloons appear and so users can adjust per-group preferences in *Settings → Appearance & Behavior → Notifications*.

- [ ] **Step 1: Read the current plugin.xml**

Run: `cat src/main/resources/META-INF/plugin.xml`

Find the `<extensions defaultExtensionNs="com.intellij">` block. (If there isn't one yet, add it inside `<idea-plugin>`.)

- [ ] **Step 2: Add the `notificationGroup` extension**

Inside the existing `<extensions defaultExtensionNs="com.intellij">`, add (alphabetically among other extensions if any):

```xml
<notificationGroup id="GitHub Actions Monitor"
                   displayType="BALLOON"/>
```

The `id` doubles as the user-visible group name. `BALLOON` makes them appear in the corner without sticking around — appropriate for transient run-completion events.

- [ ] **Step 3: Verify compile**

Run: `./gradlew --no-daemon compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(notifications): register 'GitHub Actions Monitor' notification group"
```

---

## Task 3: `NotificationCenter` project service

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/notifications/NotificationCenter.kt`

Project service. Owns a coroutine scope on `Dispatchers.IO`. Subscribes to `RunRepository.runsState`. Maintains `previousStatus: Map<RunId, RunStatus>`. On each emission: ask `NotificationDecider`, fire balloons for every event, update the map.

- [ ] **Step 1: Create the file**

Create `src/main/kotlin/com/example/ghactions/notifications/NotificationCenter.kt`:

```kotlin
package com.example.ghactions.notifications

import com.example.ghactions.auth.PluginSettings
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.repo.RunListState
import com.example.ghactions.repo.RunRepository
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ide.BrowserUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches `RunRepository.runsState` for terminal transitions and fires IDE balloon
 * notifications according to the user's `PluginSettings.notificationLevel`.
 *
 * Per-project; the platform disposes us on project close. Started by
 * `GhActionsToolWindowFactory` (idempotent — repeat calls are no-ops).
 */
@Service(Service.Level.PROJECT)
class NotificationCenter(private val project: Project) : Disposable {

    private val log = Logger.getInstance(NotificationCenter::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val previousStatus = ConcurrentHashMap<RunId, RunStatus>()
    private var observerJob: CJob? = null

    fun start() {
        if (observerJob != null) return
        val runRepo = project.getService(RunRepository::class.java)
        observerJob = scope.launch {
            runRepo.runsState.collect { state -> handle(state) }
        }
    }

    private fun handle(state: RunListState) {
        val runs = (state as? RunListState.Loaded)?.runs ?: return
        val level = NotificationLevel.fromSetting(
            PluginSettings.getInstance().state.notificationLevel
        )
        val snapshot = previousStatus.toMap()
        val events = NotificationDecider.decide(snapshot, runs, level)
        events.forEach(::fire)
        // Update snapshot — record the new status of every run we saw, including ones
        // that didn't transition. Keeps the map fresh as runs move QUEUED → IN_PROGRESS → COMPLETED.
        runs.forEach { previousStatus[it.id] = it.status }
    }

    private fun fire(event: NotificationEvent) {
        val run = event.run
        val title = "${run.workflowName} · run #${run.runNumber}"
        val body = when (event.kind) {
            NotificationKind.SUCCESS -> "Succeeded"
            NotificationKind.FAILURE -> "Failed: ${run.conclusion?.name?.lowercase() ?: "unknown"}"
        }
        val type = when (event.kind) {
            NotificationKind.SUCCESS -> NotificationType.INFORMATION
            NotificationKind.FAILURE -> NotificationType.ERROR
        }
        try {
            val notification: Notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, body, type)
                .addAction(object : NotificationAction("View on GitHub") {
                    override fun actionPerformed(e: AnActionEvent, n: Notification) {
                        BrowserUtil.browse(run.htmlUrl)
                        n.expire()
                    }
                })
            notification.notify(project)
        } catch (t: Throwable) {
            // Notification group may not be registered (e.g. in tests). Don't crash.
            log.warn("Failed to fire notification for run ${run.id}", t)
        }
    }

    override fun dispose() {
        observerJob?.cancel()
        scope.cancel()
        previousStatus.clear()
    }

    companion object {
        const val NOTIFICATION_GROUP_ID = "GitHub Actions Monitor"
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — still 146 tests, no regressions.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/notifications/NotificationCenter.kt
git commit -m "feat(notifications): add NotificationCenter project service"
```

---

## Task 4: Start `NotificationCenter` from `GhActionsToolWindowFactory`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt`

Tool-window opening already starts the `PollingCoordinator` (Plan 6). Add one more line for the notification center, alongside the existing coordinator-start.

- [ ] **Step 1: Read the current file**

Run: `cat src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt`

You should see (after the EDT-marshaling fix and Plan 6 wiring):

```kotlin
val coordinator = project.getService(com.example.ghactions.polling.PollingCoordinator::class.java)
coordinator.setToolWindowVisible(toolWindow.isVisible)
coordinator.start()
```

- [ ] **Step 2: Add the NotificationCenter start**

Insert immediately after `coordinator.start()`:

```kotlin
val notifications = project.getService(com.example.ghactions.notifications.NotificationCenter::class.java)
notifications.start()
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 146 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/GhActionsToolWindowFactory.kt
git commit -m "feat(notifications): start NotificationCenter on tool-window open"
```

---

## Task 5: Smoke test (deferred-okay) + final sweep + merge

**Files:** none — verification + merge only.

- [ ] **Step 1: Manual smoke (deferred is fine)**

Run: `./gradlew --no-daemon runIde`

Verify:
- With `notificationLevel = OFF` (Settings → Tools → GitHub Actions Monitor): no balloon when a run completes. (Trigger a workflow run on the bound repo, wait for adaptive polling to pick up the transition.)
- With `FAILURES_ONLY` (default): a red balloon appears on a failed run. Clicking *View on GitHub* opens the run page. No balloon for successful runs.
- With `ALL`: a green balloon for success and a red balloon for failure.
- Tool window closed: balloons still fire (the NotificationCenter started at the first tool-window open and remains alive for the project's lifetime; closing the window only hides the panel).
- Settings → Appearance & Behavior → Notifications: a *GitHub Actions Monitor* group is listed, allowing per-group muting.

If any step fails, write the deviation to `docs/superpowers/notes/2026-04-30-notifications-smoke-deviations.md` and commit:

```bash
git add docs/superpowers/notes/
git commit -m "docs: smoke test deviations for plan 11"
```

If no deviations, skip the commit.

- [ ] **Step 2: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS — 146 tests.

- [ ] **Step 3: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED. The existing 1 `SegmentedButton` warning is acceptable. `Notification`, `NotificationGroupManager`, `BrowserUtil`, `NotificationAction` are stable APIs; no new warnings expected.

- [ ] **Step 5: Fast-forward merge**

```bash
git checkout main
git merge --ff-only feat/plan-11-notifications
git log --oneline | head -10
```

- [ ] **Step 6: Plan-level verification**

- All 5 tasks have green check-marks (Step 1 may be deferred).
- `./gradlew test` passes (146).
- `./gradlew buildPlugin` and `verifyPlugin` succeed.

---

## What ships at the end of Plan 11

- IDE balloon notifications fire when a tracked run completes (transitions to terminal status), governed by `notificationLevel` in settings.
- *FAILURES_ONLY* (the default) shows red balloons for failed/cancelled/timed-out runs only; *ALL* also shows green balloons for successes; *OFF* silences everything.
- Each balloon carries a *View on GitHub* action that opens the run's `htmlUrl` in the OS browser.
- Notifications are registered as the *GitHub Actions Monitor* group, so the user can mute or relocate them via *Settings → Appearance & Behavior → Notifications*.

What it does **not** yet do:
- Status-bar widget summarising aggregate CI state.
- Re-run / cancel actions in the balloon.
- Per-PR or per-branch notification filtering.

---

## Open questions / risks

1. **Polling visibility.** Plan 6 pauses polling when the tool window is hidden. That means notifications also stop firing for state changes that happen while the panel is collapsed. This is intentional (we don't want background API spend), but the user should know — document it in the notification-group description if confusion comes up.
2. **First-load suppression.** The decider deliberately ignores runs unseen in the previous snapshot, so opening the IDE doesn't dump a wall of "completed run" balloons for everything that finished overnight. The trade-off: if a run completes on the *very first* polling tick after the tool window opens, the user sees no notification. We accept this — the run is visible in the panel anyway.
3. **Map memory growth.** `previousStatus` accumulates a `RunId → RunStatus` entry per ever-seen run. For long-running projects this could grow into the hundreds. Acceptable for v1; if memory profiling flags it, evict entries older than the latest snapshot.
