package com.example.viewcast.ui

import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewcast.R
import kotlin.math.roundToInt

/** Coque de l'application : barre du haut, 4 onglets (comme les maquettes) et contenu. */
@Composable
fun ViewCastApp(s: AppState, a: Actions) {
    ViewCastTheme {
        val immersive = s.rxFullscreen && s.tab == 3 && s.rxActive
        Scaffold(
            containerColor = Vc.Surface,
            topBar = { if (!immersive) TopBar(s) },
            bottomBar = { if (!immersive) BottomBar(s) }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (s.tab) {
                    0 -> ConnectScreen(s, a)
                    1 -> DiscoverScreen(s, a)
                    2 -> CastScreen(s, a)
                    3 -> ReceiveScreen(s, a)
                    else -> SettingsScreen(s, a)
                }
            }
        }
    }
}

@Composable
private fun TopBar(s: AppState) {
    val subtitle = when (s.tab) {
        0 -> R.string.title_connect
        1 -> R.string.title_discover
        2 -> R.string.title_cast
        3 -> R.string.title_receive
        else -> R.string.title_settings
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Vc.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
            )
            Column {
                Text(stringResource(R.string.app_name), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Vc.OnSurface)
                Text(stringResource(subtitle), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Vc.Primary)
            }
        }
        if (s.casting) Pill(stringResource(R.string.live), tint = Vc.Tertiary)
    }
}

@Composable
private fun BottomBar(s: AppState) {
    data class Tab(val label: Int, val icon: ImageVector)
    val tabs = listOf(
        Tab(R.string.nav_connect, Icons.Filled.Cast),
        Tab(R.string.nav_discover, Icons.Filled.Search),
        Tab(R.string.nav_cast, Icons.Filled.ScreenShare),
        Tab(R.string.nav_receive, Icons.Filled.Tv),
        Tab(R.string.nav_settings, Icons.Filled.Settings)
    )
    NavigationBar(containerColor = Vc.SurfaceLow) {
        tabs.forEachIndexed { i, t ->
            NavigationBarItem(
                selected = s.tab == i,
                onClick = { s.tab = i },
                icon = { Icon(t.icon, contentDescription = null) },
                label = { Text(stringResource(t.label), fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Vc.OnPrimary,
                    selectedTextColor = Vc.Primary,
                    indicatorColor = Vc.PrimaryContainer,
                    unselectedIconColor = Vc.OnSurfaceVariant,
                    unselectedTextColor = Vc.OnSurfaceVariant
                )
            )
        }
    }
}

// ───────────────────────────── Connexion ─────────────────────────────

@Composable
fun ConnectScreen(s: AppState, a: Actions) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Statut réseau
        VcCard(color = Vc.SurfaceLow) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusDot(if (s.ips.isEmpty()) Vc.Error else Vc.Tertiary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.net_status), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, tint = Vc.Tertiary, modifier = Modifier.size(15.dp))
                        Text(
                            s.ips.firstOrNull() ?: stringResource(R.string.net_none),
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface
                        )
                    }
                }
                Pill(
                    stringResource(if (s.ips.isEmpty()) R.string.net_offline else R.string.net_ready),
                    Icons.Filled.Bolt, if (s.ips.isEmpty()) Vc.Error else Vc.Tertiary
                )
            }
        }

        // Carte principale
        VcCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Pill(stringResource(if (s.casting) R.string.hero_badge_live else R.string.hero_badge_idle), Icons.Filled.WifiTethering)
                Icon(Icons.Filled.Devices, contentDescription = null, tint = Vc.Primary, modifier = Modifier.size(22.dp))
            }
            Text(stringResource(R.string.hero_title), fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface)
            Muted(stringResource(R.string.hero_body))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Vc.SurfaceLow).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DeviceBubble(Icons.Filled.Smartphone, stringResource(R.string.this_phone), Vc.Primary)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(26.dp).clip(CircleShape).background(Vc.PrimaryContainer),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Cast, contentDescription = null, tint = Vc.OnPrimary, modifier = Modifier.size(15.dp)) }
                    Text(stringResource(R.string.link_label), fontSize = 11.sp, color = Vc.Secondary, modifier = Modifier.padding(top = 4.dp))
                }
                DeviceBubble(Icons.Filled.DesktopWindows, stringResource(R.string.pc_screen), Vc.Secondary)
            }
        }

        // Étapes (séquence réelle)
        VcCard(color = Vc.SurfaceLow) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.steps_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface)
                Text(stringResource(R.string.steps_count), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Vc.Primary)
            }
            Step(1, stringResource(R.string.step1_t), stringResource(R.string.step1_d))
            Step(2, stringResource(R.string.step2_t), stringResource(R.string.step2_d))
            Step(3, stringResource(R.string.step3_t), stringResource(R.string.step3_d))
        }

        // Code PIN (quand la protection est activée)
        if (s.secure) {
            VcCard {
                SectionTitle(Icons.Filled.Lock, stringResource(R.string.sec_pin_title))
                Text(
                    if (s.pin.isNotEmpty()) s.pinDisplay else "••• •••",
                    fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = if (s.pin.isNotEmpty()) Vc.Tertiary else Vc.Outline,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Vc.SurfaceLow).padding(14.dp)
                )
                Muted(stringResource(if (s.pin.isNotEmpty()) R.string.sec_pin_hint else R.string.sec_pin_pending))
            }
        }

        // Adresse + démarrage
        VcCard {
            SectionTitle(Icons.Filled.Link, stringResource(R.string.address_title))
            Text(
                s.address ?: stringResource(R.string.net_none),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                color = if (s.address != null) Vc.Primary else Vc.Error,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Vc.SurfaceLow).padding(14.dp)
            )
            if (s.ips.size > 1) {
                Muted(stringResource(R.string.address_other))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (ip in s.ips) {
                        val on = ip == (s.chosenIp ?: s.ips.first())
                        Text(
                            ip, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            color = if (on) Vc.OnPrimary else Vc.OnSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (on) Vc.PrimaryContainer else Vc.SurfaceBright)
                                .clickable { s.chosenIp = ip }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SoftButton(stringResource(R.string.address_copy), Icons.Filled.ContentCopy, { a.copyAddress() }, enabled = s.address != null)
                Muted(stringResource(R.string.port_label, com.example.viewcast.rtsp.ScreenCastService.PORT))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Filled.Lock, contentDescription = null,
                    tint = if (s.secure) Vc.Tertiary else Vc.Secondary, modifier = Modifier.size(14.dp)
                )
                Text(
                    stringResource(if (s.secure) R.string.sec_secure_note else R.string.security_note),
                    fontSize = 12.sp, color = if (s.secure) Vc.Tertiary else Vc.Secondary
                )
            }
            if (s.casting) GradientButton(stringResource(R.string.stop_cast), Icons.Filled.Stop, { a.stopCast() }, danger = true)
            else GradientButton(stringResource(R.string.start_cast), Icons.Filled.Cast, { a.startCast() }, enabled = s.address != null)
        }
    }
}

@Composable
private fun DeviceBubble(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(Vc.SurfaceHighest), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        }
        Text(label, fontSize = 11.sp, color = Vc.OnSurface)
    }
}

@Composable
private fun Step(n: Int, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.size(26.dp).clip(CircleShape).background(Vc.SurfaceBright), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Vc.Primary)
        }
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface)
            Muted(description)
        }
    }
}

// ───────────────────────────── Découverte ─────────────────────────────

@Composable
fun DiscoverScreen(s: AppState, a: Actions) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VcCard(color = Vc.SurfaceLow) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.WifiTethering, contentDescription = null, tint = Vc.Tertiary, modifier = Modifier.size(26.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.p2p_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface)
                    Muted(s.p2pStatus.ifEmpty { stringResource(R.string.p2p_idle) })
                }
            }
        }

        VcCard {
            Text(
                stringResource(if (s.searching) R.string.p2p_searching else R.string.devices_title),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface
            )
            if (s.searching) Muted(stringResource(R.string.p2p_search_sub))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SoftButton(stringResource(R.string.refresh), Icons.Filled.Refresh, { a.discoverPeers() })
                Pill(stringResource(R.string.found_count, s.peers.size), Icons.Filled.Devices, Vc.Primary)
            }
        }

        if (s.peers.isEmpty()) {
            Muted(stringResource(R.string.no_devices), modifier = Modifier.padding(horizontal = 4.dp))
        }
        for (peer in s.peers) PeerCard(peer) { a.connectPeer(peer) }

        VcCard(color = Vc.SurfaceLow) {
            SectionTitle(Icons.Filled.Link, stringResource(R.string.manual_title))
            Muted(stringResource(R.string.manual_body))
            SoftButton(stringResource(R.string.manual_action), Icons.Filled.Cast, { s.tab = 0 })
        }
    }
}

@Composable
private fun PeerCard(peer: WifiP2pDevice, onConnect: () -> Unit) {
    val status = when (peer.status) {
        WifiP2pDevice.CONNECTED -> R.string.dev_connected
        WifiP2pDevice.INVITED -> R.string.dev_invited
        WifiP2pDevice.FAILED -> R.string.dev_failed
        WifiP2pDevice.AVAILABLE -> R.string.dev_available
        else -> R.string.dev_unavailable
    }
    VcCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.DesktopWindows, contentDescription = null, tint = Vc.Primary, modifier = Modifier.size(26.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.deviceName.ifEmpty { peer.deviceAddress }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface)
                Text(peer.deviceAddress, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Vc.OnSurfaceVariant)
            }
            Pill(stringResource(status), tint = if (peer.status == WifiP2pDevice.AVAILABLE) Vc.Tertiary else Vc.Secondary)
        }
        SoftButton(stringResource(R.string.connect_device), Icons.Filled.Cast, onConnect, enabled = peer.status == WifiP2pDevice.AVAILABLE)
    }
}

// ───────────────────────────── Diffusion ─────────────────────────────

@Composable
fun CastScreen(s: AppState, a: Actions) {
    var confirmStop by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!s.casting) {
            VcCard {
                Icon(Icons.Filled.ScreenShare, contentDescription = null, tint = Vc.Primary, modifier = Modifier.size(34.dp))
                Text(stringResource(R.string.idle_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface)
                Muted(stringResource(R.string.idle_body))
                GradientButton(stringResource(R.string.idle_action), Icons.Filled.Cast, { s.tab = 0 })
            }
            return@Column
        }

        VcCard {
            Pill(
                stringResource(R.string.live) + " · ${s.castHeight.coerceAtMost(s.castWidth)}p @ ${s.measuredFps} FPS",
                Icons.Filled.Cast, Vc.Tertiary
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.DesktopWindows, contentDescription = null, tint = Vc.Primary, modifier = Modifier.size(28.dp))
                Column {
                    Text(stringResource(R.string.clients_title), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurfaceVariant)
                    Text(
                        if (s.clients == 0) stringResource(R.string.clients_waiting) else stringResource(R.string.clients_count, s.clients),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface
                    )
                }
            }
            s.address?.let {
                Text(it, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Vc.Primary)
            }
            if (s.secure && s.pin.isNotEmpty()) {
                Text(
                    stringResource(R.string.cast_pin_line, s.pinDisplay),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Vc.Tertiary
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Icons.Filled.Speed, stringResource(R.string.metric_bitrate), "%.1f".format(s.mbps), stringResource(R.string.unit_mbps), Modifier.weight(1f))
            StatTile(Icons.Filled.AspectRatio, stringResource(R.string.metric_res), "${s.castWidth}×${s.castHeight}", "", Modifier.weight(1f))
            StatTile(
                Icons.Filled.GraphicEq, stringResource(R.string.metric_audio),
                stringResource(
                    when {
                        !s.audioActive -> R.string.audio_off
                        s.audioMuted -> R.string.audio_muted
                        else -> R.string.audio_stereo
                    }
                ), "", Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(
                stringResource(if (s.audioMuted) R.string.btn_sound_off else R.string.btn_sound_on),
                if (s.audioMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                { a.toggleSound() }, enabled = s.audioActive
            )
            SoftButton(stringResource(R.string.btn_stream_settings), Icons.Filled.Tune, { s.tab = 4 })
        }

        VcCard(color = Vc.SurfaceLow) {
            val resLabel = s.quality
            val mode = stringResource(if (s.lowLatency) R.string.mode_latency else R.string.mode_quality)
            Text(mode, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface)
            Muted(stringResource(R.string.mode_summary, resLabel, s.fps, s.bitrateMbps))
        }

        GradientButton(stringResource(R.string.stop_cast), Icons.Filled.Stop, { confirmStop = true }, danger = true)
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            containerColor = Vc.SurfaceContainer,
            title = { Text(stringResource(R.string.stop_title), color = Vc.OnSurface) },
            text = { Text(stringResource(R.string.stop_body), color = Vc.OnSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; a.stopCast() }) {
                    Text(stringResource(R.string.stop_now), color = Vc.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.keep_casting), color = Vc.Primary) }
            }
        )
    }
}

// ───────────────────────────── Paramètres ─────────────────────────────

@Composable
fun SettingsScreen(s: AppState, a: Actions) {
    val locked = s.casting
    var dialog by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val fr = s.language != "en"

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (locked) Muted(stringResource(R.string.set_locked), modifier = Modifier.padding(horizontal = 4.dp))

        VcCard {
            SectionTitle(Icons.Filled.Tune, stringResource(R.string.set_quality))
            Muted(stringResource(R.string.set_resolution))
            Segmented(
                listOf(
                    "480p" to stringResource(R.string.res_480),
                    "720p" to stringResource(R.string.res_720),
                    "1080p" to stringResource(R.string.res_1080)
                ), s.quality, { s.updateQuality(it) }, !locked
            )
            Muted(stringResource(R.string.set_fps))
            Segmented(
                listOf("30" to stringResource(R.string.fps_30), "60" to stringResource(R.string.fps_60)),
                s.fps.toString(), { s.updateFps(it.toInt()) }, !locked
            )
            var bitrate by remember(s.bitrateMbps) { mutableStateOf(s.bitrateMbps.toFloat()) }
            Muted(stringResource(R.string.set_bitrate, bitrate.roundToInt()))
            Slider(
                value = bitrate, onValueChange = { bitrate = it },
                onValueChangeFinished = { s.updateBitrate(bitrate.roundToInt()) },
                valueRange = 2f..30f, enabled = !locked,
                colors = SliderDefaults.colors(
                    thumbColor = Vc.Primary, activeTrackColor = Vc.PrimaryContainer, inactiveTrackColor = Vc.SurfaceHighest
                )
            )
            Muted(stringResource(R.string.set_mode))
            Segmented(
                listOf("low" to stringResource(R.string.mode_latency), "quality" to stringResource(R.string.mode_quality)),
                if (s.lowLatency) "low" else "quality", { s.updateLowLatency(it == "low") }, !locked
            )
            Muted(stringResource(if (s.lowLatency) R.string.mode_latency_d else R.string.mode_quality_d))
        }

        VcCard {
            SectionTitle(Icons.Filled.VolumeUp, stringResource(R.string.set_audio))
            ToggleRow(
                Icons.Filled.GraphicEq, stringResource(R.string.audio_switch), stringResource(R.string.audio_switch_d),
                s.audioEnabled && Build.VERSION.SDK_INT >= 29, { s.updateAudio(it) },
                enabled = !locked && Build.VERSION.SDK_INT >= 29
            )
            if (Build.VERSION.SDK_INT < 29) Muted(stringResource(R.string.audio_old_android))
        }

        VcCard {
            SectionTitle(Icons.Filled.Lock, stringResource(R.string.sec_title))
            ToggleRow(
                Icons.Filled.Lock, stringResource(R.string.sec_switch), stringResource(R.string.sec_switch_d),
                s.secure, { s.updateSecure(it) }, enabled = !locked
            )
            if (!s.secure) Text(stringResource(R.string.sec_open_warning), fontSize = 12.sp, color = Vc.Error)
        }

        VcCard {
            SectionTitle(Icons.Filled.Language, stringResource(R.string.set_lang))
            Segmented(
                listOf("fr" to stringResource(R.string.lang_fr), "en" to stringResource(R.string.lang_en)),
                s.language, { a.setLanguage(it) }
            )
        }

        VcCard {
            SectionTitle(Icons.Filled.Info, stringResource(R.string.set_about))
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.4.0"
            }
            Text(stringResource(R.string.about_version, version), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface)
            Muted(stringResource(R.string.about_rights))
            Muted(stringResource(R.string.about_contact))
            SoftButton(stringResource(R.string.btn_mail), Icons.Filled.Email, { a.sendMail() })
            SoftButton(stringResource(R.string.btn_privacy), Icons.Filled.Lock, { dialog = "privacy" })
            SoftButton(stringResource(R.string.btn_terms), Icons.Filled.Info, { dialog = "terms" })
            SoftButton(stringResource(R.string.btn_faq), Icons.Filled.Help, { dialog = "faq" })
        }
    }

    dialog?.let { kind ->
        val (title, body) = when (kind) {
            "privacy" -> stringResource(R.string.btn_privacy) to rawText(context, if (fr) R.raw.privacy_fr else R.raw.privacy_en)
            "terms" -> stringResource(R.string.btn_terms) to rawText(context, if (fr) R.raw.terms_fr else R.raw.terms_en)
            else -> stringResource(R.string.faq_title) to stringResource(R.string.faq_body)
        }
        AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = Vc.SurfaceContainer,
            title = { Text(title, color = Vc.OnSurface, fontSize = 18.sp) },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = Vc.OnSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.btn_close), color = Vc.Primary) } }
        )
    }
}

private fun rawText(context: android.content.Context, resId: Int): String =
    runCatching { context.resources.openRawResource(resId).bufferedReader().use { it.readText() } }.getOrDefault("")


// ───────────────────────────── Réception ─────────────────────────────

@Composable
fun ReceiveScreen(s: AppState, a: Actions) {
    if (s.rxActive) ReceivePlayer(s, a) else ReceiveForm(s, a)
}

@Composable
private fun ReceiveForm(s: AppState, a: Actions) {
    val message = when (s.rxState) {
        "invalid_addr" -> R.string.rx_err_addr
        "invalid_pin" -> R.string.rx_err_pin
        "unreachable" -> R.string.rx_err_unreachable
        "auth" -> R.string.rx_err_auth
        "locked" -> R.string.rx_err_locked
        "protocol" -> R.string.rx_err_protocol
        "dropped" -> R.string.rx_err_dropped
        else -> null
    }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Vc.PrimaryContainer, unfocusedBorderColor = Vc.OutlineVariant,
        focusedTextColor = Vc.OnSurface, unfocusedTextColor = Vc.OnSurface, cursorColor = Vc.Primary,
        focusedLabelColor = Vc.Primary, unfocusedLabelColor = Vc.OnSurfaceVariant
    )
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VcCard {
            Icon(Icons.Filled.Tv, contentDescription = null, tint = Vc.Primary, modifier = Modifier.size(30.dp))
            Text(stringResource(R.string.rx_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface)
            Muted(stringResource(R.string.rx_body))
        }
        VcCard {
            OutlinedTextField(
                value = s.rxAddress, onValueChange = { s.updateRxAddress(it) }, singleLine = true,
                label = { Text(stringResource(R.string.rx_addr)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = colors, modifier = Modifier.fillMaxWidth()
            )
            Muted(stringResource(R.string.rx_addr_hint))
            OutlinedTextField(
                value = s.rxPin, onValueChange = { v -> s.rxPin = v.filter { it.isDigit() }.take(6) }, singleLine = true,
                label = { Text(stringResource(R.string.rx_pin)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = colors, modifier = Modifier.fillMaxWidth()
            )
            Muted(stringResource(R.string.rx_pin_hint))
            if (message != null) Text(stringResource(message), fontSize = 13.sp, color = Vc.Error)
            GradientButton(stringResource(R.string.rx_connect), Icons.Filled.Tv, { a.connectReceive() }, enabled = !s.casting)
            if (s.casting) Muted(stringResource(R.string.rx_busy_casting))
        }
    }
}

@Composable
private fun ReceivePlayer(s: AppState, a: Actions) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val ratio = if (s.rxWidth > 0 && s.rxHeight > 0) s.rxWidth.toFloat() / s.rxHeight else 9f / 16f
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = a.setSurface(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) = a.setSurface(null)
                    })
                }
            },
            modifier = Modifier.aspectRatio(ratio)
        )
        // Couche transparente : un toucher affiche ou masque les commandes
        Box(
            modifier = Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { s.rxControls = !s.rxControls }
        )
        if (s.rxState != "playing") {
            Text(
                stringResource(if (s.rxState == "connecting") R.string.rx_connecting else R.string.rx_waiting),
                fontSize = 16.sp, color = Vc.OnSurface
            )
        }
        if (s.rxControls || s.rxState != "playing") {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC0E1320)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (s.rxState == "playing") {
                    Text(
                        stringResource(R.string.rx_stats, s.rxWidth, s.rxHeight, s.rxMbps, s.rxFps),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Vc.Tertiary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    SoftButton(
                        stringResource(if (s.rxMuted) R.string.rx_sound_off else R.string.rx_sound_on),
                        if (s.rxMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, { a.toggleReceiveSound() }
                    )
                    SoftButton(
                        stringResource(if (s.rxFullscreen) R.string.rx_exit_fullscreen else R.string.rx_fullscreen),
                        if (s.rxFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        { a.setReceiveFullscreen(!s.rxFullscreen) }
                    )
                    SoftButton(stringResource(R.string.rx_disconnect), Icons.Filled.Stop, { a.disconnectReceive() })
                }
                if (s.rxState == "playing") Muted(stringResource(R.string.rx_tap_hint))
            }
        }
    }
}
