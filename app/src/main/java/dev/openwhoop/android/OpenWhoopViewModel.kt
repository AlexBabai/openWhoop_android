package dev.openwhoop.android

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.openwhoop.android.algos.AlgorithmStats
import dev.openwhoop.android.algos.WhoopAlgosNative
import dev.openwhoop.android.ble.WhoopBleClient
import dev.openwhoop.android.ble.WhoopBleEvent
import dev.openwhoop.android.ble.WhoopProtocol
import dev.openwhoop.android.ble.WhoopScanResult
import dev.openwhoop.android.health.HealthConnectVitalsWriter
import dev.openwhoop.android.health.HealthMetricSample
import dev.openwhoop.android.monitor.WhoopBackgroundMonitor
import dev.openwhoop.android.monitor.WhoopMonitorService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OpenWhoopViewModel(application: Application) : AndroidViewModel(application) {
    private val bleClient = WhoopBleClient(application)
    private val healthConnect = HealthConnectVitalsWriter(application)
    private val backgroundMonitor = WhoopBackgroundMonitor(
        bleClient = bleClient,
        healthConnect = healthConnect,
        scope = viewModelScope,
        onStatus = { status -> _uiState.update { it.copy(status = status) } },
        onSyncResult = { result ->
            _uiState.update {
                it.copy(
                    syncedToHealthConnect = it.syncedToHealthConnect + result.insertedRecords,
                    validatedMetrics = result.validated.totalRecords,
                    rejectedMetrics = it.rejectedMetrics + result.validated.rejectedSamples,
                )
            }
        },
    )
    private var scanJob: Job? = null

    private val _uiState = MutableStateFlow(OpenWhoopUiState())
    val uiState: StateFlow<OpenWhoopUiState> = _uiState.asStateFlow()

    val bluetoothPermissions: Array<String> = bleClient.requiredPermissions()
    val healthConnectPermissions: Set<String> = healthConnect.permissions
    fun healthConnectPermissionsContract() = healthConnect.requestPermissionsContract()

    init {
        observeBleEvents()
        refreshPermissionState()
    }

    fun refreshPermissionState() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    hasBluetoothPermissions = bleClient.hasRequiredPermissions(),
                    healthConnectAvailable = healthConnect.isAvailable(),
                    hasHealthConnectPermissions = healthConnect.hasPermissions(),
                )
            }
        }
    }

    fun startScan() {
        if (!bleClient.hasRequiredPermissions()) {
            _uiState.update { it.copy(status = "Bluetooth permission is required before scanning") }
            return
        }
        scanJob?.cancel()
        _uiState.update { it.copy(isScanning = true, status = "Scanning for WHOOP strap…", devices = emptyList()) }
        scanJob = viewModelScope.launch {
            bleClient.scan().collect { result ->
                _uiState.update { state ->
                    val devices = (state.devices.filterNot { it.device.address == result.device.address } + result)
                        .sortedByDescending { it.rssi }
                    state.copy(devices = devices, status = "Found ${devices.size} WHOOP candidate(s)")
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.update { it.copy(isScanning = false, status = "Scan stopped") }
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        bleClient.connect(device)
    }

    fun startRealtimeHr() {
        bleClient.startRealtimeHeartRate()
        _uiState.update { it.copy(status = "Realtime HR streaming requested") }
    }

    fun stopRealtimeHr() {
        bleClient.stopRealtimeHeartRate()
        _uiState.update { it.copy(status = "Realtime HR stop requested") }
    }

    fun syncHistory() {
        bleClient.syncHistory()
    }

    fun writeMetricsToHealthConnect() {
        viewModelScope.launch {
            runCatching {
                val metrics = _uiState.value.healthMetrics.takeIf { it.isNotEmpty() }
                    ?: _uiState.value.samples.map {
                        HealthMetricSample(
                            time = it.time,
                            heartRateBpm = it.bpm,
                            source = it.source,
                            worn = true,
                        )
                    }
                val result = healthConnect.write(metrics)
                _uiState.update {
                    it.copy(
                        syncedToHealthConnect = it.syncedToHealthConnect + result.insertedRecords,
                        validatedMetrics = result.validated.totalRecords,
                        rejectedMetrics = it.rejectedMetrics + result.validated.rejectedSamples,
                        status = "Wrote ${result.insertedRecords} validated Health Connect record(s)",
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(status = "Health Connect write failed: ${error.message}") }
            }
        }
    }

    fun startBackgroundMonitor() {
        if (!_uiState.value.isReady) {
            _uiState.update { it.copy(status = "Connect to a WHOOP strap before starting the monitor") }
            return
        }
        backgroundMonitor.start()
        WhoopMonitorService.start(getApplication())
        _uiState.update { it.copy(isBackgroundMonitoring = true) }
    }

    fun stopBackgroundMonitor() {
        backgroundMonitor.stop()
        WhoopMonitorService.stop(getApplication())
        _uiState.update { it.copy(isBackgroundMonitoring = false) }
    }

    override fun onCleared() {
        super.onCleared()
        bleClient.close()
    }

    private fun observeBleEvents() {
        viewModelScope.launch {
            bleClient.events.collect { event ->
                when (event) {
                    is WhoopBleEvent.Connecting -> {
                        _uiState.update { it.copy(status = "Connecting to ${event.name}…") }
                    }
                    is WhoopBleEvent.Connected -> {
                        _uiState.update { it.copy(isConnected = true, status = "Connected to ${event.name}") }
                    }
                    WhoopBleEvent.Ready -> {
                        _uiState.update { it.copy(isReady = true, status = "WHOOP GATT subscriptions are ready") }
                    }
                    WhoopBleEvent.Disconnected -> {
                        _uiState.update { it.copy(isConnected = false, isReady = false, status = "Disconnected") }
                    }
                    WhoopBleEvent.HistorySyncStarted -> {
                        _uiState.update { it.copy(isSyncingHistory = true, status = "History sync started") }
                    }
                    WhoopBleEvent.HistorySyncFinished -> {
                        _uiState.update { it.copy(isSyncingHistory = false, status = "History sync finished") }
                    }
                    is WhoopBleEvent.HeartRate -> addSample(event.sample, event.healthMetrics)
                    is WhoopBleEvent.Error -> {
                        _uiState.update { it.copy(isSyncingHistory = false, status = event.message) }
                    }
                }
            }
        }
    }

    private fun addSample(
        sample: WhoopProtocol.HeartRateSample,
        healthMetrics: HealthMetricSample?,
    ) {
        val samples = (_uiState.value.samples + sample)
            .distinctBy { it.time }
            .sortedByDescending { it.time }
            .take(500)
        val metricSamples = (healthMetrics?.let { _uiState.value.healthMetrics + it } ?: _uiState.value.healthMetrics)
            .distinctBy { it.time }
            .sortedByDescending { it.time }
            .take(2_000)
        val algorithmStats = runCatching { WhoopAlgosNative.calculate(samples) }
            .getOrElse { AlgorithmStats() }
        _uiState.update { state ->
            state.copy(
                samples = samples,
                healthMetrics = metricSamples,
                latestBpm = sample.bpm,
                algorithmStats = algorithmStats,
                status = "Received ${sample.source.name.lowercase()} HR: ${sample.bpm} bpm",
            )
        }
    }
}

data class OpenWhoopUiState(
    val hasBluetoothPermissions: Boolean = false,
    val healthConnectAvailable: Boolean = false,
    val hasHealthConnectPermissions: Boolean = false,
    val isScanning: Boolean = false,
    val isConnected: Boolean = false,
    val isReady: Boolean = false,
    val isSyncingHistory: Boolean = false,
    val isBackgroundMonitoring: Boolean = false,
    val devices: List<WhoopScanResult> = emptyList(),
    val latestBpm: Long? = null,
    val samples: List<WhoopProtocol.HeartRateSample> = emptyList(),
    val healthMetrics: List<HealthMetricSample> = emptyList(),
    val algorithmStats: AlgorithmStats = AlgorithmStats(),
    val syncedToHealthConnect: Int = 0,
    val validatedMetrics: Int = 0,
    val rejectedMetrics: Int = 0,
    val status: String = "Ready",
)
