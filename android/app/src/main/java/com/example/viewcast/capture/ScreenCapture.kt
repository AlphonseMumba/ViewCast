package com.example.viewcast.capture

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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.example.viewcast.rtsp.H264
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Capture d'écran (MediaProjection) + encodage H.264 matériel.
 * Les NAL units sont fournies SANS start code ; SPS/PPS sont signalés séparément.
 */
class ScreenCapture(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val keyFrameIntervalSec: Int = 1
) {
    /** SPS et PPS (sans start code). Appelé dès que l'encodeur les publie. */
    var onParameterSets: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null

    /** Une image encodée = une ou plusieurs NAL units (sans start code). */
    var onFrame: ((nals: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean) -> Unit)? = null

    /** La capture a été arrêtée par le système ou l'utilisateur. */
    var onStopped: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)

    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null

    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null

    fun start(resultCode: Int, data: Intent) {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection indisponible")
        projection = proj

        // Obligatoire depuis Android 14 : enregistrer un callback AVANT createVirtualDisplay.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
                onStopped?.invoke()
            }
        }, mainHandler)

        val enc = createEncoder()
        codec = enc
        val surface = enc.createInputSurface()
        inputSurface = surface
        enc.start()
        running = true

        val dpi = context.resources.displayMetrics.densityDpi
        virtualDisplay = proj.createVirtualDisplay(
            "ViewCast", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, mainHandler
        )

        drainThread = Thread({ drainLoop(enc) }, "ViewCast-encoder").also { it.start() }
    }

    private fun createEncoder(): MediaCodec {
        // 1er essai : CBR + profil Baseline (le plus compatible côté lecteur) ; sinon repli sans ces options.
        for (strict in listOf(true, false)) {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)
                // Écran statique => l'encodeur ne produit rien : on force la répétition de la dernière image.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // temps réel
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                if (strict) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                }
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return enc
            } catch (e: Exception) {
                Log.w(TAG, "configure(strict=$strict) a échoué : ${e.message}")
                runCatching { enc.release() }
                if (!strict) throw e
            }
        }
        throw IllegalStateException("Encodeur H.264 indisponible")
    }

    private fun drainLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = enc.dequeueOutputBuffer(info, 100_000)
                when {
                    index >= 0 -> {
                        val buf = enc.getOutputBuffer(index)
                        if (buf != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(bytes)
                            handleBuffer(bytes, info)
                        }
                        enc.releaseOutputBuffer(index, false)
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = enc.outputFormat
                        val csd0 = f.getByteBuffer("csd-0")
                        val csd1 = f.getByteBuffer("csd-1")
                        if (csd0 != null) publishConfig(H264.splitNalUnits(csd0.toBytes()))
                        if (csd1 != null) publishConfig(H264.splitNalUnits(csd1.toBytes()))
                    }
                }
            }
        } catch (_: IllegalStateException) {
            // encodeur arrêté pendant l'appel : fin normale
        } catch (e: Exception) {
            Log.e(TAG, "Boucle d'encodage interrompue", e)
        }
    }

    private fun handleBuffer(bytes: ByteArray, info: MediaCodec.BufferInfo) {
        val nals = H264.splitNalUnits(bytes)
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            publishConfig(nals)
            return
        }
        val video = ArrayList<ByteArray>(nals.size)
        for (n in nals) {
            when (H264.nalType(n)) {
                H264.NAL_SPS, H264.NAL_PPS -> publishConfig(listOf(n)) // certains encodeurs les répètent devant l'IDR
                H264.NAL_AUD -> Unit
                else -> video.add(n)
            }
        }
        if (video.isEmpty()) return
        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
            video.any { H264.nalType(it) == H264.NAL_IDR }
        onFrame?.invoke(video, info.presentationTimeUs, isKey)
    }

    private fun publishConfig(nals: List<ByteArray>) {
        for (n in nals) {
            when (H264.nalType(n)) {
                H264.NAL_SPS -> lastSps = n
                H264.NAL_PPS -> lastPps = n
            }
        }
        val s = lastSps
        val p = lastPps
        if (s != null && p != null) onParameterSets?.invoke(s, p)
    }

    /** Demande une image clé (IDR) à l'encodeur, par ex. quand un nouveau client démarre la lecture. */
    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running = false
        val t = drainThread
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(500) }
        runCatching { virtualDisplay?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { projection?.stop() }
    }

    private fun ByteBuffer.toBytes(): ByteArray {
        val d = duplicate()
        d.rewind()
        return ByteArray(d.remaining()).also { d.get(it) }
    }

    private companion object {
        const val TAG = "ViewCast"
    }
}
