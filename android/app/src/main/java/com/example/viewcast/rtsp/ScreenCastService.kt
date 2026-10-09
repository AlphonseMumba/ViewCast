package com.example.viewcast.rtsp

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.example.viewcast.MainActivity
import com.example.viewcast.R
import com.example.viewcast.capture.ScreenCapture
import com.example.viewcast.net.NetUtils

/** Service de premier plan : capture d'écran + encodage H.264 + serveur RTSP. */
class ScreenCastService : Service() {

    private var capture: ScreenCapture? = null
    private var rtsp: RtspServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Démarrer en premier plan AVANT tout le reste : obligatoire (5 s max) et exigé avant
        // getMediaProjection() sur Android 14+, avec le type "mediaProjection".
        val ip = NetUtils.bestLocalIp()
        val text = if (ip != null) "rtsp://$ip:$PORT/stream" else "RTSP sur le port $PORT"
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(text),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )

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

        val server = RtspServer(PORT)
        val cap = ScreenCapture(this, width, height, bitrate, fps)
        server.onKeyFrameRequested = { cap.requestKeyFrame() }
        cap.onParameterSets = { sps, pps -> server.setParameterSets(sps, pps) }
        cap.onFrame = { nals, ptsUs, key -> server.sendFrame(nals, ptsUs, key) }
        cap.onStopped = { mainHandler.post { stopSelf() } }
        rtsp = server
        capture = cap

        try {
            server.start()
            cap.start(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "Démarrage de la diffusion impossible", e)
            Toast.makeText(this, "Diffusion impossible : ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    private fun releaseAll() {
        runCatching { capture?.stop() }
        runCatching { rtsp?.stop() }
        capture = null
        rtsp = null
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Diffusion d'écran", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("ViewCast : diffusion de l'écran")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
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
        const val ACTION_STOP = "com.example.viewcast.STOP"
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ViewCast"
    }
}
