package com.example.viewcast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.viewcast.net.NetUtils
import com.example.viewcast.rtsp.ScreenCastService
import com.example.viewcast.wifi.WiFiDirectHelper
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var wifi: WiFiDirectHelper
    private val peers = mutableStateListOf<WifiP2pDevice>()

    private var status by mutableStateOf("Prêt")
    private var url by mutableStateOf("")
    private var casting by mutableStateOf(false)

    private var quality by mutableStateOf("720p")
    private var bitrateMbps by mutableStateOf(4f)
    private var fps by mutableStateOf(30f)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                val (w, h) = captureSize()
                val i = Intent(this, ScreenCastService::class.java).apply {
                    putExtra(ScreenCastService.EXTRA_CODE, res.resultCode)
                    putExtra(ScreenCastService.EXTRA_DATA, data)
                    putExtra(ScreenCastService.EXTRA_WIDTH, w)
                    putExtra(ScreenCastService.EXTRA_HEIGHT, h)
                    putExtra(ScreenCastService.EXTRA_BITRATE, (bitrateMbps * 1_000_000).toInt())
                    putExtra(ScreenCastService.EXTRA_FPS, fps.toInt())
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                casting = true
                url = streamUrl()
                status = "Diffusion en cours (${w}x$h)"
            } else {
                Toast.makeText(this, "Autorisation de capture refusée", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifi = WiFiDirectHelper(this)
        wifi.register()
        requestRuntimePermissions()
        url = streamUrl()

        lifecycleScope.launch {
            wifi.events.collect { e ->
                when (e) {
                    is WiFiDirectHelper.Event.P2pEnabled -> status = "Wi‑Fi Direct activé"
                    is WiFiDirectHelper.Event.Peers -> {
                        peers.clear()
                        peers.addAll(e.list)
                        status = "Appareils trouvés : ${peers.size}"
                    }
                    is WiFiDirectHelper.Event.Connected -> {
                        status = "Connecté en Wi‑Fi Direct"
                        // Si le téléphone est Group Owner, son IP est celle du groupe ; sinon c'est son IP locale.
                        val ip = if (e.info.isGroupOwner) e.info.groupOwnerAddress?.hostAddress else null
                        url = streamUrl(ip)
                    }
                    is WiFiDirectHelper.Event.Disconnected -> status = "Wi‑Fi Direct déconnecté"
                    is WiFiDirectHelper.Event.Error -> status = "Erreur : ${e.msg}"
                }
            }
        }

        setContent { ViewCastApp() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ViewCastApp() {
        MaterialTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = status, style = MaterialTheme.typography.headlineSmall)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Qualité :")
                    Spacer(modifier = Modifier.width(8.dp))
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        TextField(
                            value = quality,
                            onValueChange = {},
                            readOnly = true,
                            enabled = !casting,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf("480p", "720p", "1080p").forEach { q ->
                                DropdownMenuItem(text = { Text(q) }, onClick = {
                                    quality = q
                                    expanded = false
                                })
                            }
                        }
                    }
                }

                Text("Débit : ${bitrateMbps.roundToInt()} Mbit/s")
                Slider(
                    value = bitrateMbps,
                    onValueChange = { bitrateMbps = it },
                    valueRange = 2f..10f,
                    steps = 7,
                    enabled = !casting,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Images/s : ${fps.toInt()}")
                Slider(
                    value = fps,
                    onValueChange = { fps = it },
                    valueRange = 15f..60f,
                    steps = 8,
                    enabled = !casting,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (casting) {
                            stopService(Intent(this@MainActivity, ScreenCastService::class.java))
                            casting = false
                            status = "Diffusion arrêtée"
                        } else {
                            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            projectionLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (casting) "Arrêter la diffusion" else "Démarrer la diffusion (RTSP)")
                }

                Text("Adresse à saisir dans le viewer :", style = MaterialTheme.typography.labelLarge)
                Text(text = url, style = MaterialTheme.typography.titleMedium)

                Button(onClick = {
                    wifi.discoverPeers()
                    status = "Recherche d'appareils…"
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Wi‑Fi Direct : chercher des appareils")
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(peers) { peer ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp)
                                .clickable {
                                    status = "Connexion à ${peer.deviceName.ifEmpty { peer.deviceAddress }}…"
                                    wifi.connect(peer)
                                }
                        ) {
                            Text(
                                text = peer.deviceName.ifEmpty { peer.deviceAddress },
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    /** Taille de la vidéo : même rapport que l'écran (pas de déformation), dimensions paires, sans agrandir. */
    private fun captureSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        val shortSide = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val longSide = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val target = when (quality) {
            "480p" -> 480f
            "1080p" -> 1080f
            else -> 720f
        }
        val scale = minOf(1f, target / shortSide)
        val s = even(shortSide * scale)
        val l = even(longSide * scale)
        return if (landscape) l to s else s to l
    }

    private fun even(v: Float): Int = (v.roundToInt() / 2) * 2

    private fun streamUrl(ipOverride: String? = null): String {
        val ip = ipOverride ?: NetUtils.bestLocalIp()
        return if (ip != null) "rtsp://$ip:${ScreenCastService.PORT}/stream"
        else "Aucun réseau détecté : activez le Wi‑Fi ou le point d'accès"
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        wifi.unregister() // la diffusion continue en arrière-plan (service)
    }
}
