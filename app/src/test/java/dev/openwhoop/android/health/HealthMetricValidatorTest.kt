package dev.openwhoop.android.health

import dev.openwhoop.android.ble.WhoopProtocol
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthMetricValidatorTest {
    private val validator = HealthMetricValidator()
    private val base = Instant.parse("2026-05-09T00:00:00Z")

    @Test
    fun filtersOffWristAndHighMovementVitals() {
        val result = validator.validate(
            listOf(
                sample(0, worn = true, movementScore = 0.05),
                sample(1, worn = false, movementScore = 0.02),
                sample(2, worn = true, movementScore = 0.80),
            ),
        )

        assertEquals(2, result.acceptedSamples)
        assertEquals(1, result.rejectedSamples)
        assertEquals(2, result.heartRate.size)
        assertEquals(1, result.hrvRmssd.size)
        assertEquals(1, result.respiratoryRate.size)
        assertEquals(1, result.skinTemperatureCelsius.size)
    }

    @Test
    fun excludesHighMovementHeartRateWhenFallbackIsDisabled() {
        val result = validator.validate(
            listOf(
                sample(0, worn = true, movementScore = 0.80),
                sample(1, worn = true, movementScore = 0.90),
            ),
            includeFallbackHeartRate = false,
        )

        assertEquals(2, result.acceptedSamples)
        assertEquals(0, result.rejectedSamples)
        assertEquals(0, result.heartRate.size)
    }

    @Test
    fun calculatesSpo2FromLowMovementRawWindow() {
        val samples = (0 until 30).map { index ->
            sample(
                second = index.toLong(),
                spo2RedRaw = 1_000 + (index % 5) * 5,
                spo2IrRaw = 2_000 + (index % 5) * 20,
            )
        }

        val result = validator.validate(samples)

        assertEquals(1, result.spo2.size)
        assertTrue(result.spo2.single().value in 94.0..100.0)
    }

    @Test
    fun acceptsHeartRateOnlySamplesWhenSensorFieldsAreMissing() {
        val result = validator.validate(
            listOf(
                HealthMetricSample(
                    time = base,
                    heartRateBpm = 72,
                    source = WhoopProtocol.SampleSource.Realtime,
                ),
                HealthMetricSample(
                    time = base.plusSeconds(1),
                    heartRateBpm = 74,
                    source = WhoopProtocol.SampleSource.Realtime,
                ),
            ),
        )

        assertEquals(2, result.acceptedSamples)
        assertEquals(0, result.rejectedSamples)
        assertEquals(2, result.heartRate.size)
    }

    @Test
    fun disablesHeartRateFallbackForRealtimeOnlyHealthConnectWrites() {
        val result = validator.validate(
            listOf(
                HealthMetricSample(
                    time = base,
                    heartRateBpm = 72,
                    source = WhoopProtocol.SampleSource.Realtime,
                ),
            ),
            includeFallbackHeartRate = false,
        )

        assertEquals(0, result.acceptedSamples)
        assertEquals(1, result.rejectedSamples)
        assertEquals(0, result.heartRate.size)
    }

    private fun sample(
        second: Long,
        worn: Boolean = true,
        movementScore: Double = 0.05,
        spo2RedRaw: Int = 1_000,
        spo2IrRaw: Int = 2_000,
    ) = HealthMetricSample(
        time = base.plusSeconds(second),
        heartRateBpm = 60,
        source = WhoopProtocol.SampleSource.History,
        rrIntervalsMillis = listOf(1_000, 1_020, 980),
        spo2RedRaw = spo2RedRaw,
        spo2IrRaw = spo2IrRaw,
        respiratoryRateRaw = 160,
        skinTemperatureRaw = 850,
        movementScore = movementScore,
        worn = worn,
        signalQuality = 100,
    )
}
