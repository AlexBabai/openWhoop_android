package dev.openwhoop.android.ble

import java.time.Instant

object StandardHeartRateParser {
    fun parse(data: ByteArray, timestamp: Instant = Instant.now()): WhoopProtocol.HeartRateSample? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val is16Bit = (flags and 0x01) != 0
        if (data.size < if (is16Bit) 3 else 2) return null
        val bpm = if (is16Bit) {
            ((data[1].toLong() and 0xFF) or ((data[2].toLong() and 0xFF) shl 8))
        } else {
            data[1].toLong() and 0xFF
        }
        if (bpm <= 0) return null
        return WhoopProtocol.HeartRateSample(timestamp, bpm, WhoopProtocol.SampleSource.Realtime)
    }
}
