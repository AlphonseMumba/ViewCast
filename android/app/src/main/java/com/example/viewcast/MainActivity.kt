package com.example.viewcast

import android.app.Activity
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.viewcast.rtsp.ScreenCastService
import com.example.viewcast.wifi.WiFiDirectHelper
import kotlinx.coroutines.launch
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var wifi: WiFiDirectHelper
    private lateinit var listView: ListView
    private lateinit var btnDiscover: Button
    private lateinit var btnStartCast: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtUrl: TextView
    private val peers = mutableListOf<WifiP2pDevice>()

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val i = Intent(this, ScreenCastService::class.java).apply {
                putExtra("code", res.resultCode)
                putExtra("data", res.data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Toast.makeText(this, "Diffusion démarrée", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Autorisation capture refusée", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtUrl = findViewById(R.id.txtUrl)
        listView = findViewById(R.id.listPeers)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnStartCast = findViewById(R.id.btnStartCast)

        wifi = WiFiDirectHelper(this)
        wifi.register()

        // Demande des permissions à l’exécution si besoin
        requestRuntimePermissions()

        lifecycleScope.launch {
            wifi.events.collect { e ->
                when (e) {
                    is WiFiDirectHelper.Event.P2pEnabled -> txtStatus.text = "Statut: Wi‑Fi Direct activé"
                    is WiFiDirectHelper.Event.Peers -> {
                        peers.clear(); peers.addAll(e.list)
                        listView.adapter = ArrayAdapter(
                            this@MainActivity,
                            android.R.layout.simple_list_item_1,
                            peers.map { it.deviceName.ifEmpty { it.deviceAddress } }
                        )
                        txtStatus.text = "Pairs trouvés: ${peers.size}"
                    }
                    is WiFiDirectHelper.Event.Connected -> {
                        txtStatus.text = "Connecté en P2P"
                        txtUrl.text = "URL: rtsp://${guessLocalIp()}:8554/stream"
                        val ip = e.info.groupOwnerAddress?.hostAddress ?: guessLocalIp()
                        txtUrl.text = "URL: rtsp://$ip:8554/stream"
                    }
                    is WiFiDirectHelper.Event.Disconnected -> {
                        txtStatus.text = "Déconnecté"
                    }
                    is WiFiDirectHelper.Event.Error -> {
                        txtStatus.text = "Erreur: ${e.msg}"
                    }
                }
            }
        }

        btnDiscover.setOnClickListener {
            wifi.discoverPeers()
            txtStatus.text = "Recherche en cours..."
        }

        listView.setOnItemClickListener { _, _, pos, _ ->
            wifi.connect(peers[pos])
            txtStatus.text = "Connexion à ${peers[pos].deviceName}..."
        }

        btnStartCast.setOnClickListener {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }

        // Affiche une IP probable par défaut
        txtUrl.text = "URL: rtsp://${guessLocalIp()}:8554/stream"
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
        // Tente de récupérer une IP locale (Wi‑Fi/Direct). Sinon 192.168.49.1 par défaut (GO Wi‑Fi Direct)
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