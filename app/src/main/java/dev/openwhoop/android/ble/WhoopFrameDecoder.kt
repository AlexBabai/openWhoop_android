package dev.openwhoop.android.ble

import dev.openwhoop.android.OpenWhoopLog

class WhoopFrameDecoder {
    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray): DecodedWhoopData? {
        val candidate = if (pending.isEmpty()) bytes else pending + bytes
        val length = WhoopCodecNative.frameLength(candidate)
        if (length <= 0) {
            OpenWhoopLog.w(Tag, "Dropping undecodable frame fragment bytes=${candidate.size} data=${candidate.toHexString()}")
            pending = ByteArray(0)
            return null
        }
        if (candidate.size < length) {
            OpenWhoopLog.d(Tag, "Buffering partial frame bytes=${candidate.size} expected=$length")
            pending = candidate
            return null
        }
        pending = ByteArray(0)
        if (candidate.size > length) {
            OpenWhoopLog.w(Tag, "Frame contained trailing bytes actual=${candidate.size} expected=$length")
        }
        return WhoopCodecNative.decode(candidate.copyOfRange(0, length))
    }

    fun reset() {
        pending = ByteArray(0)
    }

    companion object {
        private const val Tag = "WhoopFrameDecoder"
    }
}
