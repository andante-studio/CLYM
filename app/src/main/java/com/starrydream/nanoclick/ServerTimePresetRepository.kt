package com.starrydream.nanoclick

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal class ServerTimePresetRepository(
    private val context: Context,
    private val remotePresetUrl: String = ServerPresetConfig.REMOTE_PRESET_URL
) {
    suspend fun loadPresets(): PresetLoadResult =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "remoteUrl='$remotePresetUrl', isBlank=${remotePresetUrl.isBlank()}")
            val remoteJson = runCatching {
                if (remotePresetUrl.isBlank()) {
                    Log.d(TAG, "remote skipped because url is blank")
                    null
                } else {
                    Log.d(TAG, "remote load started")
                    fetchRemoteJson(remotePresetUrl)
                }
            }.onFailure { throwable ->
                Log.e(TAG, "remote load failed", throwable)
            }.getOrNull()

            val remotePresets = remoteJson?.let { json ->
                Log.d(TAG, "remote json length=${json.length}")
                runCatching {
                    ServerTimePresetParser.parse(json) { index, throwable ->
                        Log.e(TAG, "remote preset item parse failed index=$index", throwable)
                    }
                }
                    .onFailure { throwable -> Log.e(TAG, "remote parse failed", throwable) }
                    .onSuccess { presets ->
                        Log.d(TAG, "remote active parsed count=${presets.size}")
                        Log.d(TAG, "remote final ids=${presets.joinToString { "${it.id}/${it.name}" }}")
                    }
                    .getOrNull()
            }.orEmpty()

            if (remotePresets.isNotEmpty()) {
                Log.d(TAG, "using remote presets count=${remotePresets.size}")
                PresetLoadResult(remotePresets, fromFallback = false)
            } else {
                Log.d(TAG, "enter local fallback")
                val fallback = loadFallbackPresets()
                Log.d(TAG, "final local fallback count=${fallback.size}, ids=${fallback.joinToString { "${it.id}/${it.name}" }}")
                PresetLoadResult(fallback, fromFallback = true)
            }
        }

    private fun loadFallbackPresets(): List<ServerTimePreset> {
        val json = runCatching {
            Log.d(TAG, "opening asset $ASSET_FILE_NAME")
            context.assets.open(ASSET_FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.onSuccess { json ->
            Log.d(TAG, "asset open success, json length=${json.length}")
        }.onFailure { throwable ->
            Log.e(TAG, "asset open failed", throwable)
        }.getOrNull() ?: return emptyList()

        return runCatching {
            ServerTimePresetParser.parse(json) { index, throwable ->
                Log.e(TAG, "asset preset item parse failed index=$index", throwable)
            }
        }
            .onSuccess { presets ->
                Log.d(TAG, "asset active parsed count=${presets.size}")
                Log.d(TAG, "asset final ids=${presets.joinToString { "${it.id}/${it.name}" }}")
            }
            .onFailure { throwable ->
                Log.e(TAG, "asset parse failed", throwable)
            }
            .getOrDefault(emptyList())
    }

    private fun fetchRemoteJson(remoteUrl: String): String {
        val connection = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            useCaches = false
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..399) {
                throw IllegalStateException("원격 프리셋 조회 실패: HTTP $code")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ASSET_FILE_NAME = "server_time_presets.json"
        private const val TAG = "ServerPreset"
    }
}

internal data class PresetLoadResult(
    val presets: List<ServerTimePreset>,
    val fromFallback: Boolean
)
