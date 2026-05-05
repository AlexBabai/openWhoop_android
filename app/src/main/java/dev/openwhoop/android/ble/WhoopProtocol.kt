package dev.openwhoop.android.ble

import java.time.Instant
import java.util.UUID

object WhoopProtocol {
    val ServiceUuid: UUID = UUID.fromString("61080001-8d6d-82b8-614a-1c8cb0f8dcc6")
    val CmdToStrapUuid: UUID = UUID.fromString("61080002-8d6d-82b8-614a-1c8cb0f8dcc6")
    val CmdFromStrapUuid: UUID = UUID.fromString("61080003-8d6d-82b8-614a-1c8cb0f8dcc6")
    val EventsFromStrapUuid: UUID = UUID.fromString("61080004-8d6d-82b8-614a-1c8cb0f8dcc6")
    val DataFromStrapUuid: UUID = UUID.fromString("61080005-8d6d-82b8-614a-1c8cb0f8dcc6")
    val MemfaultUuid: UUID = UUID.fromString("61080007-8d6d-82b8-614a-1c8cb0f8dcc6")
    val StandardHeartRateServiceUuid: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val StandardHeartRateMeasurementUuid: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    private const val MetadataHistoryEnd = 2
    private const val MetadataHistoryComplete = 3

    data class HeartRateSample(
        val time: Instant,
        val bpm: Long,
        val source: SampleSource,
    )

    enum class SampleSource {
        Realtime,
        History,
    }

    fun isHistoryFinished(metadataType: Int): Boolean =
        metadataType == MetadataHistoryEnd || metadataType == MetadataHistoryComplete
}
