package com.example.viewcast.capture

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class ScreenCapture(
    private val context: Context,
    private val scope: CoroutineScope,
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = 6_000_000,
    private val fps: Int = 30,
    private val keyFrameIntervalSec: Int = 2
) {
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    var onEncodedFrame: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null
    var onSpsPps: ((ByteBuffer, ByteBuffer) -> Unit)? = null

    fun createCaptureIntent(): Intent {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }

    fun startFromResult(resultCode: Int, data: Intent) {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            if (Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface: Surface = codec!!.createInputSurface()
        codec!!.start()

        val metrics = context.resources.displayMetrics
        val densityDpi = metrics.densityDpi

        vDisplay = projection!!.createVirtualDisplay(
            "CastVD",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        scope.launch(Dispatchers.Default) {
            val bufferInfo = MediaCodec.BufferInfo()
            var sps: ByteBuffer? = null
            var pps: ByteBuffer? = null
            while (isActive) {
                val index = codec!!.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    index >= 0 -> {
                        val out = codec!!.getOutputBuffer(index) ?: continue
                        out.position(bufferInfo.offset)
                        out.limit(bufferInfo.offset + bufferInfo.size)

                        // Extract SPS/PPS from codec config if needed
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            val csd = out.slice()
                            // Very simple split: search for 00 00 00 01 start codes
                            val bytes = ByteArray(csd.remaining())
                            csd.get(bytes)
                            var splitIdx = -1
                            for (i in 4 until bytes.size - 4) {
                                if (bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() && bytes[i+2] == 0.toByte() && bytes[i+3] == 1.toByte()) {
                                    splitIdx = i; break
                                }
                            }
                            if (splitIdx > 0) {
                                sps = ByteBuffer.wrap(bytes, 0, splitIdx)
                                pps = ByteBuffer.wrap(bytes, splitIdx, bytes.size - splitIdx)
                                if (sps != null && pps != null) onSpsPps?.invoke(sps!!, pps!!)
                            }
                        } else {
                            onEncodedFrame?.invoke(out.slice(), bufferInfo)
                        }
                        codec!!.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }

    fun stop() {
        try { vDisplay?.release() } catch (_: Throwable) {}
        try { codec?.stop(); codec?.release() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
    }
}
