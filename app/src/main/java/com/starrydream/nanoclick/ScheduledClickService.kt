package com.starrydream.nanoclick

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ScheduledClickService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var waitJob: Job? = null
    private val didExecute = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelReservation()
                return START_NOT_STICKY
            }
            ACTION_START -> startReservation(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        waitJob?.cancel()
        reservationRunning.set(false)
        super.onDestroy()
    }

    private fun startReservation(intent: Intent) {
        if (!reservationRunning.compareAndSet(false, true)) {
            return
        }

        val x = intent.getIntExtra(EXTRA_X, -1)
        val y = intent.getIntExtra(EXTRA_Y, -1)
        val targetElapsedMs = intent.getLongExtra(EXTRA_TARGET_ELAPSED_MS, -1L)
        val scheduledLabel = intent.getStringExtra(EXTRA_SCHEDULED_LABEL) ?: "--:--:--.---"

        if (x < 0 || y < 0 || targetElapsedMs <= SystemClock.elapsedRealtime()) {
            finishWithFailure("예약 정보를 확인해주세요.")
            return
        }

        if (!isCoordinateInsideScreen(x, y)) {
            finishWithFailure("지정 좌표가 화면 범위를 벗어났습니다.")
            return
        }

        NanoClickRuntimeState.markScheduled(
            scheduledTimeLabel = scheduledLabel,
            targetElapsedMs = targetElapsedMs,
            x = x,
            y = y
        )
        startForeground(NOTIFICATION_ID, buildNotification(scheduledLabel))

        waitJob = serviceScope.launch {
            waitUntilTarget(targetElapsedMs)
            executeOnce(x, y)
        }
    }

    private suspend fun waitUntilTarget(targetElapsedMs: Long) {
        // Android scheduling is not a hard real-time system. The final short wait
        // uses elapsedRealtime, but system load and power state can still add jitter.
        while (true) {
            val remainingMs = targetElapsedMs - SystemClock.elapsedRealtime()
            when {
                remainingMs <= 0L -> return
                remainingMs > 1_000L -> delay((remainingMs - 700L).coerceAtMost(5_000L))
                remainingMs > 100L -> delay(20L)
                remainingMs > 10L -> delay(2L)
                else -> {
                    while (SystemClock.elapsedRealtime() < targetElapsedMs) {
                        // Keep the spin window tiny to avoid long CPU burn.
                    }
                    return
                }
            }
        }
    }

    private fun executeOnce(x: Int, y: Int) {
        if (!didExecute.compareAndSet(false, true)) {
            return
        }

        if (!isScreenReadyForTap()) {
            finishWithFailure("화면이 꺼져 있거나 잠금 상태라 터치하지 않았습니다.")
            return
        }

        if (!NanoClickAccessibilityService.isConnected()) {
            finishWithFailure("접근성 서비스 연결이 끊어져 터치하지 않았습니다.")
            return
        }

        NanoClickAccessibilityService.tapOnce(x, y) { result ->
            when (result) {
                TapResult.Success -> finishWithSuccess()
                TapResult.Cancelled -> finishWithFailure("터치 제스처가 취소되었습니다.")
                TapResult.FailedToDispatch -> finishWithFailure("터치 제스처를 실행하지 못했습니다.")
                TapResult.ServiceUnavailable -> finishWithFailure("접근성 서비스가 연결되어 있지 않습니다.")
            }
        }
    }

    private fun cancelReservation() {
        waitJob?.cancel()
        reservationRunning.set(false)
        NanoClickRuntimeState.markCancelled()
        stopForegroundCompat()
        stopSelf()
    }

    private fun finishWithSuccess() {
        cleanupOverlayAndReservation()
        NanoClickRuntimeState.markSuccess("예약된 위치를 한 번 터치했습니다.")
        stopForegroundCompat()
        stopSelf()
    }

    private fun finishWithFailure(message: String) {
        cleanupOverlayAndReservation()
        NanoClickRuntimeState.markFailed(message)
        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanupOverlayAndReservation() {
        reservationRunning.set(false)
        startService(ClickPositionOverlayService.clearIntent(this))
    }

    private fun isScreenReadyForTap(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return powerManager.isInteractive && !keyguardManager.isKeyguardLocked
    }

    private fun isCoordinateInsideScreen(x: Int, y: Int): Boolean {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
        return x in 0 until point.x && y in 0 until point.y
    }

    private fun buildNotification(scheduledLabel: String): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("CLYM 예약 대기 중")
            .setContentText("실행 예정 $scheduledLabel")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "예약 취소", cancelIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CLYM 예약",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val ACTION_START = "com.starrydream.nanoclick.START_SCHEDULED_CLICK"
        private const val ACTION_CANCEL = "com.starrydream.nanoclick.CANCEL_SCHEDULED_CLICK"
        private const val EXTRA_X = "extra_x"
        private const val EXTRA_Y = "extra_y"
        private const val EXTRA_TARGET_ELAPSED_MS = "extra_target_elapsed_ms"
        private const val EXTRA_SCHEDULED_LABEL = "extra_scheduled_label"
        private const val CHANNEL_ID = "nanoclick_scheduled_click"
        private const val NOTIFICATION_ID = 20260617

        private val reservationRunning = AtomicBoolean(false)

        fun isRunning(): Boolean = reservationRunning.get()

        fun startIntent(
            context: Context,
            x: Int,
            y: Int,
            targetElapsedMs: Long,
            scheduledLabel: String
        ): Intent =
            Intent(context, ScheduledClickService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_X, x)
                putExtra(EXTRA_Y, y)
                putExtra(EXTRA_TARGET_ELAPSED_MS, targetElapsedMs)
                putExtra(EXTRA_SCHEDULED_LABEL, scheduledLabel)
            }

        fun cancelIntent(context: Context): Intent =
            Intent(context, ScheduledClickService::class.java).apply {
                action = ACTION_CANCEL
            }
    }
}
