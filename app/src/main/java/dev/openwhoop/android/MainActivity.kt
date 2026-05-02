package dev.openwhoop.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.openwhoop.android.ble.WhoopProtocol
import dev.openwhoop.android.ble.WhoopScanResult
import dev.openwhoop.android.ui.theme.OpenWhoopTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val viewModel: OpenWhoopViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenWhoopTheme {
                val state by viewModel.uiState.collectAsState()
                val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    viewModel.refreshPermissionState()
                }
                val healthPermissionLauncher = rememberLauncherForActivityResult(
                    viewModel.healthConnectPermissionsContract(),
                ) {
                    viewModel.refreshPermissionState()
                }

                LaunchedEffect(Unit) {
                    viewModel.refreshPermissionState()
                }

                OpenWhoopApp(
                    state = state,
                    onRequestBluetoothPermissions = {
                        bluetoothPermissionLauncher.launch(viewModel.bluetoothPermissions)
                    },
                    onRequestHealthPermissions = {
                        healthPermissionLauncher.launch(viewModel.healthConnectPermissions)
                    },
                    onScan = viewModel::startScan,
                    onStopScan = viewModel::stopScan,
                    onConnect = { viewModel.connect(it.device) },
                    onStartRealtime = viewModel::startRealtimeHr,
                    onStopRealtime = viewModel::stopRealtimeHr,
                    onSyncHistory = viewModel::syncHistory,
                    onWriteHealthConnect = viewModel::writeHeartRateToHealthConnect,
                )
            }
        }
    }
}

@Composable
private fun OpenWhoopApp(
    state: OpenWhoopUiState,
    onRequestBluetoothPermissions: () -> Unit,
    onRequestHealthPermissions: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (WhoopScanResult) -> Unit,
    onStartRealtime: () -> Unit,
    onStopRealtime: () -> Unit,
    onSyncHistory: () -> Unit,
    onWriteHealthConnect: () -> Unit,
) {
    Scaffold(
        topBar = { OpenWhoopTopBar() },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StatusCard(state)
            }
            item {
                PermissionCard(
                    state = state,
                    onRequestBluetoothPermissions = onRequestBluetoothPermissions,
                    onRequestHealthPermissions = onRequestHealthPermissions,
                )
            }
            item {
                ScanCard(
                    state = state,
                    onScan = onScan,
                    onStopScan = onStopScan,
                )
            }
            items(state.devices) { device ->
                DeviceCard(device, onConnect)
            }
            item {
                SyncCard(
                    state = state,
                    onStartRealtime = onStartRealtime,
                    onStopRealtime = onStopRealtime,
                    onSyncHistory = onSyncHistory,
                    onWriteHealthConnect = onWriteHealthConnect,
                )
            }
            item {
                SamplesCard(state.samples)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenWhoopTopBar() {
    TopAppBar(
        title = {
            Column {
                Text("OpenWhoop")
                Text(
                    "WHOOP 4 HR sync",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun StatusCard(state: OpenWhoopUiState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Favorite, contentDescription = null)
                Text(
                    text = state.latestBpm?.let { "$it bpm" } ?: "-- bpm",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(state.status, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("Connection", if (state.isReady) "Ready" else if (state.isConnected) "Connected" else "Idle")
                Metric("Samples", state.samples.size.toString())
                Metric("Health Connect", state.syncedToHealthConnect.toString())
            }
            if (state.isScanning || state.isSyncingHistory) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PermissionCard(
    state: OpenWhoopUiState,
    onRequestBluetoothPermissions: () -> Unit,
    onRequestHealthPermissions: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(Icons.Rounded.HealthAndSafety, "Permissions")
            Text("Bluetooth: ${if (state.hasBluetoothPermissions) "granted" else "required"}")
            Text(
                "Health Connect: " + when {
                    !state.healthConnectAvailable -> "not available on this device"
                    state.hasHealthConnectPermissions -> "write HR granted"
                    else -> "write HR required"
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onRequestBluetoothPermissions,
                    enabled = !state.hasBluetoothPermissions,
                ) {
                    Text("Bluetooth")
                }
                Button(
                    onClick = onRequestHealthPermissions,
                    enabled = state.healthConnectAvailable && !state.hasHealthConnectPermissions,
                ) {
                    Text("Health Connect")
                }
            }
        }
    }
}

@Composable
private fun ScanCard(
    state: OpenWhoopUiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(Icons.Rounded.Bluetooth, "Find WHOOP 4")
            Text("Scans for the WHOOP Gen 4 service and compatible standard HR advertisements.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onScan,
                    enabled = state.hasBluetoothPermissions && !state.isScanning,
                ) {
                    Text("Scan")
                }
                OutlinedButton(
                    onClick = onStopScan,
                    enabled = state.isScanning,
                ) {
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: WhoopScanResult,
    onConnect: (WhoopScanResult) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text("${device.device.address} • RSSI ${device.rssi}")
            }
            Button(onClick = { onConnect(device) }) {
                Text("Connect")
            }
        }
    }
}

@Composable
private fun SyncCard(
    state: OpenWhoopUiState,
    onStartRealtime: () -> Unit,
    onStopRealtime: () -> Unit,
    onSyncHistory: () -> Unit,
    onWriteHealthConnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(Icons.Rounded.Sync, "Sync")
            Text("Realtime HR uses WHOOP command 0x03. History sync sends the Gen 4 high-frequency sync sequence from openwhoop.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStartRealtime,
                    enabled = state.isReady,
                ) {
                    Text("Start HR")
                }
                OutlinedButton(
                    onClick = onStopRealtime,
                    enabled = state.isReady,
                ) {
                    Text("Stop HR")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onSyncHistory,
                    enabled = state.isReady && !state.isSyncingHistory,
                ) {
                    Text("Sync history")
                }
                Button(
                    onClick = onWriteHealthConnect,
                    enabled = state.hasHealthConnectPermissions && state.samples.isNotEmpty(),
                ) {
                    Text("Write Health Connect")
                }
            }
        }
    }
}

@Composable
private fun SamplesCard(samples: List<WhoopProtocol.HeartRateSample>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Recent HR samples", style = MaterialTheme.typography.titleLarge)
            if (samples.isEmpty()) {
                Text("No samples yet.")
            } else {
                samples.take(8).forEach { sample ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${sample.bpm} bpm")
                        Text("${sample.source.name} • ${sample.time.formatLocalTime()}")
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SectionTitle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null)
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
    Spacer(Modifier.height(4.dp))
}

private fun java.time.Instant.formatLocalTime(): String =
    DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(this)
