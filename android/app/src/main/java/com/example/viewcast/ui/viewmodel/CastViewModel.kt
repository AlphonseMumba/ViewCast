package com.example.viewcast.ui.viewmodel

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.viewcast.wifi.WiFiDirectHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CastViewModel : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        object Discovering : UiState()
        object Connecting : UiState()
        object Connected : UiState()
        object Casting : UiState()
        data class Error(val message: String) : UiState()
    }

    data class Config(
        val resolution: String = "1280x720",
        val bitrateMbps: Float = 6f,
        val fps: Float = 30f
    )

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _peers = MutableStateFlow<List<android.net.wifi.p2p.WifiP2pDevice>>(emptyList())
    val peers = _peers.asStateFlow()

    private val _ipAddress = MutableStateFlow("192.168.49.1")
    val ipAddress = _ipAddress.asStateFlow()

    var config by mutableStateOf(Config())
        private set

    private var wifiHelper: WiFiDirectHelper? = null

    fun initWifi(context: android.content.Context) {
        wifiHelper = WiFiDirectHelper(context).apply {
            register()
            viewModelScope.launch {
                events.collect { event ->
                    when (event) {
                        is WiFiDirectHelper.Event.P2pEnabled -> {
                            _uiState.value = UiState.Idle
                        }
                        is WiFiDirectHelper.Event.Peers -> {
                            _peers.value = event.list
                            _uiState.value = if (event.list.isEmpty()) UiState.Discovering else UiState.Connected
                        }
                        is WiFiDirectHelper.Event.Connected -> {
                            _ipAddress.value = event.info.groupOwnerAddress?.hostAddress ?: guessIp(context)
                            _uiState.value = UiState.Connected
                        }
                        is WiFiDirectHelper.Event.Disconnected -> {
                            _uiState.value = UiState.Idle
                        }
                        is WiFiDirectHelper.Event.Error -> {
                            _uiState.value = UiState.Error(event.msg)
                        }
                    }
                }
            }
        }
    }

    fun updateResolution(res: String) { config = config.copy(resolution = res) }
    fun updateBitrate(v: Float) { config = config.copy(bitrateMbps = v) }
    fun updateFps(v: Float) { config = config.copy(fps = v) }

    fun discover() {
        _uiState.value = UiState.Discovering
        wifiHelper?.discoverPeers()
    }

    fun startCast(context: Activity, resultCode: Int, data: Intent) {
        _uiState.value = UiState.Casting
        val (w, h) = config.resolution.split("x").map { it.toInt() }
        val intent = Intent(context, com.example.viewcast.rtsp.ScreenCastService::class.java).apply {
            putExtra("code", resultCode)
            putExtra("data", data)
            putExtra("width", w)
            putExtra("height", h)
            putExtra("bitrate", (config.bitrateMbps * 1_000_000).toInt())
            putExtra("fps", config.fps.toInt())
        }
        context.startForegroundService(intent)
    }

    fun stopCast(context: android.content.Context) {
        context.stopService(Intent(context, com.example.viewcast.rtsp.ScreenCastService::class.java))
        _uiState.value = UiState.Connected
    }

    fun cleanup(context: android.content.Context) {
        wifiHelper?.unregister()
    }

    private fun guessIp(context: android.content.Context): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { ni ->
                ni.inetAddresses.toList().map { it.hostAddress ?: "" }
            }.firstOrNull { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) && it != "127.0.0.1" }
        } catch (_: Exception) { "192.168.49.1" } ?: "192.168.49.1"
    }
}
