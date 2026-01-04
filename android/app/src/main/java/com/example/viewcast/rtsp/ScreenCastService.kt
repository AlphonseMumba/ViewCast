package com.example.viewcast.rtsp

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.viewcast.R
import com.example.viewcast.capture.ScreenCapture
import kotlinx.coroutines.*

class ScreenCastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var capture: ScreenCapture
    private lateinit var rtsp: RtspServer

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNoti()
        rtsp = RtspServer(scope, 8554)
        rtsp.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")!!
        val width = intent.getIntExtra("width", 1280)
        val height = intent.getIntExtra("height", 720)
        val bitrate = intent.getIntExtra("bitrate", 6_000_000)
        val fps = intent.getIntExtra("fps", 30)
        capture = ScreenCapture(this, scope, width = width, height = height, bitrate = bitrate, fps = fps)
        capture.onSpsPps = { sps, pps -> rtsp.updateSpsPps(sps, pps) }
        capture.onEncodedFrame = { buf, info ->
            val bytes = ByteArray(info.size)
            buf.get(bytes)
            // Assume each buffer is a complete NAL with start code; strip start code if needed.
            // For simplicity, send as-is.
            rtsp.sendH264Sample(bytes, (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0)
        }
        capture.startFromResult(resultCode, data)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.stop()
        rtsp.stop()
        scope.cancel()
    }

    private fun startForegroundNoti() {
        val channelId = "cast"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(channelId, "Screen Cast", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val noti = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Diffusion écran")
            .setContentText("RTSP en cours sur :8554")
            .build()
        startForeground(1, noti)
    }
}
