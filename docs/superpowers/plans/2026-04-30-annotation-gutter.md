# Plan 14 — Annotation gutter markers

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render gutter icons for the currently-selected run's annotations next to the corresponding source lines. Hover → tooltip shows severity + message. The Annotations tab from Plan 10 already lists them; this plan puts them inline so the user sees the failure right where they're editing.

**Architecture:** Three pieces. (1) `ActiveAnnotationsService` (project service) holds the *currently-displayed* run id and subscribes to `RunRepository.annotationsState` for that run, caching a `Map<String, List<Annotation>>` keyed by repo-relative path. (2) `AnnotationGutterRenderer` (pure) implements `GutterIconRenderer` — produces the severity icon + tooltip for one annotation. (3) An `EditorFactoryListener`, started by the service in `init {}`, observes `editorCreated`/`editorReleased` and applies/removes gutter renderers via `MarkupModelEx.addRangeHighlighter(...)` whenever an editor opens or the cached annotations change. `RunDetailPanel.showRun(run)` calls `service.setActiveRun(run.id)`; `clear()` calls `service.setActiveRun(null)`. The whole thing is language-agnostic — no per-language registration needed.

**Tech Stack:** Same as Plans 1–13 — Kotlin 2.0.21, IntelliJ Platform 2024.3, JUnit 5 + `kotlin.test.*`. No new gradle deps.

**Spec reference:** `docs/superpowers/specs/2026-04-28-pycharm-gh-actions-plugin-design.md` (commit `6abeb59`):
- *IDE integrations → Annotation gutter markers*.

**Sections deliberately deferred:**
- *SHA mismatch banner* — the spec mentions warning when the working copy differs from the run's `head_sha`. v1 just shows the markers regardless; line numbers may drift on edits, which is acceptable.
- *Problems tool window* integration — separate plan; uses the same data.
- *Stale-marker toggle* — defer.
- Click-to-jump to the run (the click target is the gutter icon; tooltip is read-only). The tooltip + the existing Annotations tab cover the navigation case.

**Plans 1–13 → Plan 14 carry-overs:**
- `domain.Annotation(path, startLine, endLine, level, title, message, rawDetails)` from Plan 10.
- `domain.AnnotationLevel { NOTICE, WARNING, FAILURE, UNKNOWN }`.
- `RunRepository.annotationsState(runId): StateFlow<AnnotationsState>` from Plan 10.
- `RunDetailPanel.showRun(run: Run)` and `clear()` from Plans 8/onwards.
- `Project.basePath` resolves repo-relative paths; same approach as `AnnotationsPanel.openInEditor` from Plan 10.
- Commits one per task, type-prefixed; **no `Co-Authored-By` trailer**.

---

## File Structure

```
gh_actions_pycharm/
└── src/
    ├── main/
    │   └── kotlin/com/example/ghactions/
    │       └── editor/
    │           ├── ActiveAnnotationsService.kt   (new — project service)
    │           ├── AnnotationGutterRenderer.kt   (new — pure GutterIconRenderer)
    │           └── EditorAnnotationsApplier.kt   (new — MarkupModel application logic)
    └── test/kotlin/com/example/ghactions/
        └── editor/
            └── AnnotationGutterRendererTest.kt   (new — icon + tooltip mapping)
```

Plus minimal modifications:
- `src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt` — push the run id to the service.

**File responsibility notes:**
- `AnnotationGutterRenderer` is pure (no IDE platform deps beyond `Icon`). Tested in isolation.
- `EditorAnnotationsApplier` is a small object with `applyToEditor(editor, annotations)` and `clearFor(editor)`. Uses `MarkupModelEx.addRangeHighlighter(..., HighlighterTargetArea.LINES_IN_RANGE)`. Tracks the highlighters it created on each editor via `Editor.putUserData(ourKey, list)`.
- `ActiveAnnotationsService` is the project-scoped wiring: lifecycle + EditorFactoryListener + state-flow subscription + reapply logic.

---

## Conventions

- Tests: JUnit 5, `kotlin.test.*`. Pure logic only — the service + applier are integration code; verified by smoke (Task 5).
- **Cumulative test target:** 158 (Plan 13) + 3 (gutter renderer tests) = ~161.
- Commits one per task, type-prefixed.

---

## Task 1: Pure `AnnotationGutterRenderer`

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/editor/AnnotationGutterRenderer.kt`
- Create: `src/test/kotlin/com/example/ghactions/editor/AnnotationGutterRendererTest.kt`

The renderer is essentially a value object — `equals`/`hashCode` matter (the platform reuses identical renderers across redraws), and `getIcon()`/`getTooltipText()` are read-only.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/example/ghactions/editor/AnnotationGutterRendererTest.kt`:

```kotlin
package com.example.ghactions.editor

import com.example.ghactions.domain.Annotation
import com.example.ghactions.domain.AnnotationLevel
import com.intellij.icons.AllIcons
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AnnotationGutterRendererTest {

    private fun ann(level: AnnotationLevel, message: String, title: String? = null): Annotation =
        Annotation(
            path = "src/main.kt",
            startLine = 10, endLine = 10,
            level = level, title = title, message = message
        )

    @Test
    fun `icon picked from severity`() {
        assertEquals(AllIcons.General.Error, AnnotationGutterRenderer(ann(AnnotationLevel.FAILURE, "boom")).icon)
        assertEquals(AllIcons.General.Warning, AnnotationGutterRenderer(ann(AnnotationLevel.WARNING, "x")).icon)
        assertEquals(AllIcons.General.Information, AnnotationGutterRenderer(ann(AnnotationLevel.NOTICE, "x")).icon)
    }

    @Test
    fun `tooltip prepends title when present`() {
        val tooltip = AnnotationGutterRenderer(ann(AnnotationLevel.FAILURE, "the message", title = "Build")).tooltipText
        assertTrue(tooltip.contains("Build"), "tooltip missing title: $tooltip")
        assertTrue(tooltip.contains("the message"), "tooltip missing message: $tooltip")
    }

    @Test
    fun `equals respects underlying annotation`() {
        val a = AnnotationGutterRenderer(ann(AnnotationLevel.WARNING, "foo"))
        val b = AnnotationGutterRenderer(ann(AnnotationLevel.WARNING, "foo"))
        val c = AnnotationGutterRenderer(ann(AnnotationLevel.WARNING, "bar"))
        assertEquals(a, b, "same annotation -> equal renderers")
        assertNotEquals(a, c, "different message -> different renderers")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.editor.AnnotationGutterRendererTest`
Expected: FAIL — `AnnotationGutterRenderer` unresolved.

- [ ] **Step 3: Implement the renderer**

Create `src/main/kotlin/com/example/ghactions/editor/AnnotationGutterRenderer.kt`:

```kotlin
package com.example.ghactions.editor

import com.example.ghactions.domain.Annotation
import com.example.ghactions.domain.AnnotationLevel
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import javax.swing.Icon

/**
 * Single-annotation gutter icon. The platform reuses identical renderers across
 * redraws (cheap when [equals]/[hashCode] return value-based results), so we delegate
 * to the underlying [Annotation]'s data class equality.
 */
class AnnotationGutterRenderer(val annotation: Annotation) : GutterIconRenderer() {

    override fun getIcon(): Icon = when (annotation.level) {
        AnnotationLevel.FAILURE -> AllIcons.General.Error
        AnnotationLevel.WARNING -> AllIcons.General.Warning
        AnnotationLevel.NOTICE -> AllIcons.General.Information
        AnnotationLevel.UNKNOWN -> AllIcons.General.Note
    }

    override fun getTooltipText(): String {
        val title = annotation.title?.takeIf { it.isNotBlank() }
        return if (title != null) "$title — ${annotation.message}" else annotation.message
    }

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun equals(other: Any?): Boolean =
        other is AnnotationGutterRenderer && other.annotation == annotation

    override fun hashCode(): Int = annotation.hashCode()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon test --tests com.example.ghactions.editor.AnnotationGutterRendererTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew --no-daemon test`
Expected: PASS — 161 tests (158 + 3).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/editor/AnnotationGutterRenderer.kt \
        src/test/kotlin/com/example/ghactions/editor/AnnotationGutterRendererTest.kt
git commit -m "feat(editor): add AnnotationGutterRenderer for severity icons"
```

---

## Task 2: `EditorAnnotationsApplier` — markup model wiring

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/editor/EditorAnnotationsApplier.kt`

This is the IDE-side glue. Given an `Editor` and a list of `Annotation`s for the file, it adds one `RangeHighlighter` per annotation with the gutter renderer attached. We track the highlighters we added in user data so a subsequent `applyToEditor` call cleans them up before adding new ones (otherwise they'd accumulate).

No automated tests — this needs a real `Editor`, which BasePlatformTestCase complicates; smoke is the gate.

- [ ] **Step 1: Create the file**

Create `src/main/kotlin/com/example/ghactions/editor/EditorAnnotationsApplier.kt`:

```kotlin
package com.example.ghactions.editor

import com.example.ghactions.domain.Annotation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Key

/**
 * Applies annotation gutter icons to an [Editor]. Tracks the highlighters we added in
 * [Editor.getUserData] so each subsequent call cleans up the previous batch before adding
 * a new one — otherwise repeated state-flow emissions would stack identical icons.
 *
 * Pure non-static state lives only on the [Editor] instance; the singleton is stateless.
 */
object EditorAnnotationsApplier {

    private val OUR_HIGHLIGHTERS = Key.create<MutableList<RangeHighlighter>>("ghactions.gutterHighlighters")

    fun applyToEditor(editor: Editor, annotations: List<Annotation>) {
        clearFor(editor)
        if (annotations.isEmpty()) return
        val markup = editor.markupModel
        val document = editor.document
        val list = mutableListOf<RangeHighlighter>()
        for (a in annotations) {
            val rangeOk = a.startLine >= 1 && a.startLine <= document.lineCount
            if (!rangeOk) continue
            val highlighter = addHighlighter(markup, document, a)
            list += highlighter
        }
        editor.putUserData(OUR_HIGHLIGHTERS, list)
    }

    fun clearFor(editor: Editor) {
        val existing = editor.getUserData(OUR_HIGHLIGHTERS) ?: return
        existing.forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(OUR_HIGHLIGHTERS, null)
    }

    private fun addHighlighter(
        markup: MarkupModel,
        document: Document,
        a: Annotation
    ): RangeHighlighter {
        // GitHub annotations are 1-based; Document.getLineStartOffset is 0-based.
        val line = (a.startLine - 1).coerceIn(0, document.lineCount - 1)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val highlighter = markup.addRangeHighlighter(
            start, end,
            HighlighterLayer.WARNING,            // above syntax, below errors
            null,                                 // no text attributes — gutter only
            HighlighterTargetArea.LINES_IN_RANGE
        )
        highlighter.gutterIconRenderer = AnnotationGutterRenderer(a)
        return highlighter
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/editor/EditorAnnotationsApplier.kt
git commit -m "feat(editor): add EditorAnnotationsApplier (markup model wiring)"
```

---

## Task 3: `ActiveAnnotationsService` project service

**Files:**
- Create: `src/main/kotlin/com/example/ghactions/editor/ActiveAnnotationsService.kt`

The orchestrator. Stores the active run id; subscribes to `RunRepository.annotationsState(runId)` for that run; on emission, builds `Map<String, List<Annotation>>` keyed by repo-relative path; re-applies highlighters across all open editors. Also registers an `EditorFactoryListener` so newly-opened editors get the markers right away.

- [ ] **Step 1: Create the file**

Create `src/main/kotlin/com/example/ghactions/editor/ActiveAnnotationsService.kt`:

```kotlin
package com.example.ghactions.editor

import com.example.ghactions.domain.Annotation
import com.example.ghactions.domain.RunId
import com.example.ghactions.repo.AnnotationsState
import com.example.ghactions.repo.RunRepository
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Project-scoped registry for the *currently-displayed* run's annotations. Subscribes to
 * `RunRepository.annotationsState(runId)` for that run; pushes gutter markers onto every
 * open editor whose file matches an annotation's repo-relative path.
 *
 * Updated by `RunDetailPanel.showRun(run)` (sets the active run) and `clear()` (sets null).
 */
@Service(Service.Level.PROJECT)
class ActiveAnnotationsService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ActiveAnnotationsService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val annotationsByPath = AtomicReference<Map<String, List<Annotation>>>(emptyMap())

    private var activeRunId: RunId? = null
    private var observerJob: CJob? = null

    init {
        // Re-apply markers whenever a new editor opens (covers the case where the user
        // opens a file *after* annotations are already cached).
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    val editor = event.editor
                    if (editor.project != project) return
                    applyTo(editor)
                }
                override fun editorReleased(event: EditorFactoryEvent) {
                    EditorAnnotationsApplier.clearFor(event.editor)
                }
            },
            this
        )
    }

    fun setActiveRun(runId: RunId?) {
        if (runId == activeRunId) return
        activeRunId = runId
        observerJob?.cancel()
        observerJob = null
        annotationsByPath.set(emptyMap())
        reapplyAll()
        if (runId == null) return
        val runRepo = project.getService(RunRepository::class.java)
        observerJob = scope.launch {
            runRepo.annotationsState(runId).collect { state -> handle(state) }
        }
    }

    private fun handle(state: AnnotationsState) {
        val byPath = when (state) {
            is AnnotationsState.Loaded -> state.items
                .map { it.annotation }
                .groupBy { it.path }
            else -> emptyMap()
        }
        annotationsByPath.set(byPath)
        reapplyAll()
    }

    private fun reapplyAll() {
        ApplicationManager.getApplication().invokeLater {
            EditorFactory.getInstance().allEditors
                .filter { it.project == project }
                .forEach { applyTo(it) }
        }
    }

    private fun applyTo(editor: Editor) {
        val path = repoRelativePath(editor) ?: run {
            EditorAnnotationsApplier.clearFor(editor)
            return
        }
        val annotations = annotationsByPath.get()[path].orEmpty()
        EditorAnnotationsApplier.applyToEditor(editor, annotations)
    }

    private fun repoRelativePath(editor: Editor): String? {
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val basePath = project.basePath?.let { File(it).absolutePath } ?: return null
        val absolute = File(vf.path).absolutePath
        if (!absolute.startsWith(basePath + File.separator)) return null
        return absolute.removePrefix(basePath + File.separator).replace(File.separatorChar, '/')
    }

    override fun dispose() {
        observerJob?.cancel()
        scope.cancel()
        // Leave the EditorFactoryListener — we registered it with `this` as the parent
        // disposable, so the platform tears it down for us.
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite to confirm no regression**

Run: `./gradlew --no-daemon test`
Expected: PASS — 161 tests, no regressions.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/example/ghactions/editor/ActiveAnnotationsService.kt
git commit -m "feat(editor): add ActiveAnnotationsService (per-project annotation registry)"
```

---

## Task 4: Wire `RunDetailPanel` to the service

**Files:**
- Modify: `src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt`

`showRun(run)` pushes the run id; `clear()` clears it. Two trivial calls.

- [ ] **Step 1: Read the current RunDetailPanel.kt**

Run: `cat src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt`

Find the existing `fun showRun(run: Run)` and `fun clear()`.

### Step 2: Add the service calls

In `showRun(run: Run)`, after the existing `artifactsPanel.showRun(run.id)` line, add:

```kotlin
project.getService(com.example.ghactions.editor.ActiveAnnotationsService::class.java)
    .setActiveRun(run.id)
```

In `clear()`, after the existing `artifactsPanel.clear()` line, add:

```kotlin
project.getService(com.example.ghactions.editor.ActiveAnnotationsService::class.java)
    .setActiveRun(null)
```

### Step 3: Verify compile

Run: `./gradlew --no-daemon compileKotlin`
Expected: BUILD SUCCESSFUL.

### Step 4: Run the full test suite

Run: `./gradlew --no-daemon test`
Expected: PASS — 161 tests, no regressions.

### Step 5: Commit

```bash
git add src/main/kotlin/com/example/ghactions/ui/RunDetailPanel.kt
git commit -m "feat(ui): push active run id to ActiveAnnotationsService"
```

---

## Task 5: Smoke test (deferred-okay) + final sweep + merge

**Files:** none.

- [ ] **Step 1: Manual smoke (deferred is fine)**

Run: `./gradlew --no-daemon runIde`

Verify:
- Open a project bound to a repo whose CI emits annotations (failure annotations from a checkstyle/lint workflow are easy to find).
- Click a run with known annotations in the GitHub Actions tool window.
- Open one of the annotated files in the editor. A severity icon should appear in the gutter at the start line of each annotation. Hover → tooltip shows title + message.
- Switch to a different run with different annotations — the gutter updates accordingly.
- Click *clear* (or select a PR with no run) — gutter icons disappear.
- Open a file that's not in the annotations — no icons (clean gutter).
- Close the file and reopen — icons re-appear (the EditorFactoryListener catches the new editor).

If any step fails, document in `docs/superpowers/notes/2026-04-30-annotation-gutter-smoke-deviations.md` and commit:

```bash
git add docs/superpowers/notes/
git commit -m "docs: smoke test deviations for plan 14"
```

If no deviations, skip the commit.

- [ ] **Step 2: Full test run**

Run: `./gradlew --no-daemon cleanTest test`
Expected: PASS — 161 tests.

- [ ] **Step 3: Build distribution**

Run: `./gradlew --no-daemon buildPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Plugin verifier**

Run: `./gradlew --no-daemon verifyPlugin`
Expected: PASSED. Existing 1 `SegmentedButton` warning is acceptable; `MarkupModel`, `EditorFactory`, `GutterIconRenderer` are stable APIs since 2018.

- [ ] **Step 5: Fast-forward merge**

```bash
git checkout main
git merge --ff-only feat/plan-14-annotation-gutter
git log --oneline | head -10
```

- [ ] **Step 6: Plan-level verification**

- All 5 tasks have green check-marks (Step 1 may be deferred).
- `./gradlew test` passes (161).
- `./gradlew buildPlugin` and `verifyPlugin` succeed.

---

## What ships at the end of Plan 14

- For the run currently displayed in `RunDetailPanel`, every annotation produces a gutter icon at its start line in the corresponding source file (when that file is open in the editor).
- Severity → icon: `FAILURE` red error · `WARNING` yellow triangle · `NOTICE` info · `UNKNOWN` neutral note.
- Hovering → tooltip shows the annotation title (when present) followed by the message.
- The icons follow the user's run selection: switching to another run replaces them; clearing the selection removes them.
- Newly-opened editors pick up the markers automatically via `EditorFactoryListener`.

What it does **not** yet do:
- SHA-mismatch warning (working copy may diverge from the run's commit).
- Click-the-icon to navigate or expand context.
- Problems-tool-window integration.

---

## Open questions / risks

1. **Path resolution.** We resolve via `<projectBasePath>/<annotation.path>`. Multi-module projects with non-base content roots may miss matches. Acceptable v1 trade-off — `AnnotationsPanel.openInEditor` (Plan 10) uses the same heuristic and works in practice.
2. **Highlighter lifecycle.** Each editor stores its highlighters in user data; `editorReleased` clears them. If the editor is disposed before our listener fires, the platform GCs them anyway — no leak.
3. **Daemon collisions.** We attach highlighters to the *editor's* markup model (per-editor), not the document's markup model (shared). The IntelliJ daemon writes to the document model; ours don't conflict. `HighlighterLayer.WARNING` keeps us above syntax but below the daemon's errors, so daemon-detected errors take precedence visually.
4. **Cross-project leakage.** `EditorFactoryListener.editorCreated` fires for *every* editor across all projects. We filter on `editor.project == project` to keep it scoped. The listener is registered with `this` (the project-scoped service) as parent disposable, so the platform unregisters it on project close.
