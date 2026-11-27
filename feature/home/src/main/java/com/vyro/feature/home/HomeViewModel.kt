package com.vyro.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vyro.core.common.EngineError
import com.vyro.core.common.EngineResult
import com.vyro.core.contracts.EngineBackendMode
import com.vyro.core.contracts.EngineSessionContract
import com.vyro.core.contracts.ProjectRepository
import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.ProjectSummary
import com.vyro.core.model.Rational
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val backendMode: EngineBackendMode? = null,
    val isCreating: Boolean = false,
    val isGridView: Boolean = true,
    val error: EngineError? = null,
)

class HomeViewModel(
    private val projectRepository: ProjectRepository,
    private val engineSession: EngineSessionContract,
) : ViewModel() {

    private val backendModeFlow = MutableStateFlow<EngineBackendMode?>(null)
    private val isCreatingFlow = MutableStateFlow(false)
    private val isGridViewFlow = MutableStateFlow(true)
    private val errorFlow = MutableStateFlow<EngineError?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        projectRepository.observeProjects(),
        backendModeFlow,
        isCreatingFlow,
        isGridViewFlow,
        errorFlow,
    ) { projects, mode, creating, isGrid, error ->
        HomeUiState(
            projects = projects,
            backendMode = mode,
            isCreating = creating,
            isGridView = isGrid,
            error = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    init {
        viewModelScope.launch {
            when (val initResult = engineSession.initialize()) {
                is EngineResult.Success -> backendModeFlow.value = initResult.value.backendMode
                is EngineResult.Failure -> errorFlow.value = initResult.error
            }
            runCatching { projectRepository.verifyAllProjectsHealth() }
        }
    }

    fun toggleDraftsViewMode() {
        isGridViewFlow.value = !isGridViewFlow.value
    }

    fun createProject(
        title: String,
        aspectPreset: AspectRatioPreset,
        frameRate: Rational,
        seedStarterTimeline: Boolean,
        onCreated: (String) -> Unit,
    ) {
        if (isCreatingFlow.value) return
        viewModelScope.launch {
            isCreatingFlow.value = true
            when (
                val result = projectRepository.createProject(
                    title = title,
                    aspectPreset = aspectPreset,
                    frameRate = frameRate,
                    seedStarterTimeline = seedStarterTimeline,
                )
            ) {
                is EngineResult.Success -> {
                    isCreatingFlow.value = false
                    onCreated(result.value.projectId)
                }
                is EngineResult.Failure -> {
                    isCreatingFlow.value = false
                    errorFlow.value = result.error
                }
            }
        }
    }

    fun createQuickProject(
        aspectPreset: AspectRatioPreset,
        onCreated: (String) -> Unit,
    ) {
        val label = when (aspectPreset) {
            AspectRatioPreset.LANDSCAPE_16_9 -> "Widescreen Cut"
            AspectRatioPreset.PORTRAIT_9_16 -> "Vertical Reel"
            AspectRatioPreset.SQUARE_1_1 -> "Square Edit"
        }
        createProject(
            title = label,
            aspectPreset = aspectPreset,
            frameRate = Rational.FPS_30,
            seedStarterTimeline = true,
            onCreated = onCreated,
        )
    }

    fun duplicateProject(projectId: String) {
        viewModelScope.launch {
            val result = projectRepository.duplicateProject(projectId)
            if (result is EngineResult.Failure) {
                errorFlow.value = result.error
            }
        }
    }

    fun renameProject(projectId: String, newTitle: String) {
        viewModelScope.launch {
            val result = projectRepository.renameProject(projectId, newTitle)
            if (result is EngineResult.Failure) {
                errorFlow.value = result.error
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            val result = projectRepository.deleteProject(projectId)
            if (result is EngineResult.Failure) {
                errorFlow.value = result.error
            }
        }
    }

    fun clearError() {
        errorFlow.value = null
    }

    class Factory(
        private val projectRepository: ProjectRepository,
        private val engineSession: EngineSessionContract,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(projectRepository, engineSession) as T
        }
    }
}
