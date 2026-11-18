package com.tune2ai.sdk

import com.tune2ai.sdk.api.ResourceLimits
import com.tune2ai.sdk.api.ThermalState
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceLimitsTest {
    private val limits = ResourceLimits(minFreeMemoryBytes = GB / 2, minBatteryPercent = 15)

    @Test
    fun healthyDeviceAllowsWork() {
        assertNull(limits.violation(snapshot(), requiredBytes = 2 * GB))
    }

    @Test
    fun refusesWhenTheModelWouldEatTheReserve() {
        val reason = limits.violation(snapshot(availableGb = 2.4), requiredBytes = 2 * GB)
        assertTrue(reason, reason!!.contains("only 2457 MB is free"))
    }

    @Test
    fun refusesAtOrAboveTheThermalLimit() {
        assertTrue(limits.violation(snapshot(thermal = ThermalState.SEVERE))!!.contains("too hot"))
        assertNull(limits.violation(snapshot(thermal = ThermalState.MODERATE)))
    }

    @Test
    fun unknownThermalStateNeverBlocks() {
        assertNull(ResourceLimits(stopAtThermal = ThermalState.NONE).violation(snapshot(thermal = ThermalState.UNKNOWN)))
    }

    @Test
    fun refusesWhenTheSystemReportsLowMemory() {
        assertTrue(limits.violation(snapshot(lowMemory = true))!!.contains("low memory"))
    }

    @Test
    fun batteryFloorAppliesOnlyWhenNotChargingAndReported() {
        assertTrue(limits.violation(snapshot(batteryPercent = 10))!!.contains("Battery is at 10%"))
        assertNull(limits.violation(snapshot(batteryPercent = 10, charging = true)))
        assertNull(limits.violation(snapshot(batteryPercent = null)))
        assertNull(ResourceLimits().violation(snapshot(batteryPercent = 1)))
    }

    @Test
    fun fakePlatformDrivesReadings() {
        val platform = FakePlatform()
        platform.resources.snapshot.value = snapshot(thermal = ThermalState.CRITICAL)
        assertTrue(limits.violation(platform.resources.snapshot.value) != null)
    }
}
