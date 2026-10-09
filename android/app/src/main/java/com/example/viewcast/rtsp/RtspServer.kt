package com.example.viewcast.rtsp

import android.util.Base64
import android.util.Log
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
import java.util.concurrent.TimeUnit

/**
 * Serveur RTSP minimal mais conforme pour UN flux H.264 :
 * OPTIONS / DESCRIBE / SETUP / PLAY / PAUSE / GET_PARAMETER / TEARDOWN, RTP sur TCP (interleaved).
 * Le transport UDP est refusé (461) : les lecteurs (FFmpeg, VLC, OpenCV) retombent alors sur TCP.
 */
class RtspServer(private val port: Int = 8554) {

    /** Appelé quand un client démarre (ou doit se resynchroniser) : il faut produire une image clé. */
    var onKeyFrameRequested: (() -> Unit)? = null

    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var running = false

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private val random = SecureRandom()

    val clientCount: Int get() = clients.count { it.playing }

    fun setParameterSets(sps: ByteArray, pps: ByteArray) {
        this.sps = sps
        this.pps = pps
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
        val ts = (ptsUs * 90L / 1000L) and 0xFFFFFFFFL
        for (c in clients) {
            if (!c.playing) continue
            if (c.waitingForKey) {
                if (!isKeyFrame) continue      // on attend le prochain IDR pour démarrer proprement
                c.waitingForKey = false
            }
            val packets = ArrayList<ByteArray>()
            if (isKeyFrame) {
                val s = sps
                val p = pps
                if (s != null && p != null) c.packetizer.packetize(listOf(s, p), ts, packets, marker = false)
            }
            c.packetizer.packetize(nals, ts, packets)
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

    private inner class Client(val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
        val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
        val ssrc: Int = random.nextInt()
        var sessionId: String = ""
        var rtpChannel = 0

        @Volatile var playing = false
        @Volatile var waitingForKey = true
        @Volatile private var closed = false
        private var writer: Thread? = null

        val isClosed: Boolean get() = closed
        lateinit var packetizer: RtpPacketizer

        fun setup(channel: Int) {
            rtpChannel = channel
            packetizer = RtpPacketizer(ssrc, random.nextInt(), channel)
        }

        /** Écriture atomique : réponses RTSP et paquets RTP ne se mélangent jamais. */
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
        }
    }

    private class Request(val method: String, val url: String, val headers: Map<String, String>)

    private fun handleClient(socket: Socket) {
        val client = Client(socket)
        clients.add(client)
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 90_000 // un lecteur actif envoie un keep-alive bien avant
            val input = PushbackInputStream(BufferedInputStream(socket.getInputStream()), 1)
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
        if (!awaitParameterSets(5000)) {
            reply(c, cseq, 503)
            return
        }
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
            append("m=video 0 RTP/AVP ${RtpPacketizer.PAYLOAD_TYPE}\r\n")
            append("a=rtpmap:${RtpPacketizer.PAYLOAD_TYPE} H264/90000\r\n")
            append(
                "a=fmtp:${RtpPacketizer.PAYLOAD_TYPE} packetization-mode=1;" +
                    "profile-level-id=$profileLevelId;sprop-parameter-sets=$spsB64,$ppsB64\r\n"
            )
            append("a=control:streamid=0\r\n")
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
        val m = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
        val rtp = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val rtcp = m?.groupValues?.get(2)?.toIntOrNull() ?: (rtp + 1)
        c.setup(rtp)
        if (c.sessionId.isEmpty()) c.sessionId = (random.nextInt() ushr 1).toString(16).padStart(8, '0')
        reply(
            c, cseq, 200,
            listOf(
                "Transport: RTP/AVP/TCP;unicast;interleaved=$rtp-$rtcp;ssrc=${String.format("%08X", c.ssrc)}",
                "Session: ${c.sessionId};timeout=60"
            )
        )
    }

    private fun onPlay(c: Client, cseq: String) {
        if (c.sessionId.isEmpty()) {
            reply(c, cseq, 454)
            return
        }
        c.waitingForKey = true
        reply(c, cseq, 200, listOf("Range: npt=0.000-") + sessionHeaders(c))
        c.startWriter()
        c.playing = true
        onKeyFrameRequested?.invoke() // le client doit recevoir un IDR au plus vite
    }

    private fun awaitParameterSets(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (sps == null || pps == null) {
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
        const val QUEUE_CAPACITY = 600 // ~800 Ko : assez pour une image clé + quelques images
    }
}
