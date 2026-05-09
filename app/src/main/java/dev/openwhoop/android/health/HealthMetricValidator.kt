package dev.openwhoop.android.health

class HealthMetricValidator {
    fun validate(samples: List<HealthMetricSample>): ValidatedHealthMetrics {
        val sorted = samples
            .distinctBy { it.time }
            .sortedBy { it.time }
        val worn = sorted.filter(::isWorn)
        val lowMovement = worn.filter(::isLowMovement)
        val heartRateSamples = worn.ifEmpty {
            sorted.filter {
                it.heartRateBpm?.let { bpm -> bpm in HealthMetricSample.MinHeartRate..HealthMetricSample.MaxHeartRate } == true
            }
        }
        val heartRate = heartRateSamples
            .mapNotNull { it.toHeartRateSample() }
        val hrv = lowMovement.mapNotNull { sample ->
            HealthMetricSample.rmssd(sample.rrIntervalsMillis)?.let { TimedDouble(sample.time, it) }
        }
        val respiratoryRate = lowMovement.mapNotNull { sample ->
            sample.respiratoryRateBreathsPerMinute()?.let { TimedDouble(sample.time, it) }
        }
        val skinTemperature = lowMovement.mapNotNull { sample ->
            sample.skinTemperatureCelsius()?.let { TimedDouble(sample.time, it) }
        }
        return ValidatedHealthMetrics(
            heartRate = heartRate,
            hrvRmssd = hrv,
            spo2 = calculateSpo2(lowMovement),
            respiratoryRate = respiratoryRate,
            skinTemperatureCelsius = skinTemperature,
            acceptedSamples = acceptedSamples(heartRateSamples, lowMovement),
            rejectedSamples = sorted.size - acceptedSamples(heartRateSamples, lowMovement),
        )
    }

    private fun acceptedSamples(
        heartRateSamples: List<HealthMetricSample>,
        lowMovement: List<HealthMetricSample>,
    ): Int = (heartRateSamples + lowMovement).distinctBy { it.time }.size

    private fun isWorn(sample: HealthMetricSample): Boolean =
        sample.worn != false &&
            sample.heartRateBpm?.let { it in HealthMetricSample.MinHeartRate..HealthMetricSample.MaxHeartRate } != false &&
            (sample.signalQuality == null || sample.signalQuality <= MaxSignalNoise)

    private fun isLowMovement(sample: HealthMetricSample): Boolean =
        sample.movementScore?.let { it <= MaxGravityMovementScore } != false

    private fun calculateSpo2(samples: List<HealthMetricSample>): List<TimedDouble> {
        val direct = samples.mapNotNull { sample ->
            sample.directSpo2Percent()?.let { TimedDouble(sample.time, it) }
        }
        val rawWindowScores = samples
            .mapNotNull { sample ->
                val red = sample.spo2RedRaw?.takeIf { it > 0 } ?: return@mapNotNull null
                val ir = sample.spo2IrRaw?.takeIf { it > 0 } ?: return@mapNotNull null
                Spo2RawSample(sample.time, red.toDouble(), ir.toDouble())
            }
            .windowed(Spo2WindowSize, partialWindows = false)
            .mapNotNull(::calculateRawSpo2)
        return (direct + rawWindowScores)
            .distinctBy { it.time }
            .sortedBy { it.time }
    }

    private fun calculateRawSpo2(window: List<Spo2RawSample>): TimedDouble? {
        val meanRed = window.map { it.red }.average().takeIf { it > 0.0 } ?: return null
        val meanIr = window.map { it.ir }.average().takeIf { it > 0.0 } ?: return null
        val acRed = window.standardDeviation { it.red }
        val acIr = window.standardDeviation { it.ir }
        if (acRed < MinSpo2Ac || acIr < MinSpo2Ac) return null
        val ratio = (acRed / meanRed) / (acIr / meanIr)
        val spo2 = (110.0 - 25.0 * ratio).coerceIn(70.0, 100.0)
        return TimedDouble(window.last().time, spo2)
    }

    private fun List<Spo2RawSample>.standardDeviation(selector: (Spo2RawSample) -> Double): Double {
        val values = map(selector)
        val mean = values.average()
        return kotlin.math.sqrt(values.map { (it - mean) * (it - mean) }.average())
    }

    companion object {
        private const val MaxGravityMovementScore = 0.35
        private const val MaxSignalNoise = 4_000
        private const val Spo2WindowSize = 30
        private const val MinSpo2Ac = 0.001
    }
}

data class ValidatedHealthMetrics(
    val heartRate: List<dev.openwhoop.android.ble.WhoopProtocol.HeartRateSample> = emptyList(),
    val hrvRmssd: List<TimedDouble> = emptyList(),
    val spo2: List<TimedDouble> = emptyList(),
    val respiratoryRate: List<TimedDouble> = emptyList(),
    val skinTemperatureCelsius: List<TimedDouble> = emptyList(),
    val acceptedSamples: Int = 0,
    val rejectedSamples: Int = 0,
) {
    val totalRecords: Int
        get() = heartRate.size + hrvRmssd.size + spo2.size + respiratoryRate.size + skinTemperatureCelsius.size
}

private data class Spo2RawSample(
    val time: java.time.Instant,
    val red: Double,
    val ir: Double,
)
