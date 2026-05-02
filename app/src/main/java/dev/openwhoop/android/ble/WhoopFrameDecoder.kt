package dev.openwhoop.android.ble

class WhoopFrameDecoder {
    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray): WhoopProtocol.Packet? {
        val candidate = if (pending.isEmpty()) bytes else pending + bytes
        val length = WhoopProtocol.frameLength(candidate)
        if (length == null) {
            pending = ByteArray(0)
            return null
        }
        if (candidate.size < length) {
            pending = candidate
            return null
        }
        pending = ByteArray(0)
        return runCatching {
            WhoopProtocol.parseFrame(candidate.copyOfRange(0, length))
        }.getOrNull()
    }

    fun reset() {
        pending = ByteArray(0)
    }
}
