package com.tune2ai.sdk.platform.android

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.tune2ai.sdk.api.ResourceSnapshot
import com.tune2ai.sdk.api.ThermalState
import com.tune2ai.sdk.core.ResourceMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads memory, battery, and thermal state, and refreshes on system callbacks:
 * `onTrimMemory`, battery broadcasts, and thermal status changes (API 29+).
 */
internal class AndroidResourceMonitor(context: Context) : ResourceMonitor, AutoCloseable {
    private val app = context.applicationContext
    private val activityManager = app.getSystemService(ActivityManager::class.java)
    private val powerManager = app.getSystemService(PowerManager::class.java)
    private var battery: Intent? = null
    private val state = MutableStateFlow(read())
    override val snapshot: StateFlow<ResourceSnapshot> = state.asStateFlow()

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = refresh()
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Android") override fun onLowMemory() = refresh()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            battery = intent
            refresh()
        }
    }

    private val thermalListener: Any? =
        if (Build.VERSION.SDK_INT >= 29) PowerManager.OnThermalStatusChangedListener { refresh() } else null

    init {
        app.registerComponentCallbacks(memoryCallbacks)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        battery = if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(batteryReceiver, filter)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            powerManager.addThermalStatusListener(thermalListener as PowerManager.OnThermalStatusChangedListener)
        }
        refresh()
    }

    override fun refresh() {
        state.value = read()
    }

    private fun read(): ResourceSnapshot {
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return ResourceSnapshot(
            availableMemoryBytes = memory.availMem,
            totalMemoryBytes = memory.totalMem,
            lowMemory = memory.lowMemory,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = when (status) {
                -1 -> null
                else -> status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            },
            thermal = thermalState(),
        )
    }

    private fun thermalState(): ThermalState {
        if (Build.VERSION.SDK_INT < 29) return ThermalState.UNKNOWN
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalState.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.SHUTDOWN
            else -> ThermalState.UNKNOWN
        }
    }

    override fun close() {
        app.unregisterComponentCallbacks(memoryCallbacks)
        app.unregisterReceiver(batteryReceiver)
        if (Build.VERSION.SDK_INT >= 29) {
            powerManager.removeThermalStatusListener(thermalListener as PowerManager.OnThermalStatusChangedListener)
        }
    }
}
