package com.example.viewcast

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.viewcast.net.NetUtils
import com.example.viewcast.receive.ReceiverSession
import com.example.viewcast.receive.RxAddress
import com.example.viewcast.security.PinGuard
import com.example.viewcast.rtsp.ScreenCastService
import com.example.viewcast.ui.Actions
import com.example.viewcast.ui.AppState
import com.example.viewcast.ui.ViewCastApp
import com.example.viewcast.wifi.WiFiDirectHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), Actions {

    private lateinit var prefs: Prefs
    private lateinit var state: AppState
    private lateinit var wifi: WiFiDirectHelper
    private var rx: ReceiverSession? = null
    private var currentSurface: Surface? = null

    private val wifiPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    // Les notifications (Android 13+) ne sont demandées qu'au lancement ; les autres permissions, au moment du besoin.
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val wifiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) discoverPeers()
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                state.updateAudio(false)
                Toast.makeText(this, R.string.audio_denied, Toast.LENGTH_LONG).show()
            }
            launchProjection()
        }

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
                    putExtra(ScreenCastService.EXTRA_BITRATE, state.bitrateMbps * 1_000_000)
                    putExtra(ScreenCastService.EXTRA_FPS, state.fps)
                    putExtra(ScreenCastService.EXTRA_LOW_LATENCY, state.lowLatency)
                    putExtra(ScreenCastService.EXTRA_AUDIO, state.audioEnabled)
                    putExtra(ScreenCastService.EXTRA_SECURE, state.secure)
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                state.casting = true
                state.tab = 2
            } else {
                Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        state = AppState(prefs)
        state.ips = NetUtils.localIpv4Addresses()
        state.casting = CastStats.running

        wifi = WiFiDirectHelper(this)
        wifi.register()

        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        lifecycleScope.launch {
            wifi.events.collect { e ->
                when (e) {
                    is WiFiDirectHelper.Event.P2pEnabled -> state.p2pStatus = getString(R.string.p2p_enabled)
                    is WiFiDirectHelper.Event.Peers -> {
                        state.peers.clear()
                        state.peers.addAll(e.list)
                        state.searching = false
                    }
                    is WiFiDirectHelper.Event.Connected -> {
                        state.p2pStatus = getString(R.string.p2p_connected)
                        // Si le téléphone est Group Owner, son IP est celle du groupe ; sinon c'est son IP locale.
                        if (e.info.isGroupOwner) state.chosenIp = e.info.groupOwnerAddress?.hostAddress
                    }
                    is WiFiDirectHelper.Event.Disconnected -> state.p2pStatus = getString(R.string.p2p_disconnected)
                    is WiFiDirectHelper.Event.Error -> {
                        state.searching = false
                        state.p2pStatus = getString(R.string.p2p_error, e.msg)
                    }
                }
            }
        }

        // Rafraîchit chaque seconde l'état de la diffusion (service), les mesures et les adresses réseau.
        lifecycleScope.launch {
            var lastBytes = 0L
            var lastFrames = 0L
            var lastRxBytes = 0L
            var lastRxFrames = 0L
            var lastT = SystemClock.elapsedRealtime()
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val dt = ((now - lastT) / 1000f).coerceAtLeast(0.1f)
                lastT = now
                state.casting = CastStats.running
                if (state.casting) {
                    val b = CastStats.bytesSent.get()
                    val f = CastStats.framesEncoded.get()
                    state.mbps = ((b - lastBytes) * 8f / 1_000_000f / dt).coerceAtLeast(0f)
                    state.measuredFps = ((f - lastFrames) / dt).roundToInt().coerceAtLeast(0)
                    lastBytes = b
                    lastFrames = f
                    state.clients = CastStats.clients
                    state.castWidth = CastStats.width
                    state.castHeight = CastStats.height
                    state.audioActive = CastStats.audioActive
                    state.audioMuted = CastStats.audioMuted
                    val pin = CastStats.pin
                    if (state.pin.isNotEmpty() && pin.isNotEmpty() && pin != state.pin) {
                        Toast.makeText(this@MainActivity, R.string.sec_rotated, Toast.LENGTH_LONG).show()
                    }
                    state.pin = pin
                } else {
                    state.pin = ""
                    lastBytes = 0L
                    lastFrames = 0L
                    state.mbps = 0f
                    state.measuredFps = 0
                    state.clients = 0
                }
                state.ips = NetUtils.localIpv4Addresses()

                // Réception : état, définition, débit et images/s mesurés
                val session = rx
                if (session != null) {
                    state.rxState = when (session.state) {
                        ReceiverSession.State.CONNECTING -> "connecting"
                        ReceiverSession.State.WAITING_VIDEO -> "waiting"
                        ReceiverSession.State.PLAYING -> "playing"
                        ReceiverSession.State.UNREACHABLE -> "unreachable"
                        ReceiverSession.State.AUTH -> "auth"
                        ReceiverSession.State.LOCKED -> "locked"
                        ReceiverSession.State.PROTOCOL -> "protocol"
                        ReceiverSession.State.DROPPED -> "dropped"
                        ReceiverSession.State.STOPPED -> "idle"
                    }
                    state.rxWidth = session.width
                    state.rxHeight = session.height
                    val rb = session.bytesReceived
                    val rf = session.framesShown.get()
                    state.rxMbps = ((rb - lastRxBytes) * 8f / 1_000_000f / dt).coerceAtLeast(0f)
                    state.rxFps = ((rf - lastRxFrames) / dt).roundToInt().coerceAtLeast(0)
                    lastRxBytes = rb
                    lastRxFrames = rf
                    if (!state.rxActive) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (state.rxFullscreen) setReceiveFullscreen(false)
                    }
                } else {
                    lastRxBytes = 0L
                    lastRxFrames = 0L
                }
                delay(1000)
            }
        }

        setContent { ViewCastApp(state, this@MainActivity) }
    }

    // ───────── Actions de l'interface ─────────

    override fun startCast() {
        val needAudioPermission = state.audioEnabled && Build.VERSION.SDK_INT >= 29 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needAudioPermission) audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        else launchProjection()
    }

    private fun launchProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun stopCast() {
        stopService(Intent(this, ScreenCastService::class.java))
        state.casting = false
        Toast.makeText(this, R.string.cast_stopped, Toast.LENGTH_SHORT).show()
    }

    override fun copyAddress() {
        val address = state.address ?: return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ViewCast", address))
        Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show()
    }

    override fun discoverPeers() {
        if (wifi.hasPerm()) {
            state.searching = true
            state.p2pStatus = getString(R.string.p2p_searching)
            wifi.discoverPeers()
        } else {
            wifiPermissionLauncher.launch(wifiPermission)
        }
    }

    override fun connectPeer(device: WifiP2pDevice) = wifi.connect(device)

    override fun setLanguage(code: String) {
        if (code == state.language) return
        prefs.language = code
        state.language = code
        recreate() // recharge toutes les chaînes dans la nouvelle langue
    }

    override fun toggleSound() {
        CastStats.audioMuted = !CastStats.audioMuted
        state.audioMuted = CastStats.audioMuted
    }

    override fun sendMail() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:mumbalpha@gmail.com"))
            .putExtra(Intent.EXTRA_SUBJECT, "ViewCast")
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "mumbalpha@gmail.com", Toast.LENGTH_LONG).show()
        }
    }

    // ───────── Réception (recevoir l'écran d'un autre appareil) ─────────

    override fun connectReceive() {
        if (state.casting) {
            Toast.makeText(this, R.string.rx_busy_casting, Toast.LENGTH_LONG).show()
            return
        }
        val target = RxAddress.parse(state.rxAddress)
        if (target == null) {
            state.rxState = "invalid_addr"
            return
        }
        val pin = state.rxPin.trim()
        if (pin.isNotEmpty() && !PinGuard.isValid(pin)) {
            state.rxState = "invalid_pin"
            return
        }
        rx?.stop()
        state.updateRxAddress(state.rxAddress.trim())
        state.rxMuted = false
        state.rxWidth = 0
        state.rxHeight = 0
        state.rxControls = true
        val session = ReceiverSession(target.first, target.second, pin.ifEmpty { null })
        rx = session
        currentSurface?.let { session.setSurface(it) }
        session.start()
        state.rxState = "connecting"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun disconnectReceive() {
        rx?.stop()
        rx = null
        state.rxState = "idle"
        state.rxPin = "" // le code n'est jamais conservé
        setReceiveFullscreen(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun setSurface(surface: Surface?) {
        currentSurface = surface
        rx?.setSurface(surface)
    }

    override fun toggleReceiveSound() {
        val session = rx ?: return
        session.muted = !session.muted
        state.rxMuted = session.muted
    }

    override fun setReceiveFullscreen(on: Boolean) {
        state.rxFullscreen = on
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Taille de la vidéo : même rapport que l'écran (pas de déformation), dimensions paires, sans agrandir. */
    private fun captureSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        val shortSide = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val longSide = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val target = when (state.quality) {
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

    override fun onDestroy() {
        super.onDestroy()
        rx?.stop() // la réception s'arrête avec l'application ; la diffusion, elle, continue (service)
        wifi.unregister()
    }
}
