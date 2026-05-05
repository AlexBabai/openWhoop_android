package dev.openwhoop.android.ble

class WhoopFrameDecoder {
    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray): DecodedWhoopData? {
        val candidate = if (pending.isEmpty()) bytes else pending + bytes
        val length = WhoopCodecNative.frameLength(candidate)
        if (length <= 0) {
            pending = ByteArray(0)
            return null
        }
        if (candidate.size < length) {
            pending = candidate
            return null
        }
        pending = ByteArray(0)
        return WhoopCodecNative.decode(candidate.copyOfRange(0, length))
    }

    fun reset() {
        pending = ByteArray(0)
    }
}
