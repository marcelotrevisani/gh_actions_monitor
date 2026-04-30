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
