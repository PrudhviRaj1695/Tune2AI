package com.tune2ai.sdk.platform.android

import android.database.sqlite.SQLiteFullException
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.toTune2AIException
import java.io.IOException

/** Maps platform and native failures to SDK error categories (FR-PLT-1). */
internal fun Throwable.toPlatformException(): Tune2AIException = when (this) {
    is Tune2AIException -> this
    is SQLiteFullException -> Tune2AIException.StorageFailure("Device storage is full", this)
    is android.database.SQLException -> Tune2AIException.StorageFailure("Local storage failed: $message", this)
    is IOException -> Tune2AIException.StorageFailure("File access failed: $message", this)
    is UnsatisfiedLinkError -> Tune2AIException.BackendFailure("Native library is missing or incompatible: $message", this)
    is OutOfMemoryError -> Tune2AIException.ResourceLimit("Out of memory: $message")
    else -> toTune2AIException()
}

/** Runs a platform call, rethrowing failures as [Tune2AIException]. Cancellation passes through. */
internal inline fun <T> platformCall(block: () -> T): T = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    throw e.toPlatformException()
}
