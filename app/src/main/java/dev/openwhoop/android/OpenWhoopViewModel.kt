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
import dev.openwhoop.android.health.HealthConnectHrWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OpenWhoopViewModel(application: Application) : AndroidViewModel(application) {
    private val bleClient = WhoopBleClient(application)
    private val healthConnect = HealthConnectHrWriter(application)
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

    fun writeHeartRateToHealthConnect() {
        viewModelScope.launch {
            runCatching {
                val written = healthConnect.write(_uiState.value.samples)
                _uiState.update {
                    it.copy(
                        syncedToHealthConnect = it.syncedToHealthConnect + written,
                        status = "Wrote $written HR samples to Health Connect",
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(status = "Health Connect write failed: ${error.message}") }
            }
        }
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
                    is WhoopBleEvent.HeartRate -> addSample(event.sample)
                    is WhoopBleEvent.Error -> {
                        _uiState.update { it.copy(isSyncingHistory = false, status = event.message) }
                    }
                }
            }
        }
    }

    private fun addSample(sample: WhoopProtocol.HeartRateSample) {
        _uiState.update { state ->
            val samples = (state.samples + sample)
                .distinctBy { it.time }
                .sortedByDescending { it.time }
                .take(500)
            val algorithmStats = runCatching { WhoopAlgosNative.calculate(samples) }
                .getOrElse { AlgorithmStats() }
            state.copy(
                samples = samples,
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
    val devices: List<WhoopScanResult> = emptyList(),
    val latestBpm: Long? = null,
    val samples: List<WhoopProtocol.HeartRateSample> = emptyList(),
    val algorithmStats: AlgorithmStats = AlgorithmStats(),
    val syncedToHealthConnect: Int = 0,
    val status: String = "Ready",
)
