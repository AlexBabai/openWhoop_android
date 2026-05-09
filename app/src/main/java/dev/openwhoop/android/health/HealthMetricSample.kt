package dev.openwhoop.android.health

import dev.openwhoop.android.ble.WhoopProtocol
import java.time.Instant
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class HealthMetricSample(
    val time: Instant,
    val heartRateBpm: Long?,
    val source: WhoopProtocol.SampleSource,
    val rrIntervalsMillis: List<Int> = emptyList(),
    val spo2Percent: Double? = null,
    val spo2RedRaw: Int? = null,
    val spo2IrRaw: Int? = null,
    val respiratoryRateRaw: Int? = null,
    val skinTemperatureRaw: Int? = null,
    val movementScore: Double? = null,
    val worn: Boolean? = null,
    val signalQuality: Int? = null,
) {
    fun respiratoryRateBreathsPerMinute(): Double? {
        val raw = respiratoryRateRaw ?: return null
        val rate = when {
            raw in 4..60 -> raw.toDouble()
            raw in 40..600 -> raw / 10.0
            raw in 400..6_000 -> raw / 100.0
            else -> return null
        }
        return rate.takeIf { it in 4.0..60.0 }
    }

    fun skinTemperatureCelsius(): Double? =
        skinTemperatureRaw
            ?.takeIf { it > 0 }
            ?.let { it * SkinTempRawToCelsius }
            ?.takeIf { it in 20.0..45.0 }

    fun directSpo2Percent(): Double? =
        spo2Percent?.takeIf { it in 70.0..100.0 }

    fun toHeartRateSample(): WhoopProtocol.HeartRateSample? {
        val bpm = heartRateBpm?.takeIf { it in MinHeartRate..MaxHeartRate } ?: return null
        return WhoopProtocol.HeartRateSample(time, bpm, source)
    }

    companion object {
        const val MinHeartRate = 25L
        const val MaxHeartRate = 240L
        private const val SkinTempRawToCelsius = 0.04

        fun rmssd(rrIntervalsMillis: List<Int>): Double? {
            val valid = rrIntervalsMillis.filter { it in 300..2_000 }
            if (valid.size < 2) return null
            val meanSquaredDiff = valid
                .zipWithNext { previous, current -> (current - previous).toDouble().pow(2.0) }
                .average()
            return sqrt(meanSquaredDiff).takeIf { it in 1.0..200.0 }
        }

        fun movementFromGravity(x: Float, y: Float, z: Float): Double {
            val magnitude = sqrt(x.toDouble().pow(2.0) + y.toDouble().pow(2.0) + z.toDouble().pow(2.0))
            return abs(magnitude - 1.0)
        }
    }
}

data class TimedDouble(
    val time: Instant,
    val value: Double,
)
