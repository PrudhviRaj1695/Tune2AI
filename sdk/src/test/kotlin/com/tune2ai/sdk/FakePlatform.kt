package com.tune2ai.sdk

import com.tune2ai.sdk.api.ResourceSnapshot
import com.tune2ai.sdk.api.ThermalState
import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.core.Platform
import com.tune2ai.sdk.core.ResourceMonitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

const val GB = 1024L * 1024 * 1024

fun snapshot(
    availableGb: Double = 4.0,
    lowMemory: Boolean = false,
    batteryPercent: Int? = 80,
    charging: Boolean? = false,
    thermal: ThermalState = ThermalState.NONE,
) = ResourceSnapshot((availableGb * GB).toLong(), 8 * GB, lowMemory, batteryPercent, charging, thermal)

/** Resource monitor whose readings a test sets directly. */
class FakeResourceMonitor(initial: ResourceSnapshot = snapshot()) : ResourceMonitor {
    override val snapshot = MutableStateFlow(initial)
    override fun refresh() = Unit
}

/** JVM stand-in for AndroidPlatform. */
class FakePlatform(
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) : Platform {
    override val storage = InMemoryStorage()
    override val resources = FakeResourceMonitor()
    override val filesDir: String = System.getProperty("java.io.tmpdir")!!
    var closed = false
        private set

    override fun close() {
        closed = true
    }
}
