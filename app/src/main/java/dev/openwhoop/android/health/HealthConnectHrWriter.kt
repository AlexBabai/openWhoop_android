package dev.openwhoop.android.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import dev.openwhoop.android.ble.WhoopProtocol
import java.time.Duration
import java.time.ZoneOffset

class HealthConnectHrWriter(private val context: Context) {
    private val client: HealthConnectClient? by lazy {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(context)
            else -> null
        }
    }

    val permissions: Set<String> =
        setOf(HealthPermission.getWritePermission(HeartRateRecord::class))

    fun requestPermissionsContract() =
        PermissionController.createRequestPermissionResultContract()

    suspend fun isAvailable(): Boolean = client != null

    suspend fun hasPermissions(): Boolean =
        client?.permissionController
            ?.getGrantedPermissions()
            ?.containsAll(permissions) == true

    suspend fun write(samples: List<WhoopProtocol.HeartRateSample>): Int {
        val validSamples = samples
            .distinctBy { it.time }
            .sortedBy { it.time }
            .filter { it.bpm > 0 }
        if (validSamples.isEmpty()) return 0
        val healthConnectClient = requireNotNull(client) { "Health Connect is not available" }
        val start = validSamples.first().time
        val end = validSamples.last().time.plus(Duration.ofSeconds(1))
        val record = HeartRateRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            samples = validSamples.map {
                HeartRateRecord.Sample(
                    time = it.time,
                    beatsPerMinute = it.bpm,
                )
            },
            metadata = Metadata(
                device = Device(
                    manufacturer = "WHOOP",
                    model = "WHOOP 4.0",
                    type = Device.TYPE_WATCH,
                ),
                recordingMethod = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
            ),
        )
        healthConnectClient.insertRecords(listOf(record))
        return validSamples.size
    }
}
