package com.example.viewcast.receive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Analyse « 192.168.43.1 », « 192.168.43.1:8554 » ou « rtsp://hôte:port/... » → (hôte, port) ou null. */
object RxAddress {
    fun parse(text: String): Pair<String, Int>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val rest = if (t.startsWith("rtsp://", ignoreCase = true)) t.substring(7).substringBefore('/') else t
        if (rest.any { it.isWhitespace() || it == '/' }) return null
        val host = rest.substringBeforeLast(':', rest)
        val portText = if (rest.contains(':')) rest.substringAfterLast(':') else "8554"
        val port = portText.toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }
}

/**
 * Réception d'un autre appareil ViewCast : client RTSP (chiffré par PIN ou en clair) + décodage matériel.
 * Image : MediaCodec H.264 -> Surface. Son : MediaCodec AAC -> AudioTrack. Affichage immédiat (direct, faible latence).
 */
class ReceiverSession(host: String, port: Int, pin: String?) : RtspPlayer.Listener {

    enum class State { CONNECTING, WAITING_VIDEO, PLAYING, UNREACHABLE, AUTH, LOCKED, PROTOCOL, DROPPED, STOPPED }

    @Volatile var state = State.CONNECTING
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    val framesShown = AtomicLong()
    val bytesReceived: Long get() = player.bytesReceived()

    /** Coupe le son localement (n'affecte pas l'autre téléphone). */
    @Volatile var muted = false
        set(value) {
            field = value
            audioTrack?.setVolume(if (value) 0f else 1f)
        }

    private val player = RtspPlayer(host, port, pin, this)
    private val lock = Any()

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var aacConfig: ByteArray? = null
    private var audioRate = 48000
    private var audioChannels = 2
    @Volatile private var surface: Surface? = null

    @Volatile private var video: MediaCodec? = null
    @Volatile private var needKey = true
    private var videoThread: Thread? = null

    @Volatile private var audio: MediaCodec? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null

    fun start() = player.start()

    fun stop() {
        player.stop()
        synchronized(lock) {
            releaseVideo()
            releaseAudio()
        }
        if (state == State.CONNECTING || state == State.WAITING_VIDEO || state == State.PLAYING) state = State.STOPPED
    }

    /** La Surface d'affichage apparaît ou disparaît (écran quitté, application en arrière-plan...). */
    fun setSurface(s: Surface?) {
        synchronized(lock) {
            surface = s
            if (s == null) releaseVideo() else startVideo()
        }
    }

    // ───────────────────────── RtspPlayer.Listener ─────────────────────────

    override fun onConfig(sps: ByteArray, pps: ByteArray, aacConfig: ByteArray?, audioRate: Int, audioChannels: Int) {
        synchronized(lock) {
            this.sps = sps
            this.pps = pps
            this.aacConfig = aacConfig
            this.audioRate = audioRate
            this.audioChannels = audioChannels
            state = State.WAITING_VIDEO
            startVideo()
            startAudio()
        }
    }

    override fun onVideoFrame(annexB: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        val codec = video ?: return
        if (needKey && !keyFrame) return
        try {
            val index = codec.dequeueInputBuffer(5_000)
            if (index < 0) {            // décodeur saturé : on saute jusqu'à la prochaine image clé
                needKey = true
                return
            }
            val buf = codec.getInputBuffer(index) ?: return
            buf.clear()
            buf.put(annexB)
            codec.queueInputBuffer(index, 0, annexB.size, ptsUs, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            needKey = false
        } catch (e: Exception) {
            needKey = true
        }
    }

    override fun onAudioFrame(aacFrame: ByteArray, ptsUs: Long) {
        val codec = audio ?: return
        try {
            val index = codec.dequeueInputBuffer(5_000)
            if (index < 0) return
            val buf = codec.getInputBuffer(index) ?: return
            buf.clear()
            buf.put(aacFrame)
            codec.queueInputBuffer(index, 0, aacFrame.size, ptsUs, 0)
        } catch (_: Exception) {
        }
    }

    override fun onError(kind: Int, message: String?) {
        Log.w(TAG, "Réception : erreur $kind ($message)")
        state = when (kind) {
            RtspPlayer.ERR_UNREACHABLE -> State.UNREACHABLE
            RtspPlayer.ERR_AUTH -> State.AUTH
            RtspPlayer.ERR_LOCKED -> State.LOCKED
            RtspPlayer.ERR_PROTOCOL -> State.PROTOCOL
            else -> State.DROPPED
        }
    }

    override fun onStopped() {
        synchronized(lock) {
            releaseVideo()
            releaseAudio()
        }
    }

    // ───────────────────────── image ─────────────────────────

    private fun startVideo() {
        val s = surface ?: return
        val spsBytes = sps ?: return
        val ppsBytes = pps ?: return
        if (video != null) return
        try {
            val start = byteArrayOf(0, 0, 0, 1)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(start + spsBytes))
                setByteBuffer("csd-1", ByteBuffer.wrap(start + ppsBytes))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, s, null, 0)
            codec.start()
            needKey = true
            video = codec
            videoThread = Thread({ drainVideo(codec) }, "ViewCast-rx-video").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Décodeur vidéo indisponible", e)
            video = null
        }
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (video === codec) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    codec.releaseOutputBuffer(index, true) // affichage immédiat
                    framesShown.incrementAndGet()
                    if (state == State.WAITING_VIDEO) state = State.PLAYING
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    readSize(codec.outputFormat)
                }
            }
        } catch (_: IllegalStateException) {
        }
    }

    private fun readSize(f: MediaFormat) {
        val hasCrop = f.containsKey("crop-right") && f.containsKey("crop-left") &&
            f.containsKey("crop-bottom") && f.containsKey("crop-top")
        width = if (hasCrop) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
        height = if (hasCrop) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
    }

    private fun releaseVideo() {
        val codec = video ?: return
        video = null
        runCatching { videoThread?.join(300) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        videoThread = null
    }

    // ───────────────────────── son ─────────────────────────

    private fun startAudio() {
        val config = aacConfig ?: return
        if (audio != null) return
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioRate, audioChannels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config))
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
            }
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, 0)
            codec.start()

            val mask = if (audioChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuffer = AudioTrack.getMinBufferSize(audioRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(audioRate)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, audioRate / 5 * 2 * audioChannels)) // ≈ 200 ms
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            val track = builder.build()
            track.setVolume(if (muted) 0f else 1f)
            track.play()

            audioTrack = track
            audio = codec
            audioThread = Thread({ drainAudio(codec, track) }, "ViewCast-rx-audio").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Son indisponible", e)
            audio = null
        }
    }

    private fun drainAudio(codec: MediaCodec, track: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        var pcm = ByteArray(8192)
        try {
            while (audio === codec) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    val out = codec.getOutputBuffer(index)
                    if (out != null && info.size > 0) {
                        if (pcm.size < info.size) pcm = ByteArray(info.size)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.get(pcm, 0, info.size)
                        track.write(pcm, 0, info.size)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } catch (_: IllegalStateException) {
        }
    }

    private fun releaseAudio() {
        val codec = audio ?: return
        audio = null
        runCatching { audioThread?.join(300) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        audioThread = null
    }

    private companion object {
        const val TAG = "ViewCast"
    }
}
