package com.starrydream.nanoclick

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FloatingClockUiState(
    val isRunning: Boolean = false,
    val isEditing: Boolean = false,
    val mode: FloatingClockMode? = null,
    val message: String? = null
)

object FloatingClockRuntimeState {
    private val _state = MutableStateFlow(FloatingClockUiState())
    val state: StateFlow<FloatingClockUiState> = _state
    @Volatile
    var latestServerUrl: String = ""
        private set

    fun updateLatestServerUrl(url: String) {
        latestServerUrl = url
    }

    fun markRunning(
        editing: Boolean,
        mode: FloatingClockMode,
        message: String? = null
    ) {
        _state.value = FloatingClockUiState(
            isRunning = true,
            isEditing = editing,
            mode = mode,
            message = message
        )
    }

    fun markStopped(message: String? = null) {
        _state.value = FloatingClockUiState(
            isRunning = false,
            isEditing = false,
            message = message
        )
    }
}
