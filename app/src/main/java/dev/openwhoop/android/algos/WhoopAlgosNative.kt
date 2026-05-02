package dev.openwhoop.android.algos

import dev.openwhoop.android.ble.WhoopProtocol

object WhoopAlgosNative {
    private const val AverageHrIndex = 0
    private const val MinHrIndex = 1
    private const val MaxHrIndex = 2
    private const val StressIndex = 3
    private const val StrainIndex = 4

    init {
        System.loadLibrary("openwhoop_android_algos")
    }

    fun calculate(
        samples: List<WhoopProtocol.HeartRateSample>,
        maxHr: Int = 190,
        restingHr: Int = 60,
    ): AlgorithmStats {
        if (samples.isEmpty()) return AlgorithmStats()
        val sorted = samples.sortedBy { it.time }
        val values = calculateStats(
            timestampsMillis = sorted.map { it.time.toEpochMilli() }.toLongArray(),
            bpmValues = sorted.map { it.bpm }.toLongArray(),
            maxHr = maxHr,
            restingHr = restingHr,
        )
        return AlgorithmStats(
            averageHr = values.getOrNull(AverageHrIndex).validFinite(),
            minHr = values.getOrNull(MinHrIndex).validFinite(),
            maxHr = values.getOrNull(MaxHrIndex).validFinite(),
            stress = values.getOrNull(StressIndex).validFinite(),
            strain = values.getOrNull(StrainIndex).validFinite(),
        )
    }

    private external fun calculateStats(
        timestampsMillis: LongArray,
        bpmValues: LongArray,
        maxHr: Int,
        restingHr: Int,
    ): DoubleArray
}

data class AlgorithmStats(
    val averageHr: Double? = null,
    val minHr: Double? = null,
    val maxHr: Double? = null,
    val stress: Double? = null,
    val strain: Double? = null,
)

private fun Double?.validFinite(): Double? =
    this?.takeIf { it.isFinite() }
