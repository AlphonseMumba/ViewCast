package com.example.viewcast.rtsp

/** Utilitaires H.264 (format Annex-B -> NAL units). */
internal object H264 {
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    /**
     * Découpe un flux Annex-B (start codes 00 00 01 ou 00 00 00 01) en NAL units,
     * SANS leurs start codes (RTP interdit de les transmettre).
     */
    fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()      // premier octet après le start code
        val codeStarts = ArrayList<Int>()  // position du start code
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                codeStarts.add(i)
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) {
            return if (data.isNotEmpty()) listOf(data) else emptyList()
        }
        val result = ArrayList<ByteArray>(starts.size)
        for (k in starts.indices) {
            val s = starts[k]
            var e = if (k + 1 < starts.size) codeStarts[k + 1] else data.size
            // le 4e octet d'un start code (00) ou des zéros de bourrage appartiennent au séparateur
            while (e > s && data[e - 1] == 0.toByte()) e--
            if (e > s) result.add(data.copyOfRange(s, e))
        }
        return result
    }

    fun nalType(nal: ByteArray): Int = nal[0].toInt() and 0x1F
}
