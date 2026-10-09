package com.example.viewcast.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

class WiFiDirectHelper(private val context: Context) {

    private val manager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel =
        manager.initialize(context, context.mainLooper, null)

    val events = MutableSharedFlow<Event>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    sealed class Event {
        data object P2pEnabled : Event()
        data class Peers(val list: List<WifiP2pDevice>) : Event()
        data class Connected(val info: WifiP2pInfo) : Event()
        data object Disconnected : Event()
        data class Error(val msg: String) : Event()
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) tryEmit(Event.P2pEnabled)
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (!hasPerm()) return
                    manager.requestPeers(channel) { peers -> tryEmit(Event.Peers(peers.deviceList.toList())) }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = IntentCompat.getParcelableExtra(
                        intent, WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java
                    )
                    if (info != null && info.groupFormed) tryEmit(Event.Connected(info))
                    else tryEmit(Event.Disconnected)
                }
            }
        }
    }

    private fun tryEmit(e: Event) {
        events.tryEmit(e)
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Android 13+ : NEARBY_WIFI_DEVICES ; avant : localisation précise. */
    fun hasPerm(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        if (!hasPerm()) {
            tryEmit(Event.Error("permission Wi‑Fi Direct refusée"))
            return
        }
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                tryEmit(Event.Error("discoverPeers a échoué ($reason)"))
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        if (!hasPerm()) return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 7 // essaie de rester Group Owner (IP 192.168.49.1)
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                tryEmit(Event.Error("connexion échouée ($reason)"))
            }
        })
    }
}
