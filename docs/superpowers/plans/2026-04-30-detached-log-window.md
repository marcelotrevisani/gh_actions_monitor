# Plan 9 — Detached log window

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user pop a job's or step's log out of the tool window into its own non-modal floating window — useful for keeping a build log visible while editing other files. Two ways to trigger: (a) a *Open in new window* toolbar button on the run detail panel, (b) double-clicking a job or step node in the tree.

**Architecture:** A single new component, `LogWindow`, extends IntelliJ's `FrameWrapper` (the platform-supported way to host a non-modal floating IDE-owned frame). It hosts its own `LogViewerPanel`, owns a coroutine scope that subscribes to the same `RunRepository.logsState(...)` flow the embedded viewer uses, and disposes cleanly on close. Multiple `LogWindow` instances may coexist (one per job/step the user pops out). The trigger logic lives in `RunDetailPanel`: a toolbar action + a `MouseListener` for double-clicks. The embedded `LogViewerPanel` stays unchanged.

**Tech Stack:** Same as Plans 1–8 — Kotlin 2.0.21, Swing, IntelliJ Platform 2024.3 (`com.intellij.openapi.ui.FrameWrapper`). No new gradle deps.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` doesn't explicitly call out detached log windows, but the *RunDetailPanel* is described as having Logs / Annotations / Summary / Artifacts sub-tabs; popping logs out is a natural extension. User-requested feature, 2026-04-30.

**Sections deliberately deferred:**
- Persisting window position / size across restarts — `FrameWrapper` doesn't do this for free; defer.
- "Detach all open logs at once" — YAGNI.
- Synchronising scroll position between embedded and detached views — YAGNI.

**Plans 1–8 → Plan 9 carry-overs:**
- The embedded `LogViewerPanel` already renders ANSI (Plan 7) and respects the timestamp toggle (Plan 3). The detached window reuses it as-is.
- Coroutine scopes follow the existing `SupervisorJob() + Dispatchers.Main` pattern for UI subscribers (matches `RunDetailPanel`).
- Commits one per task, type-prefixed; **no `Co-Authored-By` trailer**.

---

## File Structure

```
gh_actions_pycharm/
└── src/main/kotlin/com/example/ghactions/ui/
    ├── LogWindow.kt          (new — FrameWrapper subclass with state-flow binding)
    └── RunDetailPanel.kt     (modify — toolbar action + double-click MouseListener)
```

No test files — `FrameWrapper` lifecycle is awkward to unit-test. Coverage comes from manual smoke (Task 3).

**File responsibility notes:**
- `LogWindow` is self-contained: takes a project, a title, a state-flow source, optionally a status hint; manages its own lifecycle. The trigger code (`RunDetailPanel`) just constructs and `show()`s it; the window cleans up its scope on close.
- `RunDetailPanel` gains: a toolbar at the top of the tree pane with one action (*Open in new window*), and a `MouseListener` on the existing tree to detect double-click. The existing single-click selection behaviour stays untouched.

---

## Conventions

- One commit per task; type-prefixed (`feat:`).
- No automated tests in this plan — manual smoke (Task 3) is the gate.
- Cumulative test count after Plan 9: unchanged from Plan 8 end state (~132).

---

## Task 1: `LogWindow` component

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/ui/LogWindow.kt`

A `FrameWrapper` that wraps a `LogViewerPanel` and binds to a `Flow<LogState>`. The window is shown via `show()`; the caller doesn't need to manage disposal because `FrameWrapper` registers itself with the project disposer chain and the window closes via the user's normal frame-close gesture.

- [ ] **Step 1: Create the file**

Create `src/main/kotlin/com/example/ghactions/ui/LogWindow.kt`:

```kotlin
package com.example.ghactions.ui

import com.example.ghactions.repo.LogState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.FrameWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.awt.Dimension

/**
 * Non-modal floating window hosting a [LogViewerPanel]. Bound to a [Flow] of [LogState]
 * — typically `RunRepository.logsState(jobId)` or `logsState(jobId, stepNumber)` — so the
 * detached view tracks the same data source as the embedded viewer.
 *
 * Multiple windows may coexist; each owns its own coroutine scope and a `LogViewerPanel`.
 * The window is shown via [show]; closing it (via the OS frame-close gesture) cancels the
 * scope and releases the panel through the [Disposable] chain established by [FrameWrapper].
 */
class LogWindow(
    project: Project,
    windowTitle: String,
    private val statusHint: String,
    private val source: Flow<LogState>
) : FrameWrapper(project, dimensionKey = null, isDialog = false) {

    private val panel = LogViewerPanel()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observerJob: CJob? = null

    init {
        title = windowTitle
        component = panel
        size = Dimension(900, 600)
        panel.setStatus(statusHint)
        observerJob = scope.launch {
            source.collect { render(it) }
        }
    }

    private fun render(state: LogState) {
        when (state) {
            is LogState.Idle -> panel.clear()
            is LogState.Loading -> { panel.setText("(loading logs…)"); panel.setStatus(statusHint) }
            is LogState.Loaded -> { panel.setText(state.text); panel.setStatus(statusHint) }
            is LogState.Error -> {
                panel.setText("Failed${state.httpStatus?.let { " ($it)" } ?: ""}: ${state.message}")
                panel.setStatus("")
            }
        }
    }

    override fun dispose() {
        observerJob?.cancel()
        scope.cancel()
        super.dispose()
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/ui/LogWindow.kt
git commit -m "feat(ui): add LogWindow (detached log viewer)"
```

---

## Task 2: Toolbar action + double-click handler in `RunDetailPanel`

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt`

Add (a) a small toolbar above the tree with one action that pops the *currently selected* job/step into a new window, and (b) a `MouseListener` on the tree that triggers the same logic on double-click.

- [ ] **Step 1: Read the current file**

Run: `cat src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt`

You should see the existing fields including `tree`, `treeModel`, `rootNode`, the splitter, etc. Find:
- The `tree` declaration (it has `addTreeSelectionListener { ... showJobLogs / showStepLogs }`).
- The `init { border = ...; add(splitter, ...) }` block.

### Step 2: Add the helper that constructs and shows a `LogWindow` for a given selection

Inside the `RunDetailPanel` class, add a private method:

```kotlin
private fun openSelectionInNewWindow() {
    val node = tree.lastSelectedPathComponent as? javax.swing.tree.DefaultMutableTreeNode ?: return
    val payload = node.userObject
    val run = rootNode.userObject as? com.example.ghactions.domain.Run ?: return
    when (payload) {
        is com.example.ghactions.domain.Job -> openJobInNewWindow(run, payload)
        is com.example.ghactions.domain.Step -> {
            val parentJob = (node.parent as? javax.swing.tree.DefaultMutableTreeNode)?.userObject
                as? com.example.ghactions.domain.Job ?: return
            openStepInNewWindow(run, parentJob, payload)
        }
        else -> Unit
    }
}

private fun openJobInNewWindow(run: com.example.ghactions.domain.Run, job: com.example.ghactions.domain.Job) {
    repository.refreshLogs(job.id)
    val window = LogWindow(
        project = project,
        windowTitle = "${run.workflowName} · ${job.name} (run #${run.id})",
        statusHint = "Job · ${job.name}",
        source = repository.logsState(job.id)
    )
    window.show()
}

private fun openStepInNewWindow(
    run: com.example.ghactions.domain.Run,
    job: com.example.ghactions.domain.Job,
    step: com.example.ghactions.domain.Step
) {
    repository.refreshStepLog(
        runId = run.id,
        jobId = job.id,
        jobName = job.name,
        stepNumber = step.number,
        stepName = step.name
    )
    val window = LogWindow(
        project = project,
        windowTitle = "${run.workflowName} · ${job.name} · ${step.name} (run #${run.id})",
        statusHint = "Step ${step.number} · ${step.name}",
        source = repository.logsState(job.id, stepNumber = step.number)
    )
    window.show()
}
```

This requires `RunDetailPanel` to keep a reference to its `Project`. Looking at the existing constructor: `class RunDetailPanel(project: Project)` — `project` is a constructor parameter; promote it to a private field. Change:

```kotlin
class RunDetailPanel(project: Project) : JPanel(BorderLayout()), Disposable {
```

to:

```kotlin
class RunDetailPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
```

### Step 3: Add the toolbar above the tree

Find the line:

```kotlin
private val splitter = OnePixelSplitter(true, 0.4f).apply {
    firstComponent = JPanel(BorderLayout()).apply {
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }
    secondComponent = logViewer
}
```

Replace the `firstComponent = ...` block so the tree pane includes a small toolbar with one *Open in new window* action:

```kotlin
private val splitter = OnePixelSplitter(true, 0.4f).apply {
    firstComponent = JPanel(BorderLayout()).apply {
        add(buildTreeToolbar().component, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }
    secondComponent = logViewer
}
```

Then add the `buildTreeToolbar` helper inside the class:

```kotlin
private fun buildTreeToolbar(): com.intellij.openapi.actionSystem.ActionToolbar {
    val group = com.intellij.openapi.actionSystem.DefaultActionGroup().apply {
        add(object : com.intellij.openapi.actionSystem.AnAction(
            "Open in New Window",
            "Pop the selected job's or step's log into a separate window",
            com.intellij.icons.AllIcons.Actions.MoveToWindow
        ) {
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val payload = (tree.lastSelectedPathComponent as? javax.swing.tree.DefaultMutableTreeNode)?.userObject
                e.presentation.isEnabled = payload is com.example.ghactions.domain.Job
                    || payload is com.example.ghactions.domain.Step
            }
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                openSelectionInNewWindow()
            }
        })
    }
    val tb = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        .createActionToolbar(com.intellij.openapi.actionSystem.ActionPlaces.TOOLWINDOW_CONTENT, group, true)
    tb.targetComponent = this
    return tb
}
```

### Step 4: Add the double-click handler

Find the existing `tree` declaration and add a `MouseAdapter` to it. After the existing block (you can extend the `apply { ... }`), add a separate listener registration in the existing `init` block:

```kotlin
init {
    border = JBUI.Borders.empty()
    add(splitter, BorderLayout.CENTER)
    tree.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseClicked(e: java.awt.event.MouseEvent) {
            if (e.clickCount == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                openSelectionInNewWindow()
                e.consume()
            }
        }
    })
}
```

(The existing `init { border = ...; add(splitter, ...) }` block already exists — extend it; do **not** create a second `init` block.)

### Step 5: Verify compile

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

### Step 6: Run the full test suite

Run: `./gradlew --no-daemon test`
Expected: PASS — same count as before this plan started, no regressions.

### Step 7: Commit

```bash
git add src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt
git commit -m "feat(ui): toolbar action + double-click to open log in new window"
```

---

## Task 3: Manual smoke test (`./gradlew runIde`)

**Files:** none.

- [ ] **Step 1: Launch the dev IDE**

Run: `./gradlew --no-daemon runIde`
Expected: sandbox IDE opens.

- [ ] **Step 2: Open a run, select a job**

Click *Refresh* in the GitHub Actions tool window, expand a PR, click a run. Confirm the jobs/steps tree appears in the lower pane.

Click a job. The toolbar above the tree should now show *Open in New Window* (enabled).

- [ ] **Step 3: Click *Open in New Window***

Expected: a separate floating window opens with the title `<workflow> · <job> (run #<id>)` and the same log content as the embedded viewer.

- [ ] **Step 4: Double-click a step**

Expected: a separate window opens with the step's log; embedded viewer also updates to show the step's log (because the single-click selection listener also fires).

- [ ] **Step 5: Multiple windows open simultaneously**

Pop two more jobs/steps. Confirm they all stay alive, each tracking its own data flow.

- [ ] **Step 6: Live updates**

If a run is in-progress and Plan 6's polling is active, the detached window's content should update on the same cadence as the embedded view (~5 s active / ~60 s idle).

- [ ] **Step 7: Close behavior**

Close one detached window via the OS gesture. Confirm:
- No "memory leak" SEVERE in `build/idea-sandbox/IC-2024.3/log/idea.log`.
- The embedded viewer continues to work normally.
- Other detached windows are unaffected.

- [ ] **Step 8: Document any deviations**

If any step fails, write the deviation in `docs/superpowers/notes/2026-04-30-detached-log-window-smoke-deviations.md` and commit:

```bash
git add docs/superpowers/notes/
git commit -m "docs: smoke test deviations for plan 9"
```

If no deviations, skip the commit.

---

## Task 4: Final sweep + merge

**Files:** none.

- [ ] **Step 1: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS — same count as before this plan, no regressions.

- [ ] **Step 2: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED against IDEA 2024.3 + 2025.1. The 2 existing `SegmentedButton` warnings are acceptable. `FrameWrapper` may surface a new experimental-API warning depending on platform version — acceptable.

- [ ] **Step 4: Fast-forward merge**

```bash
git checkout main
git merge --ff-only feat/plan-9-detached-log-window
git log --oneline | head -10
```

Expected: clean fast-forward.

- [ ] **Step 5: Plan-level verification**

- All 4 tasks have green check-marks (Task 3 may be deferred).
- `./gradlew test` passes.
- `./gradlew buildPlugin` succeeds.
- `./gradlew verifyPlugin` succeeds.

---

## What ships at the end of Plan 9

- A new *Open in New Window* toolbar action above the jobs/steps tree.
- Double-clicking a job or step in the tree opens its log in a separate floating window.
- Multiple detached windows can be open simultaneously; each tracks the same `RunRepository` state-flow as the embedded viewer.
- Closing a detached window cancels its observer cleanly; no leaks.

What it does **not** yet do:
- Persist window position/size across IDE restarts.
- Synchronise scroll position between embedded and detached views.
- "Detach all" / "Restore all" bulk actions.

---

## Open questions / risks

1. **`FrameWrapper` API stability.** It's a documented IntelliJ Platform class but parts may be marked experimental. The plugin verifier will flag any experimental-API uses; acceptable for this plugin.
2. **Multiple windows for the same job.** Two pops of the same selection both bind to the same flow — fine, both stay in sync. No coalescing logic, no de-duplication. If the user wants only one window per (job, step), that's a future enhancement.
3. **Window lifecycle on project close.** `FrameWrapper(project, ...)` registers with the project's disposer chain, so closing the project disposes any open detached windows. Verified by smoke test step 7 (no leak SEVEREs).
