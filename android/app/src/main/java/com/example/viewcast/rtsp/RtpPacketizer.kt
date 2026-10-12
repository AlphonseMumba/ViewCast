package com.example.viewcast.rtsp

internal const val VIDEO_PAYLOAD_TYPE = 96
internal const val AUDIO_PAYLOAD_TYPE = 97
internal const val MAX_RTP_PAYLOAD = 1400

/**
 * Flux RTP envoyé en trames "interleaved" RTSP-sur-TCP :
 *   '$' | canal (1 octet) | longueur (2 octets, big endian) | paquet RTP ou RTCP
 *
 * Gère le numéro de séquence, l'horloge RTP, les compteurs et les Sender Reports RTCP.
 * Les Sender Reports (NTP <-> horloge RTP) permettent au lecteur de resynchroniser
 * l'image et le son, qui ont chacun leur propre horloge RTP.
 *
 * Tous les temps `ptsUs` sont sur la MÊME base : microsecondes de l'horloge monotone (System.nanoTime()/1000).
 */
internal abstract class RtpStream(
    val ssrc: Int,
    initialSeq: Int,
    val channel: Int,
    val clockRate: Int,
    private val payloadType: Int,
    private val tsOffset: Long
) {
    private var seq = initialSeq and 0xFFFF

    @Volatile private var packetCount = 0L
    @Volatile private var octetCount = 0L

    /** Un Sender Report doit précéder le tout premier paquet média. */
    @Volatile var senderReportSent = false

    fun timestamp(ptsUs: Long): Long = (ptsUs * clockRate / 1_000_000L + tsOffset) and 0xFFFFFFFFL

    protected fun build(
        prefix: ByteArray?, src: ByteArray, srcOff: Int, srcLen: Int,
        marker: Boolean, timestamp: Long
    ): ByteArray {
        val preLen = prefix?.size ?: 0
        val payloadLen = preLen + srcLen
        val rtpLen = 12 + payloadLen
        val buf = ByteArray(4 + rtpLen)
        buf[0] = '$'.code.toByte()
        buf[1] = channel.toByte()
        buf[2] = (rtpLen shr 8).toByte()
        buf[3] = (rtpLen and 0xFF).toByte()
        buf[4] = 0x80.toByte()                                        // V=2, P=0, X=0, CC=0
        buf[5] = ((if (marker) 0x80 else 0) or payloadType).toByte()
        buf[6] = (seq shr 8).toByte()
        buf[7] = (seq and 0xFF).toByte()
        put32(buf, 8, timestamp)
        put32(buf, 12, ssrc.toLong() and 0xFFFFFFFFL)
        var p = 16
        if (prefix != null) {
            System.arraycopy(prefix, 0, buf, p, preLen)
            p += preLen
        }
        System.arraycopy(src, srcOff, buf, p, srcLen)
        seq = (seq + 1) and 0xFFFF
        packetCount++
        octetCount += payloadLen
        return buf
    }

    /**
     * RTCP Sender Report (trame interleaved sur le canal RTCP = canal RTP + 1).
     * @param ntpMillis heure murale (ms depuis 1970) correspondant à [ptsUs]
     * @param ptsUs     instant média (horloge monotone, µs) auquel l'horloge RTP du SR est calculée
     */
    fun senderReport(ntpMillis: Long, ptsUs: Long): ByteArray {
        val buf = ByteArray(4 + 28)
        buf[0] = '$'.code.toByte()
        buf[1] = (channel + 1).toByte()
        buf[2] = 0
        buf[3] = 28
        buf[4] = 0x80.toByte()          // V=2, P=0, RC=0
        buf[5] = 200.toByte()           // PT = SR
        buf[6] = 0
        buf[7] = 6                      // longueur en mots de 32 bits - 1
        put32(buf, 8, ssrc.toLong() and 0xFFFFFFFFL)
        val seconds = ntpMillis / 1000 + 2_208_988_800L            // 1900 -> 1970
        val fraction = ((ntpMillis % 1000) shl 32) / 1000
        put32(buf, 12, seconds)
        put32(buf, 16, fraction)
        put32(buf, 20, timestamp(ptsUs))
        put32(buf, 24, packetCount)
        put32(buf, 28, octetCount)
        senderReportSent = true
        return buf
    }

    private fun put32(buf: ByteArray, off: Int, v: Long) {
        buf[off] = (v shr 24).toByte()
        buf[off + 1] = (v shr 16).toByte()
        buf[off + 2] = (v shr 8).toByte()
        buf[off + 3] = v.toByte()
    }
}

/** H.264 (RFC 6184, mode 1) : NAL seule si <= MAX_RTP_PAYLOAD, sinon fragmentation FU-A. */
internal class H264Packetizer(ssrc: Int, initialSeq: Int, channel: Int, tsOffset: Long) :
    RtpStream(ssrc, initialSeq, channel, 90_000, VIDEO_PAYLOAD_TYPE, tsOffset) {

    /** @param marker true => bit M sur le dernier paquet (fin d'access unit) */
    fun packetize(nals: List<ByteArray>, timestamp: Long, out: MutableList<ByteArray>, marker: Boolean = true) {
        for ((idx, nal) in nals.withIndex()) {
            if (nal.isEmpty()) continue
            val lastNal = idx == nals.lastIndex
            if (nal.size <= MAX_RTP_PAYLOAD) {
                out.add(build(null, nal, 0, nal.size, marker && lastNal, timestamp))
            } else {
                val header = nal[0].toInt() and 0xFF
                val indicator = (header and 0xE0) or 28            // FU indicator : F/NRI du NAL + type 28
                var offset = 1                                      // l'octet d'en-tête NAL n'est pas répété
                var first = true
                while (offset < nal.size) {
                    val len = minOf(MAX_RTP_PAYLOAD - 2, nal.size - offset)
                    val last = offset + len >= nal.size
                    var fu = header and 0x1F                        // FU header : S|E|R|type
                    if (first) fu = fu or 0x80
                    if (last) fu = fu or 0x40
                    val prefix = byteArrayOf(indicator.toByte(), fu.toByte())
                    out.add(build(prefix, nal, offset, len, marker && lastNal && last, timestamp))
                    offset += len
                    first = false
                }
            }
        }
    }
}

/** AAC (RFC 3640, mode AAC-hbr) : une trame AAC brute par paquet RTP, précédée d'un AU-header de 16 bits. */
internal class AacPacketizer(ssrc: Int, initialSeq: Int, channel: Int, sampleRate: Int, tsOffset: Long) :
    RtpStream(ssrc, initialSeq, channel, sampleRate, AUDIO_PAYLOAD_TYPE, tsOffset) {

    fun packetize(frame: ByteArray, timestamp: Long, out: MutableList<ByteArray>) {
        if (frame.isEmpty() || frame.size >= 8192) return          // taille sur 13 bits
        val au = frame.size shl 3                                  // 13 bits taille | 3 bits index (0)
        val prefix = byteArrayOf(0x00, 0x10, (au shr 8).toByte(), (au and 0xFF).toByte())
        out.add(build(prefix, frame, 0, frame.size, true, timestamp))
    }
}
