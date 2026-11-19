package com.tune2ai.sdk.core

import com.tune2ai.sdk.api.RequestId
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile

/**
 * Enforces the [Tune2AIEvent] contract on any event stream: exactly one terminal event, last.
 *
 * - Events after the first terminal event are dropped and the upstream is stopped.
 * - An upstream exception becomes [Tune2AIEvent.Failed]; a timeout becomes a [Tune2AIException.Timeout] failure.
 * - An upstream cancellation (not caused by the collector) becomes [Tune2AIEvent.Cancelled].
 * - An upstream that completes without a terminal event gets a [Tune2AIEvent.Failed].
 * - When the collector itself is cancelled nothing more is emitted; there is no one to receive it.
 *
 * Apply it before `flowOn` or `buffer`: an exception that crosses those operators cancels their
 * buffer, and events emitted just before the failure would be lost.
 */
internal fun Flow<Tune2AIEvent>.withTerminalGuarantee(requestId: RequestId): Flow<Tune2AIEvent> = flow {
    var finished = false
    emitAll(
        this@withTerminalGuarantee
            .transformWhile { event ->
                emit(event)
                event !is Tune2AIEvent.Terminal
            }
            .onEach { if (it is Tune2AIEvent.Terminal) finished = true }
            // catch only sees upstream failures; the collector's own cancellation is rethrown by the operator.
            .catch { error ->
                if (finished) throw error
                finished = true
                emit(
                    if (error is CancellationException && error !is TimeoutCancellationException) {
                        Tune2AIEvent.Cancelled(requestId)
                    } else {
                        Tune2AIEvent.Failed(requestId, error.toTune2AIException())
                    },
                )
            },
    )
    if (!finished) {
        emit(Tune2AIEvent.Failed(requestId, Tune2AIException.BackendFailure("Stream ended without a terminal event")))
    }
}

/** Maps any throwable to the SDK error type, keeping SDK errors as they are. */
internal fun Throwable.toTune2AIException(): Tune2AIException = when (this) {
    is Tune2AIException -> this
    is TimeoutCancellationException -> Tune2AIException.Timeout(message ?: "Deadline exceeded")
    else -> Tune2AIException.BackendFailure(message ?: this::class.simpleName ?: "Unknown failure", this)
}
