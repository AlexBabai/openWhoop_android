package dev.openwhoop.android.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    private const val Sof = 0xAA.toByte()
    private const val PacketTypeCommand = 35
    private const val PacketTypeCommandResponse = 36
    private const val PacketTypeRealtimeData = 40
    private const val PacketTypeHistoricalData = 47
    private const val PacketTypeMetadata = 49
    private const val MetadataHistoryEnd = 2
    private const val MetadataHistoryComplete = 3
    private const val CommandToggleRealtimeHr = 3
    private const val CommandSetClock = 10
    private const val CommandSendHistoricalData = 22
    private const val CommandHistoricalDataResult = 23
    private const val CommandGetHelloHarvard = 35
    private const val CommandGetAdvertisingNameHarvard = 76
    private const val CommandEnterHighFreqSync = 96

    data class Packet(
        val type: Int,
        val sequence: Int,
        val command: Int,
        val data: ByteArray,
    )

    data class HeartRateSample(
        val time: Instant,
        val bpm: Long,
        val source: SampleSource,
    )

    enum class SampleSource {
        Realtime,
        History,
    }

    data class HistoryMetadata(
        val metadataType: Int,
        val timestampSeconds: Long,
        val endData: ByteArray,
    )

    fun toggleRealtimeHr(sequence: Int, enabled: Boolean): ByteArray =
        command(sequence, CommandToggleRealtimeHr, byteArrayOf(if (enabled) 1 else 0))

    fun helloHarvard(sequence: Int): ByteArray =
        command(sequence, CommandGetHelloHarvard, byteArrayOf(0))

    fun setTime(sequence: Int, now: Instant = Instant.now()): ByteArray {
        val unix = now.epochSecond.toInt()
        val payload = ByteBuffer.allocate(9)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(unix)
            .put(byteArrayOf(0, 0, 0, 0, 0))
            .array()
        return command(sequence, CommandSetClock, payload)
    }

    fun getName(sequence: Int): ByteArray =
        command(sequence, CommandGetAdvertisingNameHarvard, byteArrayOf(0))

    fun enterHighFreqSync(sequence: Int): ByteArray =
        command(sequence, CommandEnterHighFreqSync, byteArrayOf())

    fun historyStart(sequence: Int): ByteArray =
        command(sequence, CommandSendHistoricalData, byteArrayOf(0))

    fun historyEnd(sequence: Int, endData: ByteArray): ByteArray =
        command(sequence, CommandHistoricalDataResult, byteArrayOf(1) + endData.copyOf(8))

    fun historyEndFailure(sequence: Int): ByteArray =
        command(sequence, CommandHistoricalDataResult, byteArrayOf(0))

    fun frameLength(frameStart: ByteArray): Int? {
        if (frameStart.size < 4 || frameStart[0] != Sof) return null
        val payloadLength = ((frameStart[2].toInt() and 0xFF) shl 8) or (frameStart[1].toInt() and 0xFF)
        return 4 + payloadLength
    }

    fun parseFrame(bytes: ByteArray): Packet {
        require(bytes.size >= 8) { "WHOOP frame is too short" }
        require(bytes[0] == Sof) { "Invalid WHOOP frame start" }
        val lengthBytes = byteArrayOf(bytes[1], bytes[2])
        val expectedHeaderCrc = bytes[3].toInt() and 0xFF
        require(crc8(lengthBytes) == expectedHeaderCrc) { "Invalid WHOOP header CRC" }
        val payloadLength = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        require(payloadLength >= 8 && bytes.size >= 4 + payloadLength) { "Incomplete WHOOP frame" }
        val payloadEnd = 4 + payloadLength
        val payload = bytes.copyOfRange(4, payloadEnd - 4)
        val expectedCrc = bytes.copyOfRange(payloadEnd - 4, payloadEnd).toUIntLe()
        require(crc32(payload) == expectedCrc) { "Invalid WHOOP payload CRC" }
        require(payload.size >= 3) { "WHOOP payload is too short" }
        return Packet(
            type = payload[0].toInt() and 0xFF,
            sequence = payload[1].toInt() and 0xFF,
            command = payload[2].toInt() and 0xFF,
            data = payload.copyOfRange(3, payload.size),
        )
    }

    fun parseHeartRate(packet: Packet): HeartRateSample? =
        when (packet.type) {
            PacketTypeRealtimeData -> parseRealtimeHeartRate(packet)
            PacketTypeHistoricalData -> parseHistoricalHeartRate(packet)
            else -> null
        }

    fun parseHistoryMetadata(packet: Packet): HistoryMetadata? {
        if (packet.type != PacketTypeMetadata || packet.data.size < 18) return null
        val unix = packet.data.copyOfRange(0, 4).toUIntLe().toLong()
        val endData = packet.data.copyOfRange(10, 18)
        return HistoryMetadata(packet.command, unix, endData)
    }

    fun isHistoryFinished(metadata: HistoryMetadata): Boolean =
        metadata.metadataType == MetadataHistoryEnd || metadata.metadataType == MetadataHistoryComplete

    fun isCommandResponse(packet: Packet): Boolean = packet.type == PacketTypeCommandResponse

    private fun command(sequence: Int, command: Int, payload: ByteArray): ByteArray {
        val packet = byteArrayOf(
            PacketTypeCommand.toByte(),
            (sequence and 0xFF).toByte(),
            (command and 0xFF).toByte(),
        ) + payload
        val length = packet.size + 4
        val lengthBytes = byteArrayOf((length and 0xFF).toByte(), ((length ushr 8) and 0xFF).toByte())
        return byteArrayOf(Sof) +
            lengthBytes +
            byteArrayOf(crc8(lengthBytes).toByte()) +
            packet +
            crc32(packet).toIntLeBytes()
    }

    private fun parseRealtimeHeartRate(packet: Packet): HeartRateSample? {
        if (packet.data.size < 6) return null
        val unixBytes = byteArrayOf(
            packet.command.toByte(),
            packet.data[0],
            packet.data[1],
            packet.data[2],
        )
        val unix = unixBytes.toUIntLe().toLong()
        val bpm = packet.data[5].toLong() and 0xFF
        if (bpm <= 0) return null
        return HeartRateSample(Instant.ofEpochSecond(unix), bpm, SampleSource.Realtime)
    }

    private fun parseHistoricalHeartRate(packet: Packet): HeartRateSample? {
        if (packet.data.size < 15) return null
        val unix = packet.data.copyOfRange(4, 8).toUIntLe().toLong()
        val bpm = packet.data[14].toLong() and 0xFF
        if (unix <= 0 || bpm <= 0) return null
        return HeartRateSample(Instant.ofEpochSecond(unix), bpm, SampleSource.History)
    }

    private fun crc8(bytes: ByteArray): Int {
        var crc = 0x00
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) {
                    ((crc shl 1) xor 0x07) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return crc and 0xFF
    }

    private fun crc32(bytes: ByteArray): UInt {
        var crc = 0x00000000u
        for (byte in bytes) {
            crc = crc xor (byte.toUInt() and 0xFFu)
            repeat(8) {
                crc = if ((crc and 1u) != 0u) {
                    (crc shr 1) xor 0xEDB88320u
                } else {
                    crc shr 1
                }
            }
        }
        return crc.inv()
    }

    private fun ByteArray.toUIntLe(): UInt {
        require(size >= 4)
        return (this[0].toUInt() and 0xFFu) or
            ((this[1].toUInt() and 0xFFu) shl 8) or
            ((this[2].toUInt() and 0xFFu) shl 16) or
            ((this[3].toUInt() and 0xFFu) shl 24)
    }

    private fun UInt.toIntLeBytes(): ByteArray =
        byteArrayOf(
            (this and 0xFFu).toByte(),
            ((this shr 8) and 0xFFu).toByte(),
            ((this shr 16) and 0xFFu).toByte(),
            ((this shr 24) and 0xFFu).toByte(),
        )
}
