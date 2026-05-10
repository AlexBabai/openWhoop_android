package dev.openwhoop.android.monitor

import dev.openwhoop.android.OpenWhoopLog
import dev.openwhoop.android.ble.WhoopBleClient
import dev.openwhoop.android.ble.WhoopBleEvent
import dev.openwhoop.android.health.EnabledHealthMetrics
import dev.openwhoop.android.health.HealthConnectSyncResult
import dev.openwhoop.android.health.HealthConnectVitalsWriter
import dev.openwhoop.android.health.HealthMetricSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class WhoopBackgroundMonitor(
    private val bleClient: WhoopBleClient,
    private val healthConnect: HealthConnectVitalsWriter,
    private val scope: CoroutineScope,
    private val enabledMetrics: () -> EnabledHealthMetrics,
    private val onStatus: (String) -> Unit,
    private val onSyncResult: (HealthConnectSyncResult) -> Unit,
) {
    private val bufferedMetrics = mutableListOf<HealthMetricSample>()
    private var observeJob: Job? = null
    private var syncJob: Job? = null

    val pendingSampleCount: Int
        get() = synchronized(bufferedMetrics) { bufferedMetrics.size }

    fun start() {
        if (observeJob?.isActive == true) return
        OpenWhoopLog.d(Tag, "Starting monitor")
        onStatus("24/7 monitor started")
        bleClient.startHealthMonitoring()
        observeJob = scope.launch {
            bleClient.events.collect { event ->
                when (event) {
                    WhoopBleEvent.Ready -> {
                        OpenWhoopLog.d(Tag, "BLE ready; requesting history sync")
                        bleClient.syncHistory()
                    }
                    else -> Unit
                }
            }
        }
        syncJob = scope.launch {
            while (true) {
                delay(SyncInterval)
                flush(enabledMetrics())
            }
        }
    }

    fun stop() {
        OpenWhoopLog.d(Tag, "Stopping monitor")
        observeJob?.cancel()
        syncJob?.cancel()
        observeJob = null
        syncJob = null
        bleClient.stopHealthMonitoring()
        onStatus("24/7 monitor stopped")
    }

    fun addMetric(sample: HealthMetricSample) {
        synchronized(bufferedMetrics) {
            bufferedMetrics += sample
            if (bufferedMetrics.size > MaxBufferedMetrics) {
                bufferedMetrics.subList(0, bufferedMetrics.size - MaxBufferedMetrics).clear()
            }
            OpenWhoopLog.d(
                Tag,
                "Buffered metric time=${sample.time} hr=${sample.heartRateBpm} source=${sample.source} " +
                    "sensor=${sample.spo2RedRaw != null || sample.skinTemperatureRaw != null} pending=${bufferedMetrics.size}",
            )
        }
    }

    suspend fun flush(enabledMetrics: EnabledHealthMetrics = EnabledHealthMetrics()) {
        val snapshot = synchronized(bufferedMetrics) {
            bufferedMetrics.toList().also { bufferedMetrics.clear() }
        }
        OpenWhoopLog.d(Tag, "Flush requested snapshot=${snapshot.size} enabled=$enabledMetrics")
        if (snapshot.isEmpty()) {
            onStatus("No validated Health Connect samples pending")
            return
        }
        runCatching {
            healthConnect.write(snapshot, enabledMetrics)
        }.onSuccess { result ->
            OpenWhoopLog.d(
                Tag,
                "Flush success inserted=${result.insertedRecords} accepted=${result.validated.acceptedSamples} " +
                    "rejected=${result.validated.rejectedSamples} records=${result.validated.totalRecords}",
            )
            onSyncResult(result)
            onStatus(
                "Synced ${result.insertedRecords} Health Connect record(s); " +
                    "accepted ${result.validated.acceptedSamples}, rejected ${result.validated.rejectedSamples}",
            )
        }.onFailure { error ->
            OpenWhoopLog.e(Tag, "Flush failed", error)
            synchronized(bufferedMetrics) {
                bufferedMetrics += snapshot
            }
            onStatus("Background Health Connect sync failed: ${error.message}")
        }
    }

    companion object {
        private const val Tag = "WhoopBackgroundMonitor"
        private val SyncInterval = 15.minutes
        private const val MaxBufferedMetrics = 7_200
    }
}
