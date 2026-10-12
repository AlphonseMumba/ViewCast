package com.example.viewcast.rtsp

import android.util.Base64
import android.util.Log
import com.example.viewcast.CastStats
import com.example.viewcast.security.HandshakeException
import com.example.viewcast.security.PinGuard
import com.example.viewcast.security.SecureChannel
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Serveur RTSP pour UN flux vidéo H.264 et, en option, UN flux audio AAC :
 * OPTIONS / DESCRIBE / SETUP / PLAY / PAUSE / GET_PARAMETER / TEARDOWN, RTP sur TCP (interleaved)
 * avec RTCP Sender Reports (synchronisation image/son).
 * Le transport UDP est refusé (461) : les lecteurs (FFmpeg, VLC) retombent alors sur TCP.
 *
 * Pistes : streamid=0 = vidéo, streamid=1 = audio.
 */
class RtspServer(private val port: Int = 8554) {

    /** Appelé quand un client démarre (ou doit se resynchroniser) : il faut produire une image clé. */
    var onKeyFrameRequested: (() -> Unit)? = null

    /**
     * Code PIN à 6 chiffres. Non nul => le flux est chiffré (AES-256-GCM) et authentifié par SRP-6a :
     * un client sans le bon code est refusé. Nul => mode ouvert (RTSP en clair, compatible VLC/ffplay).
     */
    @Volatile var pin: String? = null

    /** Appelé quand le PIN est renouvelé après trop d'essais incorrects. */
    var onPinChanged: ((String) -> Unit)? = null

    private val guard = PinGuard(MAX_PIN_FAILURES)
    private val handshakeSlots = Semaphore(MAX_PARALLEL_HANDSHAKES)

    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var running = false

    @Volatile private var audioExpected = false
    @Volatile private var audioConfig: ByteArray? = null
    @Volatile private var audioSampleRate = 48_000
    @Volatile private var audioChannels = 2

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private val random = SecureRandom()

    val clientCount: Int get() = clients.count { it.playing }

    fun setParameterSets(sps: ByteArray, pps: ByteArray) {
        this.sps = sps
        this.pps = pps
    }

    /** À appeler AVANT que le son ne démarre : le DESCRIBE attendra la configuration AAC. */
    fun expectAudio(sampleRate: Int, channels: Int) {
        audioSampleRate = sampleRate
        audioChannels = channels
        audioExpected = true
    }

    fun disableAudio() {
        audioExpected = false
    }

    fun setAudioConfig(audioSpecificConfig: ByteArray) {
        audioConfig = audioSpecificConfig
    }

    @Throws(IOException::class)
    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        running = true
        Thread({ acceptLoop(ss) }, "ViewCast-rtsp-accept").also { it.isDaemon = true }.start()
        Thread({ senderReportLoop() }, "ViewCast-rtcp").also { it.isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (c in clients) c.close()
        clients.clear()
    }

    /** Envoie une image encodée (NAL units sans start code) à tous les clients en lecture. */
    fun sendFrame(nals: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean) {
        if (clients.isEmpty()) return
        for (c in clients) {
            if (!c.playing) continue
            val v = c.video ?: continue
            if (c.waitingForKey) {
                if (!isKeyFrame) continue      // on attend le prochain IDR pour démarrer proprement
                c.waitingForKey = false
            }
            val ts = v.timestamp(ptsUs)
            val packets = ArrayList<ByteArray>()
            if (!v.senderReportSent) packets.add(v.senderReport(ntpMillisFor(ptsUs), ptsUs))
            if (isKeyFrame) {
                val s = sps
                val p = pps
                if (s != null && p != null) v.packetize(listOf(s, p), ts, packets, marker = false)
            }
            v.packetize(nals, ts, packets)
            for (pkt in packets) {
                if (!c.queue.offer(pkt)) {
                    // client trop lent : on vide et on repart sur la prochaine image clé
                    c.queue.clear()
                    c.waitingForKey = true
                    onKeyFrameRequested?.invoke()
                    break
                }
            }
        }
    }

    /** Envoie une trame AAC brute à tous les clients en lecture (après le démarrage de leur vidéo). */
    fun sendAudio(frame: ByteArray, ptsUs: Long) {
        if (clients.isEmpty() || CastStats.audioMuted) return
        for (c in clients) {
            if (!c.playing) continue
            val a = c.audio ?: continue
            if (c.video != null && c.waitingForKey) continue
            val packets = ArrayList<ByteArray>(2)
            if (!a.senderReportSent) packets.add(a.senderReport(ntpMillisFor(ptsUs), ptsUs))
            a.packetize(frame, a.timestamp(ptsUs), packets)
            for (pkt in packets) {
                if (!c.queue.offer(pkt)) break // file pleine : on perd une trame audio, la vidéo gère la resynchronisation
            }
        }
    }

    /** Heure murale correspondant à un instant de l'horloge monotone (µs). */
    private fun ntpMillisFor(ptsUs: Long): Long =
        System.currentTimeMillis() - (System.nanoTime() / 1000 - ptsUs) / 1000

    /** RTCP SR périodiques : maintiennent la correspondance horloge RTP <-> heure murale. */
    private fun senderReportLoop() {
        while (running) {
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                return
            }
            val nowUs = System.nanoTime() / 1000
            val ntp = System.currentTimeMillis()
            for (c in clients) {
                if (!c.playing) continue
                c.video?.takeIf { it.senderReportSent }?.let { c.queue.offer(it.senderReport(ntp, nowUs)) }
                c.audio?.takeIf { it.senderReportSent }?.let { c.queue.offer(it.senderReport(ntp, nowUs)) }
            }
        }
    }

    // ------------------------------------------------------------------ réseau

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (_: IOException) {
                break
            }
            Thread({ handleClient(s) }, "ViewCast-rtsp-client").also { it.isDaemon = true }.start()
        }
    }

    private inner class Client(val socket: Socket, val out: OutputStream) {
        val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
        var sessionId: String = ""

        @Volatile var video: H264Packetizer? = null
        @Volatile var audio: AacPacketizer? = null
        @Volatile var playing = false
        @Volatile var waitingForKey = true
        @Volatile private var closed = false
        private var writer: Thread? = null

        val isClosed: Boolean get() = closed

        /** Écriture atomique : réponses RTSP et paquets RTP/RTCP ne se mélangent jamais. */
        fun write(bytes: ByteArray) {
            synchronized(out) {
                out.write(bytes)
                out.flush()
            }
        }

        fun startWriter() {
            if (writer != null) return
            writer = Thread({
                try {
                    while (!closed) {
                        val pkt = queue.poll(1, TimeUnit.SECONDS) ?: continue
                        write(pkt)
                        CastStats.bytesSent.addAndGet(pkt.size.toLong())
                    }
                } catch (_: IOException) {
                } catch (_: InterruptedException) {
                } finally {
                    close()
                }
            }, "ViewCast-rtsp-writer").also { it.isDaemon = true; it.start() }
        }

        fun close() {
            if (closed) return
            closed = true
            playing = false
            runCatching { socket.close() }
            writer?.interrupt()
            clients.remove(this)
            CastStats.clients = clients.count { it.playing }
        }
    }

    private class Request(val method: String, val url: String, val headers: Map<String, String>)

    /** Ouvre les flux du client : chiffrés + authentifiés par PIN si activé, sinon directs (mode ouvert). */
    private fun openStreams(socket: Socket): Pair<InputStream, OutputStream> {
        socket.tcpNoDelay = true
        socket.soTimeout = 90_000 // un lecteur actif envoie un keep-alive bien avant
        val currentPin = pin ?: return Pair(socket.getInputStream(), socket.getOutputStream())
        if (!handshakeSlots.tryAcquire()) { // trop de tentatives simultanées : refus sans calcul
            runCatching { socket.close() }
            throw IOException("trop de connexions simultanées")
        }
        try {
            val channel = SecureChannel.serverHandshake(socket, currentPin)
            guard.recordSuccess()
            return Pair(channel.inputStream, channel.outputStream)
        } catch (e: HandshakeException) {
            if (e.reason == HandshakeException.BAD_PROOF) onBadPin()
            throw e
        } finally {
            handshakeSlots.release()
        }
    }

    /** Après MAX_PIN_FAILURES échecs d'affilée, un NOUVEAU code est généré : les essais précédents deviennent inutiles. */
    private fun onBadPin() {
        CastStats.authFailures.incrementAndGet()
        if (guard.recordFailure()) {
            val fresh = PinGuard.newPin()
            pin = fresh
            CastStats.pin = fresh
            onPinChanged?.invoke(fresh)
        }
    }

    private fun handleClient(socket: Socket) {
        val streams = try {
            openStreams(socket)
        } catch (e: IOException) {
            Log.d(TAG, "Connexion refusée : ${e.message}")
            runCatching { socket.close() }
            return
        }
        val client = Client(socket, streams.second)
        clients.add(client)
        try {
            val input = PushbackInputStream(BufferedInputStream(streams.first), 1)
            while (running && !client.isClosed) {
                val req = readRequest(input) ?: break
                val cseq = req.headers["cseq"] ?: "0"
                when (req.method.uppercase()) {
                    "OPTIONS" -> reply(
                        client, cseq, 200,
                        listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER, TEARDOWN")
                    )
                    "DESCRIBE" -> onDescribe(client, cseq)
                    "SETUP" -> onSetup(client, cseq, req)
                    "PLAY" -> onPlay(client, cseq)
                    "PAUSE" -> {
                        client.playing = false
                        reply(client, cseq, 200, sessionHeaders(client))
                    }
                    "GET_PARAMETER", "SET_PARAMETER" -> reply(client, cseq, 200, sessionHeaders(client))
                    "TEARDOWN" -> {
                        reply(client, cseq, 200, sessionHeaders(client))
                        break
                    }
                    else -> reply(client, cseq, 405)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Client RTSP déconnecté : ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur client RTSP", e)
        } finally {
            client.close()
        }
    }

    private fun sessionHeaders(c: Client): List<String> =
        if (c.sessionId.isNotEmpty()) listOf("Session: ${c.sessionId};timeout=60") else emptyList()

    private fun onDescribe(c: Client, cseq: String) {
        if (!awaitVideoConfig(5000)) {
            reply(c, cseq, 503)
            return
        }
        val asc = if (audioExpected && awaitAudioConfig(3000)) audioConfig else null
        val s = sps!!
        val p = pps!!
        val host = c.socket.localAddress?.hostAddress ?: "0.0.0.0"
        val profileLevelId = String.format(
            "%02X%02X%02X",
            s[1].toInt() and 0xFF, s[2].toInt() and 0xFF, s[3].toInt() and 0xFF
        )
        val spsB64 = Base64.encodeToString(s, Base64.NO_WRAP)
        val ppsB64 = Base64.encodeToString(p, Base64.NO_WRAP)
        val sdp = buildString {
            append("v=0\r\n")
            append("o=- 0 0 IN IP4 $host\r\n")
            append("s=ViewCast\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("t=0 0\r\n")
            append("m=video 0 RTP/AVP $VIDEO_PAYLOAD_TYPE\r\n")
            append("a=rtpmap:$VIDEO_PAYLOAD_TYPE H264/90000\r\n")
            append(
                "a=fmtp:$VIDEO_PAYLOAD_TYPE packetization-mode=1;" +
                    "profile-level-id=$profileLevelId;sprop-parameter-sets=$spsB64,$ppsB64\r\n"
            )
            append("a=control:streamid=0\r\n")
            if (asc != null) {
                val config = asc.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
                append("m=audio 0 RTP/AVP $AUDIO_PAYLOAD_TYPE\r\n")
                append("a=rtpmap:$AUDIO_PAYLOAD_TYPE MPEG4-GENERIC/$audioSampleRate/$audioChannels\r\n")
                append(
                    "a=fmtp:$AUDIO_PAYLOAD_TYPE streamtype=5;profile-level-id=1;mode=AAC-hbr;" +
                        "sizelength=13;indexlength=3;indexdeltalength=3;config=$config\r\n"
                )
                append("a=control:streamid=1\r\n")
            }
        }
        reply(
            c, cseq, 200,
            listOf("Content-Base: rtsp://$host:$port/stream/", "Content-Type: application/sdp"),
            sdp
        )
    }

    private fun onSetup(c: Client, cseq: String, req: Request) {
        val transport = req.headers["transport"] ?: ""
        if (!transport.contains("RTP/AVP/TCP", ignoreCase = true)) {
            reply(c, cseq, 461) // UDP non supporté : le lecteur doit réessayer en TCP
            return
        }
        val isAudio = req.url.endsWith("streamid=1")
        if (isAudio && audioConfig == null) {
            reply(c, cseq, 404)
            return
        }
        val m = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
        val defaultRtp = if (isAudio) 2 else 0
        val rtp = m?.groupValues?.get(1)?.toIntOrNull() ?: defaultRtp
        val rtcp = m?.groupValues?.get(2)?.toIntOrNull() ?: (rtp + 1)
        val ssrc = random.nextInt()
        val tsOffset = random.nextInt().toLong() and 0xFFFFFFFFL
        if (isAudio) {
            c.audio = AacPacketizer(ssrc, random.nextInt(), rtp, audioSampleRate, tsOffset)
        } else {
            c.video = H264Packetizer(ssrc, random.nextInt(), rtp, tsOffset)
        }
        if (c.sessionId.isEmpty()) c.sessionId = (random.nextInt() ushr 1).toString(16).padStart(8, '0')
        reply(
            c, cseq, 200,
            listOf(
                "Transport: RTP/AVP/TCP;unicast;interleaved=$rtp-$rtcp;ssrc=${String.format("%08X", ssrc)}",
                "Session: ${c.sessionId};timeout=60"
            )
        )
    }

    private fun onPlay(c: Client, cseq: String) {
        if (c.sessionId.isEmpty() || (c.video == null && c.audio == null)) {
            reply(c, cseq, 454)
            return
        }
        c.waitingForKey = true
        reply(c, cseq, 200, listOf("Range: npt=0.000-") + sessionHeaders(c))
        c.startWriter()
        c.playing = true
        CastStats.clients = clients.count { it.playing }
        onKeyFrameRequested?.invoke() // le client doit recevoir un IDR au plus vite
    }

    private fun awaitVideoConfig(timeoutMs: Long): Boolean = waitFor(timeoutMs) { sps != null && pps != null }

    private fun awaitAudioConfig(timeoutMs: Long): Boolean = waitFor(timeoutMs) { audioConfig != null || !audioExpected }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline || !running) return false
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return true
    }

    private fun reply(c: Client, cseq: String, code: Int, headers: List<String> = emptyList(), body: String? = null) {
        val text = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            454 -> "Session Not Found"
            461 -> "Unsupported Transport"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
            .append("RTSP/1.0 $code $text\r\n")
            .append("CSeq: $cseq\r\n")
            .append("Server: ViewCast\r\n")
        for (h in headers) head.append(h).append("\r\n")
        if (bodyBytes != null) head.append("Content-Length: ${bodyBytes.size}\r\n")
        head.append("\r\n")
        val headBytes = head.toString().toByteArray(Charsets.UTF_8)
        c.write(if (bodyBytes == null) headBytes else headBytes + bodyBytes)
    }

    // ------------------------------------------------------------------ lecture des requêtes

    private fun readRequest(input: PushbackInputStream): Request? {
        // Ignore les trames interleaved envoyées par le client (RTCP) et les lignes vides.
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '$'.code) {
                input.read() // canal
                val hi = input.read()
                val lo = input.read()
                if (lo < 0) return null
                skipFully(input, (hi shl 8) or lo)
            } else if (b == '\r'.code || b == '\n'.code) {
                continue
            } else {
                input.unread(b)
                break
            }
        }
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        headers["content-length"]?.toIntOrNull()?.let { if (it > 0) skipFully(input, it) }
        return Request(parts[0], parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("Ligne RTSP trop longue")
        }
    }

    private fun skipFully(input: InputStream, count: Int) {
        var left = count
        while (left > 0) {
            val n = input.skip(left.toLong()).toInt()
            if (n <= 0) {
                if (input.read() < 0) throw IOException("Flux fermé")
                left--
            } else {
                left -= n
            }
        }
    }

    private companion object {
        const val TAG = "ViewCast"
        const val MAX_PIN_FAILURES = 5
        const val MAX_PARALLEL_HANDSHAKES = 2
        const val QUEUE_CAPACITY = 800 // ~1 Mo : une image clé + quelques images + l'audio
    }
}
