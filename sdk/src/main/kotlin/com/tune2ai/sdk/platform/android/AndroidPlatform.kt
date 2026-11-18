package com.tune2ai.sdk.platform.android

import android.content.Context
import com.tune2ai.sdk.core.Platform
import com.tune2ai.sdk.core.ResourceMonitor
import com.tune2ai.sdk.core.Storage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The Android adapter: the only place outside backend modules that touches `android.*` (FR-PLT-1).
 * Holds the application context only, never an Activity. Call [close] when the SDK shuts down.
 */
class AndroidPlatform(context: Context) : Platform {
    private val app = context.applicationContext
    private val sqlite = SqliteStorage(app)
    private val monitor = AndroidResourceMonitor(app)

    override val storage: Storage get() = sqlite
    override val resources: ResourceMonitor get() = monitor
    override val filesDir: String = app.filesDir.path
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    override fun close() {
        monitor.close()
        sqlite.close()
    }
}
