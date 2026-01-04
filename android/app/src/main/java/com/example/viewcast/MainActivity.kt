package com.example.viewcast

import android.app.Activity
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.viewcast.rtsp.ScreenCastService
import com.example.viewcast.wifi.WiFiDirectHelper
import kotlinx.coroutines.launch
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private lateinit var wifi: WiFiDirectHelper
    private val peers = mutableStateListOf<WifiP2pDevice>()
    private var status by mutableStateOf("Statut: prêt")
    private var url by mutableStateOf("URL: rtsp://${guessLocalIp()}:8554/stream")

    // Configuration options
    private var resolution by mutableStateOf("1280x720")
    private var bitrate by mutableStateOf(6_000_000f)
    private var fps by mutableStateOf(30f)

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val i = Intent(this, ScreenCastService::class.java).apply {
                putExtra("code", res.resultCode)
                putExtra("data", res.data)
                putExtra("width", resolution.split("x")[0].toInt())
                putExtra("height", resolution.split("x")[1].toInt())
                putExtra("bitrate", bitrate.toInt())
                putExtra("fps", fps.toInt())
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Toast.makeText(this, "Diffusion démarrée", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Autorisation capture refusée", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifi = WiFiDirectHelper(this)
        wifi.register()

        requestRuntimePermissions()

        lifecycleScope.launch {
            wifi.events.collect { e ->
                when (e) {
                    is WiFiDirectHelper.Event.P2pEnabled -> status = "Statut: Wi‑Fi Direct activé"
                    is WiFiDirectHelper.Event.Peers -> {
                        peers.clear()
                        peers.addAll(e.list)
                        status = "Pairs trouvés: ${peers.size}"
                    }
                    is WiFiDirectHelper.Event.Connected -> {
                        status = "Connecté en P2P"
                        val ip = e.info.groupOwnerAddress?.hostAddress ?: guessLocalIp()
                        url = "URL: rtsp://$ip:8554/stream"
                    }
                    is WiFiDirectHelper.Event.Disconnected -> {
                        status = "Déconnecté"
                    }
                    is WiFiDirectHelper.Event.Error -> {
                        status = "Erreur: ${e.msg}"
                    }
                }
            }
        }

        setContent {
            ViewCastApp()
        }
    }

    @Composable
    fun ViewCastApp() {
        val context = LocalContext.current
        MaterialTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text = status, style = MaterialTheme.typography.headlineSmall)

                // Configuration
                Text("Configuration", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Résolution:")
                    Spacer(modifier = Modifier.width(8.dp))
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        TextField(
                            value = resolution,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf("1280x720", "1920x1080", "720x480").forEach { res ->
                                DropdownMenuItem(text = { Text(res) }, onClick = {
                                    resolution = res
                                    expanded = false
                                })
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bitrate (Mbps): ${bitrate / 1_000_000}")
                    Slider(
                        value = bitrate,
                        onValueChange = { bitrate = it },
                        valueRange = 2_000_000f..10_000_000f,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("FPS: ${fps.toInt()}")
                    Slider(
                        value = fps,
                        onValueChange = { fps = it },
                        valueRange = 15f..60f,
                        modifier = Modifier.weight(1f)
                    )
                }

                Button(onClick = {
                    wifi.discoverPeers()
                    status = "Recherche en cours..."
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Découvrir appareils (Wi‑Fi Direct)")
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(peers) { peer ->
                        Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                            Text(
                                text = peer.deviceName.ifEmpty { peer.deviceAddress },
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                Button(onClick = {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Démarrer diffusion (RTSP)")
                }

                Text(text = url)
            }
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        perms += android.Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) {
            perms += android.Manifest.permission.NEARBY_WIFI_DEVICES
        }
        val toAsk = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (toAsk.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toAsk.toTypedArray(), 123)
        }
    }

    private fun guessLocalIp(): String {
        return try {
            val ips = NetworkInterface.getNetworkInterfaces().toList().flatMap { ni ->
                ni.inetAddresses.toList().map { it.hostAddress ?: "" }
            }.filter { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) && it != "127.0.0.1" }
            ips.firstOrNull() ?: "192.168.49.1"
        } catch (_: Exception) { "192.168.49.1" }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifi.unregister()
    }
}