package com.starrydream.nanoclick

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ServerTimePresetRepository(
    private val context: Context
) {
    suspend fun loadPresets(): PresetLoadResult =
        withContext(Dispatchers.IO) {
            val presets = loadPublicPresets()
            Log.d(TAG, "public preset count=${presets.size}, ids=${presets.joinToString { "${it.id}/${it.name}" }}")
            PresetLoadResult(presets, fromFallback = false)
        }

    private fun loadPublicPresets(): List<ServerTimePreset> {
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

    companion object {
        private const val ASSET_FILE_NAME = "server_time_presets.json"
        private const val TAG = "ServerPreset"
    }
}

internal data class PresetLoadResult(
    val presets: List<ServerTimePreset>,
    val fromFallback: Boolean
)
