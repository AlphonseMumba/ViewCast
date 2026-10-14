package com.example.viewcast.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.viewcast.Prefs
import com.example.viewcast.rtsp.ScreenCastService

/** État observable de l'interface (les réglages sont enregistrés dès qu'ils changent). */
class AppState(private val prefs: Prefs) {
    var tab by mutableStateOf(0)

    // Diffusion (alimenté par CastStats)
    var casting by mutableStateOf(false)
    var clients by mutableStateOf(0)
    var mbps by mutableStateOf(0f)
    var measuredFps by mutableStateOf(0)
    var castWidth by mutableStateOf(0)
    var castHeight by mutableStateOf(0)
    var audioActive by mutableStateOf(false)
    var audioMuted by mutableStateOf(false)
    var pin by mutableStateOf("")

    // Réseau
    var ips by mutableStateOf<List<String>>(emptyList())
    var chosenIp by mutableStateOf<String?>(null)

    // Wi-Fi Direct
    val peers = mutableStateListOf<WifiP2pDevice>()
    var p2pStatus by mutableStateOf("")
    var searching by mutableStateOf(false)

    // Réception (cet appareil reçoit l'écran d'un autre)
    var rxAddress by mutableStateOf(prefs.rxAddress)
    var rxPin by mutableStateOf("")
    var rxState by mutableStateOf("idle") // idle | invalid_addr | invalid_pin | connecting | waiting | playing | unreachable | auth | locked | protocol | dropped
    var rxWidth by mutableStateOf(0)
    var rxHeight by mutableStateOf(0)
    var rxMbps by mutableStateOf(0f)
    var rxFps by mutableStateOf(0)
    var rxMuted by mutableStateOf(false)
    var rxFullscreen by mutableStateOf(false)
    var rxControls by mutableStateOf(true)
    val rxActive: Boolean get() = rxState == "connecting" || rxState == "waiting" || rxState == "playing"

    // Réglages
    var language by mutableStateOf(prefs.language)
    var quality by mutableStateOf(prefs.quality)
    var fps by mutableStateOf(prefs.fps)
    var bitrateMbps by mutableStateOf(prefs.bitrateMbps)
    var lowLatency by mutableStateOf(prefs.lowLatency)
    var audioEnabled by mutableStateOf(prefs.audio)
    var secure by mutableStateOf(prefs.secure)

    fun updateRxAddress(v: String) { rxAddress = v; prefs.rxAddress = v }
    fun updateQuality(v: String) { quality = v; prefs.quality = v }
    fun updateFps(v: Int) { fps = v; prefs.fps = v }
    fun updateBitrate(v: Int) { bitrateMbps = v; prefs.bitrateMbps = v }
    fun updateLowLatency(v: Boolean) { lowLatency = v; prefs.lowLatency = v }
    fun updateAudio(v: Boolean) { audioEnabled = v; prefs.audio = v }
    fun updateSecure(v: Boolean) { secure = v; prefs.secure = v }

    /** Code affiché par groupes de 3 chiffres : « 482 913 ». */
    val pinDisplay: String get() = pin.chunked(3).joinToString(" ")

    /** Adresse à saisir dans le récepteur Windows. */
    val address: String?
        get() = (chosenIp ?: ips.firstOrNull())?.let { "rtsp://$it:${ScreenCastService.PORT}/stream" }
}

/** Actions déclenchées par l'interface (implémentées par l'activité). */
interface Actions {
    fun startCast()
    fun stopCast()
    fun copyAddress()
    fun discoverPeers()
    fun connectPeer(device: WifiP2pDevice)
    fun setLanguage(code: String)
    fun toggleSound()
    fun sendMail()
    fun connectReceive()
    fun disconnectReceive()
    fun setSurface(surface: android.view.Surface?)
    fun toggleReceiveSound()
    fun setReceiveFullscreen(on: Boolean)
}
