package dev.openwhoop.android.ble

import dev.openwhoop.android.OpenWhoopLog
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
import android.bluetooth.BluetoothStatusCodes
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque
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
    private val pendingDescriptors = ArrayDeque<BluetoothGattDescriptor>()
    private var descriptorWriteInFlight = false
    private var subscriptionsReady = false
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
        if (!subscriptionsReady) {
            OpenWhoopLog.w(Tag, "startRealtimeHeartRate requested before subscriptions are ready")
            emit(WhoopBleEvent.Error("WHOOP GATT subscriptions are not ready yet"))
            return
        }
        write("toggleRealtimeHr(true)", WhoopCodecNative.toggleRealtimeHr(nextSeq(), enabled = true))
    }

    @SuppressLint("MissingPermission")
    fun stopRealtimeHeartRate() {
        write("toggleRealtimeHr(false)", WhoopCodecNative.toggleRealtimeHr(nextSeq(), enabled = false))
    }

    @SuppressLint("MissingPermission")
    fun startHealthMonitoring() {
        if (!subscriptionsReady) {
            OpenWhoopLog.w(Tag, "startHealthMonitoring requested before subscriptions are ready")
            emit(WhoopBleEvent.Error("WHOOP GATT subscriptions are not ready yet"))
            return
        }
        scope.launch {
            OpenWhoopLog.d(Tag, "Starting WHOOP health monitor command sequence")
            writeSpaced("helloHarvard", WhoopCodecNative.helloHarvard(nextSeq()))
            writeSpaced("setTime", WhoopCodecNative.setTime(nextSeq()))
            writeSpaced("getName", WhoopCodecNative.getName(nextSeq()))
            writeSpaced("toggleR7DataCollection", WhoopCodecNative.toggleR7DataCollection(nextSeq()))
            writeSpaced("toggleImuMode(true)", WhoopCodecNative.toggleImuMode(nextSeq(), enabled = true))
            writeSpaced("toggleHistoricalImuMode(true)", WhoopCodecNative.toggleHistoricalImuMode(nextSeq(), enabled = true))
            repeat(OpticalEnableAttempts) {
                writeSpaced("enableOpticalData(true) attempt=${it + 1}", WhoopCodecNative.enableOpticalData(nextSeq(), enabled = true))
                writeSpaced("toggleOpticalMode(true) attempt=${it + 1}", WhoopCodecNative.toggleOpticalMode(nextSeq(), enabled = true))
            }
            writeSpaced("toggleGen4Feature73(true)", WhoopCodecNative.toggleGen4Feature73(nextSeq(), enabled = true))
            writeSpaced("toggleGen4Feature74(true)", WhoopCodecNative.toggleGen4Feature74(nextSeq(), enabled = true))
            writeSpaced("toggleRealtimeHr(true)", WhoopCodecNative.toggleRealtimeHr(nextSeq(), enabled = true))
            syncHistoryCommands()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopHealthMonitoring() {
        write("toggleRealtimeHr(false)", WhoopCodecNative.toggleRealtimeHr(nextSeq(), enabled = false))
        write("toggleGen4Feature74(false)", WhoopCodecNative.toggleGen4Feature74(nextSeq(), enabled = false))
        write("toggleGen4Feature73(false)", WhoopCodecNative.toggleGen4Feature73(nextSeq(), enabled = false))
        write("enableOpticalData(false)", WhoopCodecNative.enableOpticalData(nextSeq(), enabled = false))
        write("toggleOpticalMode(false)", WhoopCodecNative.toggleOpticalMode(nextSeq(), enabled = false))
        write("toggleImuMode(false)", WhoopCodecNative.toggleImuMode(nextSeq(), enabled = false))
        write("toggleHistoricalImuMode(false)", WhoopCodecNative.toggleHistoricalImuMode(nextSeq(), enabled = false))
    }

    fun maintainBackgroundConnection() {
        if (commandCharacteristic != null && subscriptionsReady) {
            startHealthMonitoring()
        }
    }

    @SuppressLint("MissingPermission")
    fun syncHistory() {
        if (!subscriptionsReady) {
            OpenWhoopLog.w(Tag, "syncHistory requested before subscriptions are ready")
            emit(WhoopBleEvent.Error("WHOOP GATT subscriptions are not ready yet"))
            return
        }
        scope.launch {
            syncHistoryCommands(includeHandshake = true)
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        commandCharacteristic = null
        pendingDescriptors.clear()
        descriptorWriteInFlight = false
        subscriptionsReady = false
        nextSequence = 0
        frameDecoder.reset()
    }

    @SuppressLint("MissingPermission")
    private fun write(label: String, bytes: ByteArray?) {
        if (bytes == null) {
            OpenWhoopLog.e(Tag, "WHOOP codec could not build command: $label")
            emit(WhoopBleEvent.Error("WHOOP codec could not build command"))
            return
        }
        val characteristic = commandCharacteristic
        val bluetoothGatt = gatt
        if (characteristic == null || bluetoothGatt == null) {
            OpenWhoopLog.w(Tag, "Command characteristic not ready for $label")
            emit(WhoopBleEvent.Error("WHOOP command characteristic is not ready"))
            return
        }
        OpenWhoopLog.d(Tag, "Writing command $label bytes=${bytes.toHexString()}")
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = bluetoothGatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
            OpenWhoopLog.d(Tag, "Command write requested label=$label status=$status")
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            bluetoothGatt.writeCharacteristic(characteristic)
        }
        if (!accepted) {
            OpenWhoopLog.w(Tag, "BluetoothGatt rejected command write $label")
            emit(WhoopBleEvent.Error("WHOOP command write was rejected: $label"))
        }
    }

    private suspend fun writeSpaced(label: String, bytes: ByteArray?) {
        write(label, bytes)
        delay(CommandSpacingMillis)
    }

    private suspend fun syncHistoryCommands(includeHandshake: Boolean = false) {
        if (includeHandshake) {
            writeSpaced("helloHarvard", WhoopCodecNative.helloHarvard(nextSeq()))
            writeSpaced("setTime", WhoopCodecNative.setTime(nextSeq()))
            writeSpaced("getName", WhoopCodecNative.getName(nextSeq()))
        }
        writeSpaced("enterHighFreqSync", WhoopCodecNative.enterHighFreqSync(nextSeq()))
        writeSpaced("historyStart", WhoopCodecNative.historyStart(nextSeq()))
        emit(WhoopBleEvent.HistorySyncStarted)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            OpenWhoopLog.d(Tag, "Connection state changed status=$status newState=$newState")
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
            OpenWhoopLog.d(Tag, "Services discovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit(WhoopBleEvent.Error("GATT service discovery failed: $status"))
                return
            }
            val service = gatt.getService(WhoopProtocol.ServiceUuid)
            if (service == null) {
                emit(WhoopBleEvent.Error("WHOOP Gen 4 service was not found"))
                gatt.getService(WhoopProtocol.StandardHeartRateServiceUuid)?.let { hrService ->
                    queueSubscription(gatt, hrService, WhoopProtocol.StandardHeartRateMeasurementUuid)
                    startNextDescriptorWrite(gatt)
                }
                return
            }
            commandCharacteristic = service.getCharacteristic(WhoopProtocol.CmdToStrapUuid)
            subscriptionsReady = false
            queueSubscription(gatt, service, WhoopProtocol.DataFromStrapUuid)
            queueSubscription(gatt, service, WhoopProtocol.CmdFromStrapUuid)
            queueSubscription(gatt, service, WhoopProtocol.EventsFromStrapUuid)
            queueSubscription(gatt, service, WhoopProtocol.MemfaultUuid)
            startNextDescriptorWrite(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            OpenWhoopLog.d(Tag, "Descriptor write uuid=${descriptor.characteristic.uuid} status=$status")
            descriptorWriteInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit(WhoopBleEvent.Error("WHOOP notification subscription failed: $status"))
                return
            }
            startNextDescriptorWrite(gatt)
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
    private fun queueSubscription(
        gatt: BluetoothGatt,
        service: BluetoothGattService,
        uuid: UUID,
    ) {
        val characteristic = service.getCharacteristic(uuid) ?: run {
            OpenWhoopLog.w(Tag, "Subscription characteristic missing uuid=$uuid")
            return
        }
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(ClientCharacteristicConfigUuid) ?: run {
            OpenWhoopLog.w(Tag, "CCCD missing for uuid=$uuid")
            return
        }
        pendingDescriptors += descriptor
        OpenWhoopLog.d(Tag, "Queued subscription uuid=$uuid")
    }

    @SuppressLint("MissingPermission")
    private fun startNextDescriptorWrite(gatt: BluetoothGatt) {
        if (descriptorWriteInFlight) return
        val descriptor = if (pendingDescriptors.isEmpty()) null else pendingDescriptors.removeFirst()
        if (descriptor == null) {
            subscriptionsReady = true
            OpenWhoopLog.d(Tag, "WHOOP GATT subscriptions ready")
            emit(WhoopBleEvent.Ready)
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        OpenWhoopLog.d(Tag, "Writing CCCD for uuid=${descriptor.characteristic.uuid}")
        descriptorWriteInFlight = true
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(descriptor, value)
            OpenWhoopLog.d(Tag, "Descriptor write requested uuid=${descriptor.characteristic.uuid} status=$status")
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!accepted) {
            descriptorWriteInFlight = false
            emit(WhoopBleEvent.Error("WHOOP notification subscription write was rejected"))
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        OpenWhoopLog.d(Tag, "Notification uuid=$uuid bytes=${value.size} data=${value.toHexString()}")
        if (uuid == WhoopProtocol.StandardHeartRateMeasurementUuid) {
            StandardHeartRateParser.parse(value)?.let {
                OpenWhoopLog.d(Tag, "Standard HR sample bpm=${it.bpm} time=${it.time}")
                emit(WhoopBleEvent.HeartRate(it))
            }
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
                    write("historyEnd", WhoopCodecNative.historyEnd(nextSeq(), decoded.endData))
                    emit(WhoopBleEvent.HistorySyncFinished)
                }
            }
            is DecodedWhoopData.CommandResponse -> OpenWhoopLog.d(
                Tag,
                "Command response event cmd=${decoded.command} seq=${decoded.sequence} result=${decoded.result}",
            )
            null -> Unit
        }
    }

    private fun nextSeq(): Int {
        val sequence = nextSequence
        nextSequence = (nextSequence + 1) and 0xFF
        return sequence
    }

    private fun emit(event: WhoopBleEvent) {
        OpenWhoopLog.d(Tag, "Event $event")
        scope.launch { _events.emit(event) }
    }

    companion object {
        private const val Tag = "WhoopBleClient"
        private const val CommandSpacingMillis = 150L
        private const val OpticalEnableAttempts = 3
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
