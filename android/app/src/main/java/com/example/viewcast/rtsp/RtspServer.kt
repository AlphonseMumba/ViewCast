package com.example.viewcast.rtsp

import kotlinx.coroutines.*
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import android.media.MediaCodec

class RtspServer(private val scope: CoroutineScope, private val port: Int = 8554) {
    private var serverJob: Job? = null
    private val clients = mutableListOf<Client>()

    data class Client(val socket: Socket, val out: BufferedOutputStream)

    @Volatile private var sps: ByteBuffer? = null
    @Volatile private var pps: ByteBuffer? = null

    fun updateSpsPps(spsBuf: ByteBuffer, ppsBuf: ByteBuffer) {
        sps = spsBuf.duplicate()
        pps = ppsBuf.duplicate()
    }

    fun start() {
        serverJob = scope.launch(Dispatchers.IO) {
            val server = ServerSocket(port)
            while (isActive) {
                val socket = server.accept()
                launch { handleClient(socket) }
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        synchronized(clients) {
            clients.forEach { runCatching { it.socket.close() } }
            clients.clear()
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val out = BufferedOutputStream(socket.getOutputStream())
        var session = "12345678"

        while (true) {
            val request = readRequest(reader) ?: break
            val (method, url, cseq) = request

            when (method) {
                "OPTIONS" -> {
                    respond(out, cseq, extraHeaders = listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY"))
                }
                "DESCRIBE" -> {
                    val spsLocal = sps ?: ByteBuffer.allocate(0)
                    val ppsLocal = pps ?: ByteBuffer.allocate(0)
                    val spsB64 = android.util.Base64.encodeToString(byteArrayOf(*spsLocal.copyBytes()), android.util.Base64.NO_WRAP)
                    val ppsB64 = android.util.Base64.encodeToString(byteArrayOf(*ppsLocal.copyBytes()), android.util.Base64.NO_WRAP)
                    val sdp = buildString {
                        append("v=0\r\n")
                        append("o=- 0 0 IN IP4 127.0.0.1\r\n")
                        append("s=AndroidScreen\r\n")
                        append("t=0 0\r\n")
                        append("m=video 0 RTP/AVP 96\r\n")
                        append("c=IN IP4 0.0.0.0\r\n")
                        append("a=rtpmap:96 H264/90000\r\n")
                        append("a=fmtp:96 packetization-mode=1;sprop-parameter-sets=$spsB64,$ppsB64\r\n")
                        append("a=control:trackID=1\r\n")
                    }
                    val headers = listOf(
                        "Content-Base: $url",
                        "Content-Type: application/sdp",
                        "Content-Length: ${sdp.toByteArray().size}"
                    )
                    respond(out, cseq, extraHeaders = headers, body = sdp)
                }
                "SETUP" -> {
                    // Interleaved TCP RTP
                    session = "sess" + System.currentTimeMillis()
                    val headers = listOf(
                        "Transport: RTP/AVP/TCP;unicast;interleaved=0-1",
                        "Session: $session"
                    )
                    respond(out, cseq, extraHeaders = headers)
                    synchronized(clients) { clients.add(Client(socket, out)) }
                }
                "PLAY" -> {
                    val headers = listOf("RTP-Info: url=$url/trackID=1;seq=0")
                    respond(out, cseq, extraHeaders = headers)
                }
                else -> respond(out, cseq)
            }
        }
    }

    private fun respond(out: BufferedOutputStream, cseq: String, extraHeaders: List<String> = emptyList(), body: String? = null) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 200 OK\r\n")
        sb.append("CSeq: $cseq\r\n")
        extraHeaders.forEach { sb.append("$it\r\n") }
        if (body != null) {
            sb.append("\r\n")
            sb.append(body)
        } else {
            sb.append("\r\n")
        }
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun readRequest(reader: BufferedReader): Triple<String, String, String>? {
        var line = reader.readLine() ?: return null
        if (line.isEmpty()) return null
        val requestLine = line
        var method = ""
        var url = ""
        requestLine.split(" ").let {
            method = it.getOrNull(0) ?: ""
            url = it.getOrNull(1) ?: ""
        }
        var cseq = "1"
        while (true) {
            line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("CSeq:", true)) {
                cseq = line.split(":")[1].trim()
            }
        }
        return Triple(method, url, cseq)
    }

    fun sendH264Sample(nal: ByteArray, isKeyFrame: Boolean) {
        val rtpHeader = ByteArray(12)
        // Minimalistic: not a full RTP implementation; for demo only.
        // For TCP interleaving, prepend '$' channel byte and 2-byte length
        val payload = nal
        val interleavedHeader = byteArrayOf('$'.code.toByte(), 0, ((payload.size shr 8) and 0xFF).toByte(), (payload.size and 0xFF).toByte())

        synchronized(clients) {
            val iter = clients.iterator()
            while (iter.hasNext()) {
                val c = iter.next()
                try {
                    c.out.write(interleavedHeader)
                    c.out.write(payload)
                    c.out.flush()
                } catch (e: Exception) {
                    runCatching { c.socket.close() }
                    iter.remove()
                }
            }
        }
    }

    private fun ByteBuffer.copyBytes(): ByteArray {
        val dup = duplicate()
        val arr = ByteArray(dup.remaining())
        dup.get(arr)
        return arr
    }
}
