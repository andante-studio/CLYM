package com.starrydream.nanoclick

import android.content.Intent
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
private const val PUBLIC_PREFS_NAME = "public_preferences"
private const val COUNTDOWN_SOUND_ENABLED = "countdown_sound_enabled"
private const val COUNTDOWN_SOUND_LEAD_TIME_MS = 11_000L

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
    val publicPrefs = remember {
        context.getSharedPreferences(PUBLIC_PREFS_NAME, Context.MODE_PRIVATE)
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
    var countdownTargetElapsedMs by remember { mutableStateOf<Long?>(null) }
    var countdownMessage by remember { mutableStateOf<String?>(null) }
    var isCountdownSoundEnabled by remember {
        mutableStateOf(publicPrefs.getBoolean(COUNTDOWN_SOUND_ENABLED, false))
    }
    var countdownMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isCountdownSoundScheduled by remember { mutableStateOf(false) }
    var hasPlayedCountdownSound by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val floatingClockState by FloatingClockRuntimeState.state.collectAsState()
    var currentElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val isCountdownRunning = countdownTargetElapsedMs != null

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

    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }

    fun releaseCountdownMediaPlayer() {
        countdownMediaPlayer?.runCatching {
            if (isPlaying) {
                stop()
            }
            release()
        }
        countdownMediaPlayer = null
    }

    fun clearCountdownSound() {
        isCountdownSoundScheduled = false
        hasPlayedCountdownSound = false
        releaseCountdownMediaPlayer()
    }

    fun playCountdownSound() {
        if (!isCountdownSoundScheduled || hasPlayedCountdownSound) {
            return
        }

        hasPlayedCountdownSound = true
        isCountdownSoundScheduled = false
        releaseCountdownMediaPlayer()
        countdownMediaPlayer = MediaPlayer.create(context, R.raw.clym_countdown_10s)?.apply {
            setOnCompletionListener { player ->
                player.release()
                if (countdownMediaPlayer === player) {
                    countdownMediaPlayer = null
                }
            }
            setOnErrorListener { player, _, _ ->
                player.release()
                if (countdownMediaPlayer === player) {
                    countdownMediaPlayer = null
                }
                true
            }
            start()
        }
    }

    fun toggleCountdown() {
        if (isCountdownRunning) {
            countdownTargetElapsedMs = null
            countdownMessage = null
            clearCountdownSound()
            return
        }

        val scheduleOffset = if (timeMode == TimeMode.Server && serverTimeOffsetMs != null) {
            serverTimeOffsetMs ?: 0L
        } else {
            0L
        }
        countdownMessage = null

        val target = calculateScheduleTarget(
            hour = hour,
            minute = minute,
            second = second,
            millisecond = millisecond,
            deviceNowMs = currentDeviceTimeMs,
            serverOffsetMs = scheduleOffset,
            elapsedNowMs = SystemClock.elapsedRealtime()
        ).getOrElse { throwable ->
            countdownMessage = throwable.message ?: "목표 시각을 확인해주세요."
            return
        }

        val remainingMs = target.targetElapsedMs - SystemClock.elapsedRealtime()
        clearCountdownSound()
        isCountdownSoundScheduled =
            isCountdownSoundEnabled && remainingMs >= COUNTDOWN_SOUND_LEAD_TIME_MS
        countdownTargetElapsedMs = target.targetElapsedMs
    }

    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasOverlayPermission = Settings.canDrawOverlays(context)
                }
                Lifecycle.Event.ON_STOP -> {
                    isCountdownSoundScheduled = false
                    releaseCountdownMediaPlayer()
                }
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            clearCountdownSound()
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
        while (true) {
            currentDeviceTimeMs = System.currentTimeMillis()
            currentElapsedMs = SystemClock.elapsedRealtime()
            val remainingMs = countdownTargetElapsedMs
                ?.minus(currentElapsedMs)
                ?.coerceAtLeast(0L)
            if (
                remainingMs != null &&
                remainingMs in 1L..COUNTDOWN_SOUND_LEAD_TIME_MS &&
                isCountdownSoundScheduled &&
                !hasPlayedCountdownSound
            ) {
                playCountdownSound()
            }
            if (
                countdownTargetElapsedMs != null &&
                currentElapsedMs >= (countdownTargetElapsedMs ?: Long.MAX_VALUE)
            ) {
                countdownTargetElapsedMs = null
                isCountdownSoundScheduled = false
            }
            delay(30L)
        }
    }

    val serverTimeMs = serverTimeOffsetMs?.let { currentDeviceTimeMs + it }
    val serverTimeText = serverTimeMs?.let { formatTime(it) } ?: "연결 전"
    val deviceTimeText = formatTime(currentDeviceTimeMs)
    val timeDiffText = serverTimeOffsetMs?.let { formatTimeDifference(it) } ?: "-"
    val communicationTimeText = if (roundTripTimeMs > 0L) "$roundTripTimeMs ms" else "-"
    val remainingTimeText = formatCountdown(
        countdownTargetElapsedMs
            ?.minus(currentElapsedMs)
            ?.coerceAtLeast(0L)
            ?: 0L
    )

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
                    text = "Mobile Millisecond Clock",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
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
                                    enabled = !isLoadingServerTime && syncSourceUrl.isNotBlank(),
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
                        text = "목표 시각",
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
                                countdownMessage = null
                            },
                            label = "",
                            maxLength = 2,
                            enabled = !isCountdownRunning,
                            modifier = Modifier.width(58.dp)
                                               .height(48.dp)
                        )
                        TimeSeparator(":")
                        TimeInput(
                            value = minute,
                            onValueChange = {
                                minute = it
                                countdownMessage = null
                            },
                            label = "",
                            maxLength = 2,
                            enabled = !isCountdownRunning,
                            modifier = Modifier.width(58.dp)
                                               .height(48.dp)
                        )
                        TimeSeparator(":")
                        TimeInput(
                            value = second,
                            onValueChange = {
                                second = it
                                countdownMessage = null
                            },
                            label = "",
                            maxLength = 2,
                            enabled = !isCountdownRunning,
                            modifier = Modifier.width(58.dp)
                                               .height(48.dp)
                        )
                        TimeSeparator(".")
                        TimeInput(
                            value = millisecond,
                            onValueChange = {
                                millisecond = it
                                countdownMessage = null
                            },
                            label = "",
                            maxLength = 3,
                            enabled = !isCountdownRunning,
                            modifier = Modifier.width(78.dp)
                                               .height(48.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "목표 시각: $hour:$minute:$second.$millisecond",
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
                        text = "카운트다운",
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = remainingTimeText,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )

                    countdownMessage?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = { toggleCountdown() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(if (isCountdownRunning) "카운트다운 취소" else "카운트다운 시작")
            }

            Spacer(modifier = Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NeutralCardColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "카운트다운 사운드",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "목표 시각 전에 카운트다운 사운드를 재생합니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = isCountdownSoundEnabled,
                        onCheckedChange = { enabled ->
                            isCountdownSoundEnabled = enabled
                            if (!enabled) {
                                clearCountdownSound()
                            } else {
                                val remainingMs = countdownTargetElapsedMs
                                    ?.minus(SystemClock.elapsedRealtime())
                                if (
                                    remainingMs != null &&
                                    remainingMs >= COUNTDOWN_SOUND_LEAD_TIME_MS &&
                                    !hasPlayedCountdownSound
                                ) {
                                    isCountdownSoundScheduled = true
                                }
                            }
                            publicPrefs.edit()
                                .putBoolean(COUNTDOWN_SOUND_ENABLED, enabled)
                                .apply()
                        }
                    )
                }
            }

            if (!hasOverlayPermission) {
                Spacer(modifier = Modifier.height(14.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NeutralCardColor)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "플로팅 권한이 필요합니다",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "플로팅 시계를 화면 위에 표시하려면 권한을 허용해주세요.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { openOverlaySettings() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("플로팅 권한 열기")
                        }
                    }
                }
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

private fun formatCountdown(durationMs: Long): String {
    val hours = durationMs / 3_600_000L
    val minutes = (durationMs % 3_600_000L) / 60_000L
    val seconds = (durationMs % 60_000L) / 1_000L
    val milliseconds = durationMs % 1_000L
    return "%02d:%02d:%02d.%03d".format(
        Locale.US,
        hours,
        minutes,
        seconds,
        milliseconds
    )
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
        throw IllegalArgumentException("이미 지난 시각입니다. 목표 시각을 다시 설정해주세요.")
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
        throw IllegalArgumentException("목표 시각을 확인해주세요.")
    }
}

private fun parseTimePart(value: String, range: IntRange): Int {
    val parsed = value.toIntOrNull()
        ?: throw IllegalArgumentException("목표 시각을 확인해주세요.")
    if (parsed !in range) {
        throw IllegalArgumentException("목표 시각을 확인해주세요.")
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
