package dev.openwhoop.android.health

data class EnabledHealthMetrics(
    val heartRate: Boolean = true,
    val hrv: Boolean = true,
    val spo2: Boolean = true,
    val respiratoryRate: Boolean = true,
    val skinTemperature: Boolean = true,
) {
    fun withMetric(metric: HealthMetricType, enabled: Boolean): EnabledHealthMetrics =
        when (metric) {
            HealthMetricType.HeartRate -> copy(heartRate = enabled)
            HealthMetricType.Hrv -> copy(hrv = enabled)
            HealthMetricType.Spo2 -> copy(spo2 = enabled)
            HealthMetricType.RespiratoryRate -> copy(respiratoryRate = enabled)
            HealthMetricType.SkinTemperature -> copy(skinTemperature = enabled)
        }
}

enum class HealthMetricType(
    val label: String,
    val permissionPurpose: String,
) {
    HeartRate("HR", "android.permission.health.WRITE_HEART_RATE"),
    Hrv("HRV", "android.permission.health.WRITE_HEART_RATE_VARIABILITY"),
    Spo2("SpO2", "android.permission.health.WRITE_OXYGEN_SATURATION"),
    RespiratoryRate("Respiratory", "android.permission.health.WRITE_RESPIRATORY_RATE"),
    SkinTemperature("Skin temp", "android.permission.health.WRITE_SKIN_TEMPERATURE"),
}
