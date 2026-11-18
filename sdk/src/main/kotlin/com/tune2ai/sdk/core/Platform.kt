package com.tune2ai.sdk.core

import com.tune2ai.sdk.api.ResourceSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/** Live device resource readings. The Model Manager (T2AI-020) consumes [snapshot]. */
interface ResourceMonitor {
    val snapshot: StateFlow<ResourceSnapshot>

    /** Takes a fresh reading now (readings also update on system callbacks). */
    fun refresh()
}

/**
 * Everything the shared runtime needs from the operating system. Android implements it in
 * `platform.android.AndroidPlatform`; tests use a fake. Shared code depends only on this interface.
 */
interface Platform : AutoCloseable {
    val storage: Storage
    val resources: ResourceMonitor

    /** App-private directory for model files and other SDK data. */
    val filesDir: String

    /** Dispatcher for blocking I/O and native calls. */
    val ioDispatcher: CoroutineDispatcher
}
