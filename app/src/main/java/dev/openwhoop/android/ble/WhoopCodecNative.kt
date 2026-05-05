package dev.openwhoop.android.ble

import java.time.Instant

object WhoopCodecNative {
    private const val DecodeNoop = 0
    private const val DecodeHeartRate = 1
    private const val DecodeHistoryMetadata = 2
    private const val DecodeCommandResponse = 3

    private const val SourceRealtime = 0
    private const val SourceHistory = 1

    init {
        System.loadLibrary("openwhoop_android_algos")
    }

    external fun toggleRealtimeHr(sequence: Int, enabled: Boolean): ByteArray?
    external fun helloHarvard(sequence: Int): ByteArray?
    external fun setTime(sequence: Int): ByteArray?
    external fun getName(sequence: Int): ByteArray?
    external fun enterHighFreqSync(sequence: Int): ByteArray?
    external fun historyStart(sequence: Int): ByteArray?
    external fun historyEnd(sequence: Int, endData: ByteArray): ByteArray?
    external fun historyEndFailure(sequence: Int): ByteArray?
    external fun frameLength(frameStart: ByteArray): Int
    private external fun decodeGen4Frame(frame: ByteArray): ByteArray?

    fun decode(frame: ByteArray): DecodedWhoopData? {
        val decoded = decodeGen4Frame(frame) ?: return null
        if (decoded.isEmpty()) return null
        return when (decoded[0].toInt() and 0xFF) {
            DecodeHeartRate -> decoded.decodeHeartRate()
            DecodeHistoryMetadata -> decoded.decodeHistoryMetadata()
            DecodeCommandResponse -> decoded.decodeCommandResponse()
            DecodeNoop -> null
            else -> null
        }
    }

    private fun ByteArray.decodeHeartRate(): DecodedWhoopData.HeartRate? {
        if (size < 11) return null
        val source = when (this[1].toInt() and 0xFF) {
            SourceRealtime -> WhoopProtocol.SampleSource.Realtime
            SourceHistory -> WhoopProtocol.SampleSource.History
            else -> return null
        }
        val unix = readLongLe(2)
        val bpm = this[10].toLong() and 0xFF
        if (unix <= 0 || bpm <= 0) return null
        return DecodedWhoopData.HeartRate(
            WhoopProtocol.HeartRateSample(
                time = Instant.ofEpochSecond(unix),
                bpm = bpm,
                source = source,
            ),
        )
    }

    private fun ByteArray.decodeHistoryMetadata(): DecodedWhoopData.HistoryMetadata? {
        if (size < 19) return null
        return DecodedWhoopData.HistoryMetadata(
            metadataType = this[1].toInt() and 0xFF,
            timestampSeconds = readLongLe(2),
            endData = copyOfRange(10, 18),
        )
    }

    private fun ByteArray.decodeCommandResponse(): DecodedWhoopData.CommandResponse? {
        if (size < 4) return null
        return DecodedWhoopData.CommandResponse(
            command = this[1].toInt() and 0xFF,
            sequence = this[2].toInt() and 0xFF,
            result = this[3].toInt() and 0xFF,
        )
    }

    private fun ByteArray.readLongLe(offset: Int): Long {
        var value = 0L
        repeat(8) { index ->
            value = value or ((this[offset + index].toLong() and 0xFF) shl (8 * index))
        }
        return value
    }
}

sealed interface DecodedWhoopData {
    data class HeartRate(val sample: WhoopProtocol.HeartRateSample) : DecodedWhoopData

    data class HistoryMetadata(
        val metadataType: Int,
        val timestampSeconds: Long,
        val endData: ByteArray,
    ) : DecodedWhoopData

    data class CommandResponse(
        val command: Int,
        val sequence: Int,
        val result: Int,
    ) : DecodedWhoopData
}
