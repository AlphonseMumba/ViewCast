package com.example.viewcast.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/**
 * Capture du son INTERNE du téléphone (lecteur vidéo, jeux, musique...) via AudioPlaybackCapture,
 * puis encodage AAC-LC. Android 10 (API 29) minimum, permission RECORD_AUDIO requise.
 *
 * Limite d'Android : les applications qui interdisent la capture (appels, certaines apps de streaming)
 * restent muettes.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioCapture(
    private val projection: MediaProjection,
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    private val bitrate: Int = 128_000
) {
    /** AudioSpecificConfig (2 octets) : nécessaire pour décrire le flux dans le SDP. */
    var onConfig: ((audioSpecificConfig: ByteArray) -> Unit)? = null

    /** Une trame AAC brute (sans ADTS) et son instant (µs, horloge monotone). */
    var onFrame: ((aacFrame: ByteArray, ptsUs: Long) -> Unit)? = null

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var thread: Thread? = null

    private val frameBytes = SAMPLES_PER_FRAME * channels * 2

    @SuppressLint("MissingPermission")
    fun start() {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, frameBytes * 8))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord non initialisé" }

        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes * 4)
        }
        try {
            enc.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
        } catch (e: Exception) {
            runCatching { enc.release() }
            rec.release()
            throw e
        }

        record = rec
        encoder = enc
        running = true
        thread = Thread({ loop(rec, enc) }, "ViewCast-audio").also { it.start() }
    }

    private fun loop(rec: AudioRecord, enc: MediaCodec) {
        val pcm = ByteArray(frameBytes)
        val info = MediaCodec.BufferInfo()
        var baseUs = 0L
        var samplesIn = 0L
        var framesOut = 0L
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(pcm, 0, pcm.size) // bloquant : rempli à l'horloge audio
                if (n < 0) break
                if (n == 0) continue
                val sampleFrames = n / (2 * channels)
                if (samplesIn == 0L) {
                    // Ancre sur l'horloge monotone ; les instants suivants suivent l'horloge audio (pas de dérive interne).
                    baseUs = System.nanoTime() / 1000 - sampleFrames * 1_000_000L / sampleRate
                }
                val ptsIn = baseUs + samplesIn * 1_000_000L / sampleRate
                samplesIn += sampleFrames

                val inIndex = enc.dequeueInputBuffer(20_000)
                if (inIndex >= 0) {
                    val ib = enc.getInputBuffer(inIndex)!!
                    ib.clear()
                    ib.put(pcm, 0, n)
                    enc.queueInputBuffer(inIndex, 0, n, ptsIn, 0)
                }

                while (true) {
                    val out = enc.dequeueOutputBuffer(info, 0)
                    if (out >= 0) {
                        if (info.size > 0) {
                            val ob = enc.getOutputBuffer(out)!!
                            val bytes = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.get(bytes)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                onConfig?.invoke(bytes)
                            } else {
                                // Une trame AAC = 1024 échantillons : instant déterministe, sans gigue.
                                val pts = baseUs + framesOut * SAMPLES_PER_FRAME * 1_000_000L / sampleRate
                                framesOut++
                                onFrame?.invoke(bytes, pts)
                            }
                        }
                        enc.releaseOutputBuffer(out, false)
                    } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        enc.outputFormat.getByteBuffer("csd-0")?.let { onConfig?.invoke(it.toBytes()) }
                    } else {
                        break
                    }
                }
            }
        } catch (_: IllegalStateException) {
            // arrêt pendant un appel : fin normale
        } catch (e: Exception) {
            Log.e(TAG, "Boucle audio interrompue", e)
        }
    }

    fun stop() {
        running = false
        val t = thread
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(500) }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        record = null
        encoder = null
    }

    private fun ByteBuffer.toBytes(): ByteArray {
        val d = duplicate()
        d.rewind()
        return ByteArray(d.remaining()).also { d.get(it) }
    }

    private companion object {
        const val TAG = "ViewCast"
        const val SAMPLES_PER_FRAME = 1024
    }
}
