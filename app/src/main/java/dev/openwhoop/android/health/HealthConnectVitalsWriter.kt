package dev.openwhoop.android.health

import android.content.Context
import androidx.health.connect.client.ExperimentalDeduplicationApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import java.time.Duration
import java.time.ZoneOffset

@OptIn(ExperimentalFeatureAvailabilityApi::class, ExperimentalDeduplicationApi::class)
class HealthConnectVitalsWriter(private val context: Context) {
    private val validator = HealthMetricValidator()
    private val client: HealthConnectClient? by lazy {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(context)
            else -> null
        }
    }

    val permissions: Set<String> =
        setOf(
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getWritePermission(OxygenSaturationRecord::class),
            HealthPermission.getWritePermission(RespiratoryRateRecord::class),
            HealthPermission.getWritePermission(SkinTemperatureRecord::class),
        )

    fun requestPermissionsContract() =
        PermissionController.createRequestPermissionResultContract()

    suspend fun isAvailable(): Boolean = client != null

    suspend fun hasPermissions(): Boolean =
        client?.permissionController
            ?.getGrantedPermissions()
            ?.containsAll(permissions) == true

    suspend fun write(
        samples: List<HealthMetricSample>,
        enabledMetrics: EnabledHealthMetrics = EnabledHealthMetrics(),
    ): HealthConnectSyncResult {
        val healthConnectClient = requireNotNull(client) { "Health Connect is not available" }
        val validated = validator.validate(samples)
        val records = buildRecords(healthConnectClient, validated, enabledMetrics)
        if (records.isNotEmpty()) {
            healthConnectClient.insertRecords(records)
        }
        return HealthConnectSyncResult(
            insertedRecords = records.size,
            validated = validated,
        )
    }

    private fun buildRecords(
        healthConnectClient: HealthConnectClient,
        validated: ValidatedHealthMetrics,
        enabledMetrics: EnabledHealthMetrics,
    ): List<Record> {
        val records = mutableListOf<Record>()
        if (enabledMetrics.heartRate) {
            records += heartRateRecord(validated)
        }
        if (enabledMetrics.hrv) {
            records += validated.hrvRmssd.map { sample ->
                HeartRateVariabilityRmssdRecord(
                    time = sample.time,
                    zoneOffset = ZoneOffset.UTC,
                    heartRateVariabilityMillis = sample.value,
                    metadata = whoopMetadata("hrv-${sample.time.epochSecond}"),
                )
            }
        }
        if (enabledMetrics.spo2) {
            records += validated.spo2.map { sample ->
                OxygenSaturationRecord(
                    time = sample.time,
                    zoneOffset = ZoneOffset.UTC,
                    percentage = Percentage(sample.value),
                    metadata = whoopMetadata("spo2-${sample.time.epochSecond}"),
                )
            }
        }
        if (enabledMetrics.respiratoryRate) {
            records += validated.respiratoryRate.map { sample ->
                RespiratoryRateRecord(
                    time = sample.time,
                    zoneOffset = ZoneOffset.UTC,
                    rate = sample.value,
                    metadata = whoopMetadata("respiratory-${sample.time.epochSecond}"),
                )
            }
        }
        if (enabledMetrics.skinTemperature && hasSkinTemperatureFeature(healthConnectClient)) {
            skinTemperatureRecord(validated)?.let { records += it }
        }
        return records
    }

    private fun heartRateRecord(validated: ValidatedHealthMetrics): List<Record> {
        if (validated.heartRate.isEmpty()) return emptyList()
        val start = validated.heartRate.first().time
        val end = validated.heartRate.last().time.plus(Duration.ofSeconds(1))
        return listOf(
            HeartRateRecord(
                startTime = start,
                startZoneOffset = ZoneOffset.UTC,
                endTime = end,
                endZoneOffset = ZoneOffset.UTC,
                samples = validated.heartRate.map {
                    HeartRateRecord.Sample(
                        time = it.time,
                        beatsPerMinute = it.bpm,
                    )
                },
                metadata = whoopMetadata("hr-$start"),
            ),
        )
    }

    private fun skinTemperatureRecord(validated: ValidatedHealthMetrics): SkinTemperatureRecord? {
        val samples = validated.skinTemperatureCelsius
        if (samples.isEmpty()) return null
        val baseline = samples.map { it.value }.average()
        val start = samples.first().time
        val end = samples.last().time.plus(Duration.ofSeconds(1))
        return SkinTemperatureRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            metadata = whoopMetadata("skin-temp-$start"),
            deltas = samples.map {
                SkinTemperatureRecord.Delta(
                    time = it.time,
                    delta = TemperatureDelta.celsius(it.value - baseline),
                )
            },
            baseline = Temperature.celsius(baseline),
            measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST,
        )
    }

    private fun hasSkinTemperatureFeature(healthConnectClient: HealthConnectClient): Boolean =
        healthConnectClient.features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    private fun whoopMetadata(clientRecordId: String): Metadata =
        Metadata(
            device = Device(
                manufacturer = "WHOOP",
                model = "WHOOP 4.0",
                type = Device.TYPE_WATCH,
            ),
            recordingMethod = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
            clientRecordId = "openwhoop-$clientRecordId",
            clientRecordVersion = 1,
        )
}

data class HealthConnectSyncResult(
    val insertedRecords: Int,
    val validated: ValidatedHealthMetrics,
)
