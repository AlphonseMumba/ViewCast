package com.example.viewcast.rtsp

/**
 * Paquetisation RTP H.264 (RFC 6184, mode 1) encapsulée en trames "interleaved" RTSP-sur-TCP :
 *   '$' | canal (1 octet) | longueur (2 octets, big endian) | paquet RTP
 *
 * - NAL <= MAX_PAYLOAD : paquet "Single NAL unit"
 * - sinon : fragmentation FU-A
 * Une instance par client (numéro de séquence et SSRC propres).
 */
internal class RtpPacketizer(
    private val ssrc: Int,
    initialSeq: Int,
    private val channel: Int
) {
    private var seq = initialSeq and 0xFFFF

    /**
     * @param nals       NAL units sans start code
     * @param timestamp  horloge 90 kHz (32 bits)
     * @param marker     true => bit M sur le dernier paquet (fin d'access unit)
     */
    fun packetize(nals: List<ByteArray>, timestamp: Long, out: MutableList<ByteArray>, marker: Boolean = true) {
        for ((idx, nal) in nals.withIndex()) {
            if (nal.isEmpty()) continue
            val lastNal = idx == nals.lastIndex
            if (nal.size <= MAX_PAYLOAD) {
                out.add(build(null, 0, nal, 0, nal.size, marker && lastNal, timestamp))
            } else {
                val header = nal[0].toInt() and 0xFF
                val indicator = (header and 0xE0) or 28            // FU indicator : F/NRI du NAL + type 28
                var offset = 1                                      // l'octet d'en-tête NAL n'est pas répété
                var first = true
                while (offset < nal.size) {
                    val len = minOf(MAX_PAYLOAD - 2, nal.size - offset)
                    val last = offset + len >= nal.size
                    var fu = header and 0x1F                        // FU header : S|E|R|type
                    if (first) fu = fu or 0x80
                    if (last) fu = fu or 0x40
                    out.add(build(indicator, fu, nal, offset, len, marker && lastNal && last, timestamp))
                    offset += len
                    first = false
                }
            }
        }
    }

    private fun build(
        fuIndicator: Int?, fuHeader: Int,
        src: ByteArray, srcOff: Int, srcLen: Int,
        marker: Boolean, timestamp: Long
    ): ByteArray {
        val fuLen = if (fuIndicator != null) 2 else 0
        val rtpLen = 12 + fuLen + srcLen
        val buf = ByteArray(4 + rtpLen)
        buf[0] = '$'.code.toByte()
        buf[1] = channel.toByte()
        buf[2] = (rtpLen shr 8).toByte()
        buf[3] = (rtpLen and 0xFF).toByte()
        // en-tête RTP
        buf[4] = 0x80.toByte()                                      // V=2, P=0, X=0, CC=0
        buf[5] = ((if (marker) 0x80 else 0) or PAYLOAD_TYPE).toByte()
        buf[6] = (seq shr 8).toByte()
        buf[7] = (seq and 0xFF).toByte()
        buf[8] = (timestamp shr 24).toByte()
        buf[9] = (timestamp shr 16).toByte()
        buf[10] = (timestamp shr 8).toByte()
        buf[11] = timestamp.toByte()
        buf[12] = (ssrc shr 24).toByte()
        buf[13] = (ssrc shr 16).toByte()
        buf[14] = (ssrc shr 8).toByte()
        buf[15] = ssrc.toByte()
        var p = 16
        if (fuIndicator != null) {
            buf[p++] = fuIndicator.toByte()
            buf[p++] = fuHeader.toByte()
        }
        System.arraycopy(src, srcOff, buf, p, srcLen)
        seq = (seq + 1) and 0xFFFF
        return buf
    }

    companion object {
        const val PAYLOAD_TYPE = 96
        const val MAX_PAYLOAD = 1400
    }
}
