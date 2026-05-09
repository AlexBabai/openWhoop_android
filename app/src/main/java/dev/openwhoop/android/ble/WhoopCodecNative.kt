package dev.openwhoop.android.ble

import dev.openwhoop.android.health.HealthMetricSample
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
    external fun toggleR7DataCollection(sequence: Int): ByteArray?
    external fun toggleImuMode(sequence: Int, enabled: Boolean): ByteArray?
    external fun toggleHistoricalImuMode(sequence: Int, enabled: Boolean): ByteArray?
    external fun enableOpticalData(sequence: Int, enabled: Boolean): ByteArray?
    external fun toggleOpticalMode(sequence: Int, enabled: Boolean): ByteArray?
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
        val healthMetrics = if (size >= 54 && source == WhoopProtocol.SampleSource.History) {
            decodeHealthMetrics(unix, bpm, source)
        } else {
            null
        }
        return DecodedWhoopData.HeartRate(
            WhoopProtocol.HeartRateSample(
                time = Instant.ofEpochSecond(unix),
                bpm = bpm,
                source = source,
            ),
            healthMetrics = healthMetrics,
        )
    }

    private fun ByteArray.decodeHealthMetrics(
        unix: Long,
        bpm: Long,
        source: WhoopProtocol.SampleSource,
    ): HealthMetricSample {
        val rrCount = (this[11].toInt() and 0xFF).coerceIn(0, 4)
        val rr = (0 until rrCount)
            .mapNotNull { index ->
                readU16Le(12 + index * 2).takeIf { it > 0 }
            }
        val hasSensorData = this[20].toInt() != 0
        val sensorOffset = 21
        val directSpo2 = if (hasSensorData) {
            this[45].toInt().and(0xFF).takeIf { it in 70..100 }?.toDouble()
        } else {
            null
        }
        val hasImu = this[46].toInt() != 0
        val movement = if (hasImu) {
            HealthMetricSample.movementFromGravity(
                readFloatLe(47),
                readFloatLe(51),
                readFloatLe(55),
            )
        } else if (hasSensorData) {
            HealthMetricSample.movementFromGravity(
                readFloatLe(sensorOffset + 12),
                readFloatLe(sensorOffset + 16),
                readFloatLe(sensorOffset + 20),
            )
        } else {
            null
        }
        return HealthMetricSample(
            time = Instant.ofEpochSecond(unix),
            heartRateBpm = bpm,
            source = source,
            rrIntervalsMillis = rr,
            spo2Percent = directSpo2,
            spo2RedRaw = if (hasSensorData) readU16Le(sensorOffset) else null,
            spo2IrRaw = if (hasSensorData) readU16Le(sensorOffset + 2) else null,
            skinTemperatureRaw = if (hasSensorData) readU16Le(sensorOffset + 4) else null,
            respiratoryRateRaw = if (hasSensorData) readU16Le(sensorOffset + 6) else null,
            signalQuality = if (hasSensorData) readU16Le(sensorOffset + 8) else null,
            worn = if (hasSensorData) this[sensorOffset + 10].toInt() != 0 else null,
            movementScore = movement,
        )
    }

    private fun ByteArray.decodeHistoryMetadata(): DecodedWhoopData.HistoryMetadata? {
        if (size < 18) return null
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

    private fun ByteArray.readU16Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.readFloatLe(offset: Int): Float =
        Float.fromBits(
            (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24),
        )
}

sealed interface DecodedWhoopData {
    data class HeartRate(
        val sample: WhoopProtocol.HeartRateSample,
        val healthMetrics: HealthMetricSample? = null,
    ) : DecodedWhoopData

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
