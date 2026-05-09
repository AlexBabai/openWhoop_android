package dev.openwhoop.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dev.openwhoop.android.ble.WhoopProtocol.HeartRateSample
import dev.openwhoop.android.health.HealthMetricSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.UUID

class WhoopBleClient(private val context: Context) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val frameDecoder = WhoopFrameDecoder()

    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var nextSequence = 0

    private val _events = MutableSharedFlow<WhoopBleEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<WhoopBleEvent> = _events

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    fun scan(): Flow<WhoopScanResult> = callbackFlow {
        if (!hasRequiredPermissions()) {
            close(SecurityException("Bluetooth permissions are not granted"))
            return@callbackFlow
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth LE scanner is not available"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val deviceName = result.device.name ?: result.scanRecord?.deviceName
                val hasWhoopService = result.scanRecord
                    ?.serviceUuids
                    ?.contains(ParcelUuid(WhoopProtocol.ServiceUuid)) == true
                if (hasWhoopService || deviceName?.contains("WHOOP", ignoreCase = true) == true) {
                    trySend(WhoopScanResult(result.device, deviceName ?: "WHOOP", result.rssi))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(WhoopProtocol.ServiceUuid))
            .build()
        val standardHrFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(WhoopProtocol.StandardHeartRateServiceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter, standardHrFilter), settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasRequiredPermissions()) {
            emit(WhoopBleEvent.Error("Bluetooth permissions are not granted"))
            return
        }
        close()
        emit(WhoopBleEvent.Connecting(device.displayName()))
        frameDecoder.reset()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun startRealtimeHeartRate() {
        write(WhoopCodecNative.toggleRealtimeHr(nextSeq(), enabled = true))
    }

    @SuppressLint("MissingPermission")
    fun stopRealtimeHeartRate() {
        write(WhoopCodecNative.toggleRealtimeHr(nextSeq(), enabled = false))
    }

    fun maintainBackgroundConnection() {
        if (commandCharacteristic != null) {
            startRealtimeHeartRate()
        }
    }

    @SuppressLint("MissingPermission")
    fun syncHistory() {
        write(WhoopCodecNative.helloHarvard(nextSeq()))
        write(WhoopCodecNative.setTime(nextSeq()))
        write(WhoopCodecNative.getName(nextSeq()))
        write(WhoopCodecNative.enterHighFreqSync(nextSeq()))
        write(WhoopCodecNative.historyStart(nextSeq()))
        emit(WhoopBleEvent.HistorySyncStarted)
    }

    @SuppressLint("MissingPermission")
    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        commandCharacteristic = null
        nextSequence = 0
        frameDecoder.reset()
    }

    @SuppressLint("MissingPermission")
    private fun write(bytes: ByteArray?) {
        if (bytes == null) {
            emit(WhoopBleEvent.Error("WHOOP codec could not build command"))
            return
        }
        val characteristic = commandCharacteristic
        val bluetoothGatt = gatt
        if (characteristic == null || bluetoothGatt == null) {
            emit(WhoopBleEvent.Error("WHOOP command characteristic is not ready"))
            return
        }
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            bluetoothGatt.writeCharacteristic(characteristic)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit(WhoopBleEvent.Error("GATT connection failed: $status"))
                close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    emit(WhoopBleEvent.Connected(gatt.device.displayName()))
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    emit(WhoopBleEvent.Disconnected)
                    close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit(WhoopBleEvent.Error("GATT service discovery failed: $status"))
                return
            }
            val service = gatt.getService(WhoopProtocol.ServiceUuid)
            if (service == null) {
                emit(WhoopBleEvent.Error("WHOOP Gen 4 service was not found"))
                gatt.getService(WhoopProtocol.StandardHeartRateServiceUuid)?.let { hrService ->
                    subscribe(gatt, hrService, WhoopProtocol.StandardHeartRateMeasurementUuid)
                    emit(WhoopBleEvent.Ready)
                }
                return
            }
            commandCharacteristic = service.getCharacteristic(WhoopProtocol.CmdToStrapUuid)
            subscribe(gatt, service, WhoopProtocol.DataFromStrapUuid)
            subscribe(gatt, service, WhoopProtocol.CmdFromStrapUuid)
            subscribe(gatt, service, WhoopProtocol.EventsFromStrapUuid)
            subscribe(gatt, service, WhoopProtocol.MemfaultUuid)
            emit(WhoopBleEvent.Ready)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleNotification(characteristic.uuid, characteristic.value ?: return)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(
        gatt: BluetoothGatt,
        service: BluetoothGattService,
        uuid: UUID,
    ) {
        val characteristic = service.getCharacteristic(uuid) ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(ClientCharacteristicConfigUuid) ?: return
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        if (uuid == WhoopProtocol.StandardHeartRateMeasurementUuid) {
            StandardHeartRateParser.parse(value)?.let { emit(WhoopBleEvent.HeartRate(it)) }
            return
        }

        when (val decoded = frameDecoder.decode(value)) {
            is DecodedWhoopData.HeartRate -> emit(
                WhoopBleEvent.HeartRate(
                    sample = decoded.sample,
                    healthMetrics = decoded.healthMetrics,
                ),
            )
            is DecodedWhoopData.HistoryMetadata -> {
                if (WhoopProtocol.isHistoryFinished(decoded.metadataType)) {
                    write(WhoopCodecNative.historyEnd(nextSeq(), decoded.endData))
                    emit(WhoopBleEvent.HistorySyncFinished)
                }
            }
            is DecodedWhoopData.CommandResponse -> Unit
            null -> Unit
        }
    }

    private fun nextSeq(): Int {
        val sequence = nextSequence
        nextSequence = (nextSequence + 1) and 0xFF
        return sequence
    }

    private fun emit(event: WhoopBleEvent) {
        scope.launch { _events.emit(event) }
    }

    companion object {
        private val ClientCharacteristicConfigUuid: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

data class WhoopScanResult(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int,
)

sealed interface WhoopBleEvent {
    data class Connecting(val name: String) : WhoopBleEvent
    data class Connected(val name: String) : WhoopBleEvent
    data object Ready : WhoopBleEvent
    data object Disconnected : WhoopBleEvent
    data object HistorySyncStarted : WhoopBleEvent
    data object HistorySyncFinished : WhoopBleEvent
    data class HeartRate(
        val sample: HeartRateSample,
        val healthMetrics: HealthMetricSample? = null,
    ) : WhoopBleEvent
    data class Error(val message: String) : WhoopBleEvent
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.displayName(): String =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            name
        } else {
            @Suppress("DEPRECATION")
            name
        }
    }.getOrNull() ?: address ?: "WHOOP"
