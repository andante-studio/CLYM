package com.starrydream.nanoclick

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class ReservationPhase {
    Idle,
    Scheduled,
    Success,
    Failed
}

data class ReservationUiState(
    val phase: ReservationPhase = ReservationPhase.Idle,
    val message: String? = null,
    val scheduledTimeLabel: String? = null,
    val targetElapsedMs: Long? = null,
    val x: Int? = null,
    val y: Int? = null,
    val completionId: Long = 0L
)

object NanoClickRuntimeState {
    private val completionCounter = AtomicLong(0L)

    private val _reservationState = MutableStateFlow(ReservationUiState())
    val reservationState: StateFlow<ReservationUiState> = _reservationState

    private val _accessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected

    fun setAccessibilityConnected(connected: Boolean) {
        _accessibilityConnected.value = connected
    }

    fun markScheduled(
        scheduledTimeLabel: String,
        targetElapsedMs: Long,
        x: Int,
        y: Int
    ) {
        _reservationState.value = ReservationUiState(
            phase = ReservationPhase.Scheduled,
            message = "예약 대기 중",
            scheduledTimeLabel = scheduledTimeLabel,
            targetElapsedMs = targetElapsedMs,
            x = x,
            y = y
        )
    }

    fun markCancelled() {
        _reservationState.value = ReservationUiState(
            phase = ReservationPhase.Idle,
            message = "예약이 취소되었습니다."
        )
    }

    fun markSuccess(message: String) {
        _reservationState.value = ReservationUiState(
            phase = ReservationPhase.Success,
            message = message,
            completionId = completionCounter.incrementAndGet()
        )
    }

    fun markFailed(message: String) {
        _reservationState.value = ReservationUiState(
            phase = ReservationPhase.Failed,
            message = message,
            completionId = completionCounter.incrementAndGet()
        )
    }

    fun resetAfterResult() {
        _reservationState.update { current ->
            if (current.phase == ReservationPhase.Success || current.phase == ReservationPhase.Failed) {
                current.copy(phase = ReservationPhase.Idle)
            } else {
                current
            }
        }
    }
}
