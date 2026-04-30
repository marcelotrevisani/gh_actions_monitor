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
        setSize(Dimension(900, 600))
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
