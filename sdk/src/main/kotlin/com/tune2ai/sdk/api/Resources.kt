package com.tune2ai.sdk.api

/** Device thermal status, mirroring Android's PowerManager levels. [UNKNOWN] below API 29. */
enum class ThermalState { UNKNOWN, NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

/** One reading of device resources. Null battery fields mean the device did not report them. */
data class ResourceSnapshot(
    val availableMemoryBytes: Long,
    val totalMemoryBytes: Long,
    val lowMemory: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val thermal: ThermalState,
)

/**
 * When the SDK refuses or stops work to protect the device (NFR-8). Configured by the app.
 *
 * @param minFreeMemoryBytes memory that must stay free after a model is loaded.
 * @param stopAtThermal refuse new work at or above this thermal state.
 * @param minBatteryPercent refuse new work below this level when not charging; null disables the check.
 */
data class ResourceLimits(
    val minFreeMemoryBytes: Long = 256L * MB,
    val stopAtThermal: ThermalState = ThermalState.SEVERE,
    val minBatteryPercent: Int? = null,
) {
    /** Why [snapshot] does not allow work needing [requiredBytes] more memory, or null if it does. */
    fun violation(snapshot: ResourceSnapshot, requiredBytes: Long = 0): String? {
        val thermal = snapshot.thermal
        val battery = snapshot.batteryPercent
        val freeAfter = snapshot.availableMemoryBytes - requiredBytes
        return when {
            thermal != ThermalState.UNKNOWN && thermal >= stopAtThermal ->
                "Device is too hot (thermal state $thermal)"
            snapshot.lowMemory -> "The system reports low memory"
            freeAfter < minFreeMemoryBytes ->
                "Needs ${requiredBytes / MB} MB but only ${snapshot.availableMemoryBytes / MB} MB is free " +
                    "(keeping ${minFreeMemoryBytes / MB} MB in reserve)"
            minBatteryPercent != null && battery != null && snapshot.charging != true && battery < minBatteryPercent ->
                "Battery is at $battery% (minimum $minBatteryPercent% when not charging)"
            else -> null
        }
    }

    private companion object {
        const val MB = 1024L * 1024
    }
}
