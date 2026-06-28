package com.starrydream.nanoclick

import android.content.Intent
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starrydream.nanoclick.ui.theme.NanoclickTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val NeutralCardColor = Color(0xFFF6F7F8)
private val DialogBackgroundColor = Color.White
private val ClymNavy = Color(0xFF24445F)
private val ClymBlue = Color(0xFF8FC7F7)
private val ClymAccentBlue = Color(0xFF5EA9E6)
private const val SERVER_PRESET_TAG = "ServerPreset"
private const val PERMISSION_PREFS_NAME = "permission_onboarding"
private const val HAS_SHOWN_PERMISSION_ONBOARDING = "has_shown_permission_onboarding"

private enum class TimeMode {
    Device,
    Server
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NanoclickTheme {
                NanoClickScreen()
            }
        }
    }
}

@Composable
fun NanoClickScreen() {
    val context = LocalContext.current
    val presetRepository = remember { ServerTimePresetRepository(context.applicationContext) }
    val permissionPrefs = remember {
        context.getSharedPreferences(PERMISSION_PREFS_NAME, Context.MODE_PRIVATE)
    }
    var timeMode by remember { mutableStateOf(TimeMode.Device) }
    var selectedPresetId by remember { mutableStateOf<String?>(null) }
    var selectedPresetName by remember { mutableStateOf<String?>(null) }
    var syncSourceUrl by remember { mutableStateOf("") }
    var directSyncUrlDraft by remember { mutableStateOf("") }
    var lastSyncTimeMs by remember { mutableStateOf<Long?>(null) }
    var presets by remember { mutableStateOf<List<ServerTimePreset>>(emptyList()) }
    var isLoadingPresets by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var showDirectSyncDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val defaultExecutionTime = remember { calculateDefaultExecutionTime() }

    var hour by remember { mutableStateOf(defaultExecutionTime.hour) }
    var minute by remember { mutableStateOf(defaultExecutionTime.minute) }
    var second by remember { mutableStateOf(defaultExecutionTime.second) }
    var millisecond by remember { mutableStateOf(defaultExecutionTime.millisecond) }

    var isLoadingServerTime by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var serverTimeOffsetMs by remember { mutableStateOf<Long?>(null) }
    var roundTripTimeMs by remember { mutableLongStateOf(0L) }
    var currentDeviceTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var clickPosition by remember { mutableStateOf<ClickPosition?>(null) }
    var clickPositionMessage by remember { mutableStateOf<String?>(null) }
    var reservationMessage by remember { mutableStateOf<String?>(null) }
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    var handledCompletionId by remember { mutableLongStateOf(0L) }
    val coroutineScope = rememberCoroutineScope()
    val reservationState by NanoClickRuntimeState.reservationState.collectAsState()
    val isAccessibilityConnected by NanoClickRuntimeState.accessibilityConnected.collectAsState()
    val floatingClockState by FloatingClockRuntimeState.state.collectAsState()
    var currentElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val isReservationScheduled = reservationState.phase == ReservationPhase.Scheduled

    val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        reservationMessage = if (NanoClickAccessibilityService.isConnected()) {
            null
        } else {
            "CLYM 접근성 서비스를 켜주세요."
        }
    }

    fun startClickPositionOverlay() {
        context.startService(ClickPositionOverlayService.startEditingIntent(context))
        clickPositionMessage = "원형 포인터를 드래그해 누를 위치를 맞춘 뒤 적용을 눌러주세요."
        reservationMessage = null
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        if (hasOverlayPermission) {
            startClickPositionOverlay()
        } else {
            clickPositionMessage = "다른 앱 위에 표시 권한이 필요합니다. 권한을 허용한 뒤 다시 시도해주세요."
        }
    }

    var pendingFloatingClockMode by remember { mutableStateOf<FloatingClockMode?>(null) }

    fun startFloatingClock(mode: FloatingClockMode) {
        val offset = when (mode) {
            FloatingClockMode.Device -> 0L
            FloatingClockMode.Server -> serverTimeOffsetMs
        }
        if (offset == null) {
            errorMessage = "서버 시간을 먼저 불러와주세요."
            return
        }

        val intent = FloatingServerClockService.startIntent(
            context = context,
            mode = mode,
            serverOffsetMs = offset
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    val floatingClockPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        if (hasOverlayPermission) {
            pendingFloatingClockMode?.let { mode ->
                startFloatingClock(mode)
            }
        } else {
            errorMessage = "다른 앱 위에 표시 권한을 허용해야 플로팅 시계를 사용할 수 있습니다."
        }
        pendingFloatingClockMode = null
    }

    fun requestFloatingClockStart() {
        val mode = if (serverTimeOffsetMs != null) {
            FloatingClockMode.Server
        } else {
            FloatingClockMode.Device
        }
        FloatingClockRuntimeState.updateLatestServerUrl(syncSourceUrl)

        hasOverlayPermission = Settings.canDrawOverlays(context)
        if (hasOverlayPermission) {
            startFloatingClock(mode)
        } else {
            pendingFloatingClockMode = mode
            errorMessage = "다른 앱 위에 표시 권한을 허용해야 플로팅 시계를 사용할 수 있습니다."
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            floatingClockPermissionLauncher.launch(intent)
        }
    }

    fun resetFloatingClock() {
        context.startService(FloatingServerClockService.resetIntent(context))
    }

    fun switchToDeviceTimeMode() {
        if (isReservationScheduled || ScheduledClickService.isRunning()) {
            reservationMessage = "진행 중인 예약을 먼저 취소해주세요."
            return
        }

        timeMode = TimeMode.Device
        serverTimeOffsetMs = null
        selectedPresetId = null
        selectedPresetName = null
        syncSourceUrl = ""
        roundTripTimeMs = 0L
        lastSyncTimeMs = null
        errorMessage = null

        if (floatingClockState.isRunning && floatingClockState.mode == FloatingClockMode.Server) {
            FloatingClockRuntimeState.updateLatestServerUrl("")
            startFloatingClock(FloatingClockMode.Device)
        }
    }

    fun syncServerTime(sourceName: String, sourceId: String?, sourceUrl: String) {
        val normalizedUrl = runCatching { normalizeUrl(sourceUrl) }
            .onFailure { throwable ->
                errorMessage = throwable.message ?: "주소를 확인해주세요."
            }
            .getOrNull() ?: return

        coroutineScope.launch {
            isLoadingServerTime = true
            errorMessage = null
            reservationMessage = null

            fetchServerTime(normalizedUrl)
                .onSuccess { serverTime ->
                    timeMode = TimeMode.Server
                    selectedPresetId = sourceId
                    selectedPresetName = sourceName
                    syncSourceUrl = normalizedUrl
                    serverTimeOffsetMs = serverTime.offsetMs
                    roundTripTimeMs = serverTime.roundTripTimeMs
                    lastSyncTimeMs = System.currentTimeMillis()
                    FloatingClockRuntimeState.updateLatestServerUrl(normalizedUrl)
                    if (
                        floatingClockState.isRunning &&
                        floatingClockState.mode == FloatingClockMode.Server
                    ) {
                        context.startService(
                            FloatingServerClockService.updateOffsetIntent(
                                context,
                                serverTime.offsetMs
                            )
                        )
                    }
                }
                .onFailure { throwable ->
                    errorMessage = throwable.message ?: "서버 시간 동기화에 실패했습니다."
                }

            isLoadingServerTime = false
        }
    }

    fun openPresetDialog() {
        coroutineScope.launch {
            isLoadingPresets = true
            errorMessage = null
            showPresetDialog = true
            Log.d(SERVER_PRESET_TAG, "open dialog, current compose presets=${presets.size}")
            val result = presetRepository.loadPresets()
            presets = result.presets
            Log.d(
                SERVER_PRESET_TAG,
                "compose state assigned count=${presets.size}, fallback=${result.fromFallback}, ids=${presets.joinToString { "${it.id}/${it.name}" }}"
            )
            if (result.presets.isEmpty()) {
                errorMessage = "사용 가능한 프리셋이 없습니다. 직접 설정을 사용해주세요."
            }
            isLoadingPresets = false
        }
    }

    fun pasteDirectSyncUrl() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(
                ClipDescription.MIMETYPE_TEXT_PLAIN
            ) == true
        ) {
            directSyncUrlDraft = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        }
    }

    fun requestClickPosition() {
        if (isReservationScheduled) {
            clickPositionMessage = "예약 중에는 클릭 위치를 변경할 수 없습니다."
            return
        }
        clickPosition = null
        reservationMessage = null
        hasOverlayPermission = Settings.canDrawOverlays(context)
        if (hasOverlayPermission) {
            startClickPositionOverlay()
        } else {
            clickPositionMessage = "다른 앱 위에 표시 권한을 허용해야 위치를 지정할 수 있습니다."
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    fun clearClickPosition() {
        if (isReservationScheduled) {
            clickPositionMessage = "예약 중에는 클릭 위치를 초기화할 수 없습니다."
            return
        }
        clickPosition = null
        reservationMessage = null
        context.startService(ClickPositionOverlayService.clearIntent(context))
        clickPositionMessage = "클릭 위치가 초기화되었습니다."
    }

    fun openAccessibilitySettings() {
        accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }

    fun cancelReservation() {
        context.startService(ScheduledClickService.cancelIntent(context))
    }

    fun startReservation() {
        val scheduleOffset = if (timeMode == TimeMode.Server && serverTimeOffsetMs != null) {
            serverTimeOffsetMs ?: 0L
        } else {
            0L
        }
        val position = clickPosition
        reservationMessage = null

        when {
            position == null -> {
                reservationMessage = "클릭 위치를 먼저 지정해주세요."
                return
            }
            !isAccessibilityConnected || !NanoClickAccessibilityService.isConnected() -> {
                reservationMessage = "CLYM 접근성 서비스를 켜주세요."
                showAccessibilityDialog = true
                return
            }
            isReservationScheduled || ScheduledClickService.isRunning() -> {
                reservationMessage = "이미 예약 대기 중입니다."
                return
            }
        }

        val target = calculateScheduleTarget(
            hour = hour,
            minute = minute,
            second = second,
            millisecond = millisecond,
            deviceNowMs = currentDeviceTimeMs,
            serverOffsetMs = scheduleOffset,
            elapsedNowMs = SystemClock.elapsedRealtime()
        ).getOrElse { throwable ->
            reservationMessage = throwable.message ?: "실행 시각을 확인해주세요."
            return
        }

        val intent = ScheduledClickService.startIntent(
            context = context,
            x = position.x,
            y = position.y,
            targetElapsedMs = target.targetElapsedMs,
            scheduledLabel = target.label
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        reservationMessage = "${
            if (scheduleOffset == 0L) "기기 시간" else "서버 시간"
        } 기준으로 예약합니다. 실행 시각까지 화면을 켜두고 잠금을 해제한 상태로 유지해주세요."
    }

    DisposableEffect(Unit) {
        ClickPositionOverlayResult.onApplied = { position ->
            clickPosition = position
            clickPositionMessage = null
            reservationMessage = null
        }
        ClickPositionOverlayResult.onCancelled = {
            clickPositionMessage = if (clickPosition == null) {
                "위치 지정이 취소되었습니다. 좌표가 저장되지 않았습니다."
            } else {
                "위치 지정이 취소되었습니다."
            }
        }

        onDispose {
            ClickPositionOverlayResult.onApplied = null
            ClickPositionOverlayResult.onCancelled = null
        }
    }

    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                NanoClickRuntimeState.setAccessibilityConnected(
                    NanoClickAccessibilityService.isConnected()
                )
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        Log.d(SERVER_PRESET_TAG, "initial preset load started")
        val result = presetRepository.loadPresets()
        presets = result.presets
        Log.d(
            SERVER_PRESET_TAG,
            "initial compose state assigned count=${presets.size}, fallback=${result.fromFallback}, ids=${presets.joinToString { "${it.id}/${it.name}" }}"
        )
    }

    LaunchedEffect(Unit) {
        val hasShownPermissionOnboarding = permissionPrefs.getBoolean(
            HAS_SHOWN_PERMISSION_ONBOARDING,
            false
        )
        val isOverlayReady = Settings.canDrawOverlays(context)
        val isAccessibilityReady = NanoClickAccessibilityService.isConnected()
        if (!hasShownPermissionOnboarding && (!isOverlayReady || !isAccessibilityReady)) {
            permissionPrefs.edit()
                .putBoolean(HAS_SHOWN_PERMISSION_ONBOARDING, true)
                .apply()
            hasOverlayPermission = isOverlayReady
            NanoClickRuntimeState.setAccessibilityConnected(isAccessibilityReady)
            showPermissionDialog = true
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentDeviceTimeMs = System.currentTimeMillis()
            currentElapsedMs = SystemClock.elapsedRealtime()
            delay(30L)
        }
    }

    LaunchedEffect(reservationState.completionId) {
        if (reservationState.completionId > 0L && reservationState.completionId != handledCompletionId) {
            handledCompletionId = reservationState.completionId
            clickPosition = null
            reservationMessage = reservationState.message
        }
    }

    val serverTimeMs = serverTimeOffsetMs?.let { currentDeviceTimeMs + it }
    val serverTimeText = serverTimeMs?.let { formatTime(it) } ?: "연결 전"
    val deviceTimeText = formatTime(currentDeviceTimeMs)
    val timeDiffText = serverTimeOffsetMs?.let { formatTimeDifference(it) } ?: "-"
    val communicationTimeText = if (roundTripTimeMs > 0L) "$roundTripTimeMs ms" else "-"
    val remainingTimeText = reservationState.targetElapsedMs
        ?.let { formatReadableDuration((it - currentElapsedMs).coerceAtLeast(0L)) }
        ?: "-"

    if (showAccessibilityDialog) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDialog = false },
            title = { Text("접근성 권한이 필요합니다") },
            text = {
                Text("예약된 시각에 지정 위치를 한 번 터치하기 위해 CLYM 접근성 서비스를 켜주세요.")
            },
            confirmButton = {
                DialogPrimaryButton(
                    text = "설정 열기",
                    onClick = {
                        showAccessibilityDialog = false
                        openAccessibilitySettings()
                    }
                )
            },
            dismissButton = {
                DialogMenuButton(
                    text = "취소",
                    onClick = { showAccessibilityDialog = false }
                )
            },
            containerColor = DialogBackgroundColor
        )
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("권한 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (hasOverlayPermission && isAccessibilityConnected) {
                            "사용 준비 완료"
                        } else {
                            "필요한 권한을 확인해주세요."
                        },
                        fontWeight = FontWeight.Bold
                    )
                    PermissionItem(
                        title = "다른 앱 위에 표시",
                        status = if (hasOverlayPermission) "허용됨" else "설정 필요",
                        description = "플로팅 시계와 클릭 위치 표시를 위해 필요합니다.",
                        needsAttention = !hasOverlayPermission,
                        onClick = { openOverlaySettings() }
                    )
                    PermissionItem(
                        title = "접근성 서비스",
                        status = if (isAccessibilityConnected) "켜짐" else "설정 필요",
                        description = "예약된 시각에 지정 위치를 한 번 터치하기 위해 필요합니다.",
                        needsAttention = !isAccessibilityConnected,
                        onClick = { openAccessibilitySettings() }
                    )
                }
            },
            confirmButton = {
                DialogCloseButton(onClick = { showPermissionDialog = false })
            },
            containerColor = DialogBackgroundColor
        )
    }

    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            title = { Text("서버 선택") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogMenuButton(
                        text = "기기 시간",
                        onClick = {
                            showPresetDialog = false
                            switchToDeviceTimeMode()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isLoadingPresets) {
                        Text("목록을 불러오는 중...")
                    } else if (presets.isEmpty()) {
                        Text("사용 가능한 프리셋이 없습니다.")
                    } else {
                        presets.forEach { preset ->
                            DialogMenuButton(
                                text = preset.name,
                                onClick = {
                                    showPresetDialog = false
                                    syncServerTime(preset.name, preset.id, preset.syncUrl)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFFDDE2E6))
                    )
                    DialogMenuButton(
                        text = "직접 설정",
                        onClick = {
                            showPresetDialog = false
                            directSyncUrlDraft = syncSourceUrl
                            showDirectSyncDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                DialogCloseButton(onClick = { showPresetDialog = false })
            },
            containerColor = DialogBackgroundColor
        )
    }

    if (showDirectSyncDialog) {
        AlertDialog(
            onDismissRequest = { showDirectSyncDialog = false },
            title = { Text("서버 주소 직접 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = directSyncUrlDraft,
                        onValueChange = { directSyncUrlDraft = it },
                        label = { Text("서버 시간 동기화 주소") },
                        placeholder = { Text("https://...") },
                        singleLine = true
                    )
                    DialogMenuButton(
                        text = "붙여넣기",
                        onClick = { pasteDirectSyncUrl() }
                    )
                }
            },
            confirmButton = {
                DialogPrimaryButton(
                    text = "동기화",
                    onClick = {
                        val trimmed = directSyncUrlDraft.trim()
                        if (trimmed.isEmpty()) {
                            errorMessage = "주소를 입력해주세요."
                        } else {
                            showDirectSyncDialog = false
                            syncServerTime("직접 설정", "custom", trimmed)
                        }
                    }
                )
            },
            dismissButton = {
                DialogMenuButton(
                    text = "취소",
                    onClick = { showDirectSyncDialog = false }
                )
            },
            containerColor = DialogBackgroundColor
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.White
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.main_title),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "서버 시간 기준 예약 클릭",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    onClick = { showPermissionDialog = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(
                        modifier = Modifier.size(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⚙",
                            color = Color(0xFF24445F),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (!hasOverlayPermission || !isAccessibilityConnected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 3.dp)
                                    .size(8.dp)
                                    .background(Color(0xFFE53935), CircleShape)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NeutralCardColor)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp)
                ) {
                    if (timeMode == TimeMode.Server && serverTimeMs != null) {
                        TimeCardHeader(
                            title = "서버 시간",
                            chipText = "${selectedPresetName ?: "서버"} ▼",
                            onChipClick = { openPresetDialog() },
                            action = {
                                IconButton(
                                    onClick = {
                                        val name = selectedPresetName ?: "직접 설정"
                                        syncServerTime(name, selectedPresetId, syncSourceUrl)
                                    },
                                    enabled = !isLoadingServerTime && !isReservationScheduled && syncSourceUrl.isNotBlank(),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_refresh_24),
                                        contentDescription = "서버 시간 다시 동기화",
                                        tint = ClymNavy,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = serverTimeText,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "기기 $deviceTimeText · 서버 ${formatSignedOffset(serverTimeOffsetMs ?: 0L)}",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            text = "통신 $communicationTimeText · 마지막 ${lastSyncTimeMs?.let { formatTime(it) } ?: "-"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        TimeCardHeader(
                            title = "현재 시간",
                            chipText = "기기 시간 ▼",
                            onChipClick = { openPresetDialog() },
                            action = {
                                Box(modifier = Modifier.size(44.dp))
                            }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = deviceTimeText,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    errorMessage?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (floatingClockState.isRunning) {
                                resetFloatingClock()
                            } else {
                                requestFloatingClockStart()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (floatingClockState.isRunning) "플로팅 시계 초기화" else "플로팅 시계 켜기")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NeutralCardColor)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp)
                ) {
                    Text(
                        text = "실행 시각",
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TimeInput(
                            value = hour,
                            onValueChange = {
                                hour = it
                                reservationMessage = null
                            },
                            label = "",
                            maxLength = 2,
                            enabled = !isReservationScheduled,
                            modifier = Modifier.width(58.dp)
                                               .height(48.dp)
                        )
                        TimeSeparator(":")
                        TimeInput(
                            value = minute,
                            onValueChange = {
                                minute = it
                                reservationMessage = null
                            },
                            label = "",
                            maxLength = 2,
                            enabled = !isReservationScheduled,
                            modifier = Modifier.width(58.dp)
                                               .height(48.dp)
                        )
                        TimeSeparator(":")
                        TimeInput(
                            value = second,
                            onValueChange = {
                                second = it
                                reservationMessage = null
                            },
                            label = "",
                            maxLength = 2,
                            enabled = !isReservationScheduled,
                            modifier = Modifier.width(58.dp)
                                               .height(48.dp)
                        )
                        TimeSeparator(".")
                        TimeInput(
                            value = millisecond,
                            onValueChange = {
                                millisecond = it
                                reservationMessage = null
                            },
                            label = "",
                            maxLength = 3,
                            enabled = !isReservationScheduled,
                            modifier = Modifier.width(78.dp)
                                               .height(48.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "실행 예정: $hour:$minute:$second.$millisecond",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NeutralCardColor)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp)
                ) {
                    Text(
                        text = if (clickPosition == null) "클릭 위치 (미지정)" else "클릭 위치 (지정 완료)",
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val savedClickPosition = clickPosition
                    if (savedClickPosition != null) {
                        Text(
                            text = "X: ${savedClickPosition.x}, Y: ${savedClickPosition.y}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = "예약된 위치를 한 번 터치합니다.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    clickPositionMessage?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (clickPosition == null) {
                        Button(
                            onClick = { requestClickPosition() },
                            enabled = !isReservationScheduled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("클릭 위치 지정")
                        }
                    } else {
                        Button(
                            onClick = { clearClickPosition() },
                            enabled = !isReservationScheduled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("초기화")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isReservationScheduled || reservationMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NeutralCardColor)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        if (isReservationScheduled) {
                            Text(
                                text = "예약 대기 중",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "실행 예정: ${reservationState.scheduledTimeLabel ?: "-"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "남은 시간: $remainingTimeText",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "지정 좌표: X ${reservationState.x ?: "-"}, Y ${reservationState.y ?: "-"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "접근성 서비스: ${if (isAccessibilityConnected) "연결됨" else "꺼짐"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        reservationMessage?.let { message ->
                            if (isReservationScheduled) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            Button(
                onClick = {
                    if (isReservationScheduled) {
                        cancelReservation()
                    } else {
                        startReservation()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    when {
                        isReservationScheduled -> "예약 취소"
                        else -> "예약 시작"
                    }
                )
            }
        }
    }
}

@Composable
private fun DialogMenuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ClymAccentBlue),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = DialogBackgroundColor,
            contentColor = ClymNavy
        )
    ) {
        Text(text)
    }
}

@Composable
private fun DialogPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ClymBlue,
            contentColor = Color.White
        )
    ) {
        Text(text)
    }
}

@Composable
private fun DialogCloseButton(
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        DialogPrimaryButton(
            text = "닫기",
            onClick = onClick,
            modifier = Modifier.width(132.dp)
        )
    }
}

@Composable
private fun PermissionItem(
    title: String,
    status: String,
    description: String,
    needsAttention: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NeutralCardColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = ClymNavy,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = status,
                    color = if (needsAttention) MaterialTheme.colorScheme.error else ClymAccentBlue,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (needsAttention) {
                DialogMenuButton(
                    text = "설정 열기",
                    onClick = onClick
                )
            }
        }
    }
}

@Composable
private fun TimeCardHeader(
    title: String,
    chipText: String,
    onChipClick: () -> Unit,
    action: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                color = ClymNavy,
                fontWeight = FontWeight.Bold
            )
            TimeSourceChip(
                text = chipText,
                onClick = onChipClick
            )
        }
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center
        ) {
            action()
        }
    }
}

@Composable
private fun TimeSourceChip(
    text: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .height(34.dp)
            .widthIn(max = 136.dp)
            .semantics { contentDescription = "서버 선택" },
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, ClymAccentBlue),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = ClymNavy
        ),
        contentPadding = ButtonDefaults.ContentPadding
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TimeSeparator(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 3.dp),
        color = ClymNavy,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun TimeInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    maxLength: Int,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            if (
                newValue.length <= maxLength &&
                newValue.all { it.isDigit() }
            ) {
                onValueChange(newValue)
            }
        },
        modifier = modifier,
        enabled = enabled,
        label = if (label.isBlank()) null else ({ Text(label) }),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number
        )
    )
}

internal data class ServerTimeResult(
    val offsetMs: Long,
    val roundTripTimeMs: Long
)

internal suspend fun fetchServerTime(inputUrl: String): Result<ServerTimeResult> =
    withContext(Dispatchers.IO) {
        runCatching {
            val normalizedUrl = normalizeUrl(inputUrl)
            val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                useCaches = false
            }

            try {
                val requestStartElapsedMs = SystemClock.elapsedRealtime()
                val responseCode = connection.responseCode
                val responseEndElapsedMs = SystemClock.elapsedRealtime()
                val responseEndDeviceTimeMs = System.currentTimeMillis()
                val roundTripTimeMs = responseEndElapsedMs - requestStartElapsedMs

                if (responseCode !in 200..399) {
                    throw IllegalStateException("HTTP 오류가 발생했습니다. 코드: $responseCode")
                }

                val dateHeaderMs = connection.getHeaderFieldDate("Date", -1L)
                if (dateHeaderMs <= 0L) {
                    throw IllegalStateException("응답에 Date 헤더가 없습니다.")
                }

                val adjustedServerTimeAtResponseMs = dateHeaderMs + (roundTripTimeMs / 2L)
                ServerTimeResult(
                    offsetMs = adjustedServerTimeAtResponseMs - responseEndDeviceTimeMs,
                    roundTripTimeMs = roundTripTimeMs
                )
            } finally {
                connection.disconnect()
            }
        }
    }

internal fun normalizeUrl(inputUrl: String): String {
    val trimmedUrl = inputUrl.trim()
    if (trimmedUrl.isEmpty()) {
        throw IllegalArgumentException("URL을 입력해주세요.")
    }

    return if (
        trimmedUrl.startsWith("http://", ignoreCase = true) ||
        trimmedUrl.startsWith("https://", ignoreCase = true)
    ) {
        trimmedUrl
    } else {
        "https://$trimmedUrl"
    }
}

private fun formatTime(timeMs: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA).format(Date(timeMs))

private fun formatSignedOffset(offsetMs: Long): String =
    if (offsetMs >= 0L) "+${offsetMs}ms" else "${offsetMs}ms"

private fun formatTimeDifference(offsetMs: Long): String {
    if (offsetMs == 0L) {
        return "서버와 기기 시간이 같습니다"
    }

    val readableDuration = formatReadableDuration(abs(offsetMs))
    return if (offsetMs > 0L) {
        "서버가 기기보다 $readableDuration 빠름"
    } else {
        "기기가 서버보다 $readableDuration 빠름"
    }
}

private fun formatReadableDuration(durationMs: Long): String =
    when {
        durationMs >= 60_000L -> {
            val minutes = durationMs / 60_000L
            val seconds = (durationMs % 60_000L) / 1_000L
            "${minutes}분 ${seconds}초"
        }
        durationMs >= 1_000L -> "${durationMs / 1_000L}초"
        else -> "$durationMs ms"
    }

internal data class ExecutionTimeParts(
    val hour: String,
    val minute: String,
    val second: String,
    val millisecond: String
)

internal fun calculateDefaultExecutionTime(
    now: LocalDateTime = LocalDateTime.now()
): ExecutionTimeParts {
    val candidate = now
        .withMinute(59)
        .withSecond(59)
        .withNano(960_000_000)
        .let { target ->
            if (target.isAfter(now)) target else target.plusHours(1)
        }

    return ExecutionTimeParts(
        hour = candidate.hour.toString().padStart(2, '0'),
        minute = candidate.minute.toString().padStart(2, '0'),
        second = candidate.second.toString().padStart(2, '0'),
        millisecond = (candidate.nano / 1_000_000).toString().padStart(3, '0')
    )
}

internal data class ScheduleTarget(
    val targetServerTimeMs: Long,
    val targetElapsedMs: Long,
    val label: String
)

internal fun calculateScheduleTarget(
    hour: String,
    minute: String,
    second: String,
    millisecond: String,
    deviceNowMs: Long,
    serverOffsetMs: Long,
    elapsedNowMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Result<ScheduleTarget> = runCatching {
    val parsedHour = parseTimePart(hour, 0..23)
    val parsedMinute = parseTimePart(minute, 0..59)
    val parsedSecond = parseTimePart(second, 0..59)
    val parsedMillisecond = parseTimePart(millisecond, 0..999)

    val serverNowMs = deviceNowMs + serverOffsetMs
    val serverNow = Instant.ofEpochMilli(serverNowMs).atZone(zoneId)
    val targetLocalTime = LocalTime.of(
        parsedHour,
        parsedMinute,
        parsedSecond,
        parsedMillisecond * 1_000_000
    )
    val targetServer = serverNow.toLocalDate().atTime(targetLocalTime).atZone(zoneId)
    val targetServerTimeMs = targetServer.toInstant().toEpochMilli()
    val delayMs = targetServerTimeMs - serverNowMs

    if (delayMs <= 0L) {
        throw IllegalArgumentException("이미 지난 시각입니다. 실행 시각을 다시 설정해주세요.")
    }

    ScheduleTarget(
        targetServerTimeMs = targetServerTimeMs,
        targetElapsedMs = elapsedNowMs + delayMs,
        label = targetServer.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.KOREA))
    )
}.recoverCatching { throwable ->
    if (throwable is IllegalArgumentException) {
        throw throwable
    } else {
        throw IllegalArgumentException("실행 시각을 확인해주세요.")
    }
}

private fun parseTimePart(value: String, range: IntRange): Int {
    val parsed = value.toIntOrNull()
        ?: throw IllegalArgumentException("실행 시각을 확인해주세요.")
    if (parsed !in range) {
        throw IllegalArgumentException("실행 시각을 확인해주세요.")
    }
    return parsed
}

@Preview(showBackground = true)
@Composable
fun NanoClickScreenPreview() {
    NanoclickTheme {
        NanoClickScreen()
    }
}
