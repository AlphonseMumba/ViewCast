package com.example.viewcast.rtsp

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import android.content.Context
import com.example.viewcast.CastStats
import com.example.viewcast.LocaleHelper
import com.example.viewcast.MainActivity
import com.example.viewcast.R
import com.example.viewcast.capture.AudioCapture
import com.example.viewcast.capture.ScreenCapture
import com.example.viewcast.net.NetUtils
import com.example.viewcast.security.PinGuard

/** Service de premier plan : capture d'écran + encodage H.264 + serveur RTSP. */
class ScreenCastService : Service() {

    private var capture: ScreenCapture? = null
    private var audio: AudioCapture? = null
    private var rtsp: RtspServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Démarrer en premier plan AVANT tout le reste : obligatoire (5 s max) et exigé avant
        // getMediaProjection() sur Android 14+, avec le type "mediaProjection".
        val ip = NetUtils.bestLocalIp()
        val text = if (ip != null) "rtsp://$ip:$PORT/stream" else getString(R.string.notif_text_no_ip, PORT)

        // Son : Android 10+, permission RECORD_AUDIO accordée et option activée. Le type "microphone"
        // n'est déclaré que dans ce cas (sinon Android 14 refuse de démarrer le service).
        val wantAudio = intent?.getBooleanExtra(EXTRA_AUDIO, true) ?: false
        val useAudio = wantAudio && Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        var fgsType = 0
        if (Build.VERSION.SDK_INT >= 29) fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 30 && useAudio) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), fgsType)

        val resultCode = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (intent == null || resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        releaseAll() // redémarrage : on repart de zéro

        val width = intent.getIntExtra(EXTRA_WIDTH, 720)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1280)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_000_000)
        val fps = intent.getIntExtra(EXTRA_FPS, 30)
        val lowLatency = intent.getBooleanExtra(EXTRA_LOW_LATENCY, true)
        CastStats.reset(width, height, fps)

        val server = RtspServer(PORT)
        val secure = intent.getBooleanExtra(EXTRA_SECURE, true)
        CastStats.secure = secure
        if (secure) {
            val code = PinGuard.newPin()
            server.pin = code
            CastStats.pin = code
            server.onPinChanged = { fresh -> CastStats.pin = fresh }
        }
        val cap = ScreenCapture(this, width, height, bitrate, fps, lowLatency)
        server.onKeyFrameRequested = { cap.requestKeyFrame() }
        cap.onParameterSets = { sps, pps -> server.setParameterSets(sps, pps) }
        cap.onFrame = { nals, ptsUs, key ->
            CastStats.framesEncoded.incrementAndGet()
            server.sendFrame(nals, ptsUs, key)
        }
        cap.onStopped = { mainHandler.post { stopSelf() } }
        rtsp = server
        capture = cap

        try {
            server.start()
            cap.start(resultCode, data)
            CastStats.running = true
            if (useAudio && Build.VERSION.SDK_INT >= 29) startAudio(cap, server)
        } catch (e: Exception) {
            Log.e(TAG, "Démarrage de la diffusion impossible", e)
            Toast.makeText(this, getString(R.string.toast_cast_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Le son est optionnel : en cas d'échec la diffusion continue, sans audio. */
    @RequiresApi(29)
    private fun startAudio(cap: ScreenCapture, server: RtspServer) {
        val projection = cap.projection ?: return
        try {
            val a = AudioCapture(projection)
            a.onConfig = { asc -> server.setAudioConfig(asc) }
            a.onFrame = { frame, ptsUs -> server.sendAudio(frame, ptsUs) }
            server.expectAudio(a.sampleRate, a.channels)
            a.start()
            audio = a
            CastStats.audioActive = true
        } catch (e: Exception) {
            Log.e(TAG, "Capture du son impossible", e)
            server.disableAudio()
            Toast.makeText(this, getString(R.string.toast_audio_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    private fun releaseAll() {
        CastStats.clear()
        runCatching { audio?.stop() }
        runCatching { capture?.stop() }
        runCatching { rtsp?.stop() }
        audio = null
        capture = null
        rtsp = null
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), immutable)
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCastService::class.java).setAction(ACTION_STOP), immutable
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PORT = 8554
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_LOW_LATENCY = "low_latency"
        const val EXTRA_SECURE = "secure"
        const val ACTION_STOP = "com.example.viewcast.STOP"
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ViewCast"
    }
}
