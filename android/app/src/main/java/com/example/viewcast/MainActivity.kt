package com.example.viewcast

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.viewcast.ui.components.QrCodeCanvas
import com.example.viewcast.ui.components.StatusCard
import com.example.viewcast.ui.theme.ViewCastTheme
import com.example.viewcast.ui.viewmodel.CastViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: CastViewModel by viewModels()

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            viewModel.startCast(this, res.resultCode, res.data!!)
            Toast.makeText(this, "Diffusion démarrée", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Autorisation refusée", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initWifi(this)
        requestPerms()

        setContent {
            ViewCastTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CastScreen(
                        viewModel = viewModel,
                        onRequestProjection = {
                            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                            projectionLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    )
                }
            }
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
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

    override fun onDestroy() {
        super.onDestroy()
        viewModel.cleanup(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastScreen(viewModel: CastViewModel, onRequestProjection: () -> Unit) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ip by viewModel.ipAddress.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val config = viewModel.config

    var showSettings by remember { mutableStateOf(false) }

    val rtspUrl = "rtsp://$ip:8554/stream"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ViewCast") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                ),
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "ViewCast",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "Diffusez votre écran en local",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            // Status
            val (statusIcon, statusTitle, statusSub) = when (uiState) {
                is CastViewModel.UiState.Idle -> Triple(Icons.Default.WifiOff, "Prêt", "Appuyez pour découvrir")
                is CastViewModel.UiState.Discovering -> Triple(Icons.Default.Search, "Recherche...", "Scan des appareils")
                is CastViewModel.UiState.Connecting -> Triple(Icons.Default.Sync, "Connexion...", "Établissement du lien")
                is CastViewModel.UiState.Connected -> Triple(Icons.Default.Wifi, "Connecté", "Wi-Fi Direct actif")
                is CastViewModel.UiState.Casting -> Triple(Icons.Default.Videocam, "En diffusion", "RTSP actif sur $ip")
                is CastViewModel.UiState.Error -> Triple(Icons.Default.Error, "Erreur", (uiState as CastViewModel.UiState.Error).message)
            }

            StatusCard(
                icon = statusIcon,
                title = statusTitle,
                subtitle = statusSub,
                isActive = uiState is CastViewModel.UiState.Casting || uiState is CastViewModel.UiState.Connected
            )

            // QR Code (visible si connecté ou casting)
            AnimatedVisibility(
                visible = uiState is CastViewModel.UiState.Connected || uiState is CastViewModel.UiState.Casting
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Scannez pour connecter",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        QrCodeCanvas(data = rtspUrl, size = 200)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            rtspUrl,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Peers list
            if (peers.isNotEmpty()) {
                Text("Appareils trouvés", style = MaterialTheme.typography.titleMedium)
                peers.forEach { peer ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { /* Connect logic */ }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(peer.deviceName.ifEmpty { "Inconnu" }, style = MaterialTheme.typography.bodyLarge)
                                Text(peer.deviceAddress, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Actions
            Spacer(modifier = Modifier.height(8.dp))

            if (uiState !is CastViewModel.UiState.Casting) {
                Button(
                    onClick = { viewModel.discover() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState !is CastViewModel.UiState.Discovering
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Découvrir appareils")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onRequestProjection,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Démarrer la diffusion")
                }
            } else {
                Button(
                    onClick = { viewModel.stopCast(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Arrêter la diffusion")
                }
            }
        }
    }

    // Settings Bottom Sheet
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Configuration", style = MaterialTheme.typography.headlineSmall)

                // Resolution
                Text("Résolution", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow {
                    listOf("720x480", "1280x720", "1920x1080").forEach { res ->
                        SegmentedButton(
                            selected = config.resolution == res,
                            onClick = { viewModel.updateResolution(res) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = listOf("720x480", "1280x720", "1920x1080").indexOf(res),
                                count = 3
                            )
                        ) { Text(res) }
                    }
                }

                // Bitrate
                Text("Bitrate: ${config.bitrateMbps.toInt()} Mbps", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = config.bitrateMbps,
                    onValueChange = { viewModel.updateBitrate(it) },
                    valueRange = 2f..10f,
                    steps = 7
                )

                // FPS
                Text("FPS: ${config.fps.toInt()}", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = config.fps,
                    onValueChange = { viewModel.updateFps(it) },
                    valueRange = 15f..60f,
                    steps = 8
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
