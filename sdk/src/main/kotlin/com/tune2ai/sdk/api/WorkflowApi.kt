package com.tune2ai.sdk.api

import com.tune2ai.sdk.core.withTerminalGuarantee
import com.tune2ai.sdk.core.workflow.WorkflowCheckpoint
import com.tune2ai.sdk.core.workflow.WorkflowDefinition
import com.tune2ai.sdk.core.workflow.WorkflowEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

/** A persisted run's state, as reported by [WorkflowApi.status] and [WorkflowApi.list]. */
data class WorkflowRunStatus(
    val runId: String,
    val definitionId: String,
    val definitionVersion: Int,
    val completed: Boolean,
    val nextStepId: String?,
    val variables: Map<String, String>,
) {
    /** True when the run stopped partway and can be continued with [WorkflowApi.resume]. */
    val canResume: Boolean get() = !completed
}

/**
 * Define, validate, start, inspect, cancel, and resume workflows (FR-SDK-6). Get it from
 * `Tune2AI.workflow`. A run is identified by its [RequestId]; pass the same id to [resume] and
 * [status] after a restart. [start] and [resume] return a [Flow] — cancel a Flow run by cancelling
 * its collection. [stream] is the Java-friendly callback form, and the run it starts can be stopped
 * with [cancel].
 */
class WorkflowApi internal constructor(
    private val engine: WorkflowEngine,
    private val scope: CoroutineScope,
    private val ready: Deferred<Unit>,
) {
    private val running = ConcurrentHashMap<String, Cancellable>()

    /** Problems with [definition] against the registered tools; an empty list means it is valid. */
    fun validate(definition: WorkflowDefinition): List<String> = engine.validate(definition)

    /** Starts a run. Invalid definitions and missing inputs end the stream with a typed [Tune2AIEvent.Failed] event. */
    @JvmOverloads
    fun start(
        definition: WorkflowDefinition,
        inputs: Map<String, String> = emptyMap(),
        options: RequestOptions = RequestOptions(),
        requestId: RequestId = RequestId.random(),
    ): Flow<Tune2AIEvent> = flow {
        ready.await()
        emitAll(engine.run(definition, inputs, options, requestId))
    }.withTerminalGuarantee(requestId)

    /** Continues run [runId] from its last checkpoint; a changed definition version is refused. */
    @JvmOverloads
    fun resume(
        definition: WorkflowDefinition,
        runId: RequestId,
        options: RequestOptions = RequestOptions(),
    ): Flow<Tune2AIEvent> = flow {
        ready.await()
        emitAll(engine.resume(definition, runId, options))
    }.withTerminalGuarantee(runId)

    /** Callback form of [start] for Java callers; the run is tracked under [requestId] so [cancel] can stop it. */
    @JvmOverloads
    fun stream(
        definition: WorkflowDefinition,
        inputs: Map<String, String> = emptyMap(),
        options: RequestOptions = RequestOptions(),
        requestId: RequestId = RequestId.random(),
        listener: EventListener,
    ): Cancellable {
        val handle = streamTo(scope, start(definition, inputs, options, requestId), requestId) { event ->
            if (event is Tune2AIEvent.Terminal) running.remove(requestId.value)
            listener.onEvent(event)
        }
        running[requestId.value] = handle
        return Cancellable { handle.cancel() }
    }

    /** Cancels a run started through [stream]. Returns false if no such run is active (Flow runs cancel with their collection). */
    fun cancel(runId: String): Boolean = running.remove(runId)?.let { it.cancel(); true } ?: false

    /** The persisted state of [runId], or null if nothing is stored for it. */
    suspend fun status(runId: String): WorkflowRunStatus? = engine.checkpoint(runId)?.toStatus()

    /** Every persisted run, for a console or recovery UI. */
    suspend fun list(): List<WorkflowRunStatus> = engine.runs().map { it.toStatus() }

    /** Removes a persisted run. Returns true if one was deleted. */
    suspend fun delete(runId: String): Boolean = engine.delete(runId)

    private fun WorkflowCheckpoint.toStatus() =
        WorkflowRunStatus(runId, definitionId, definitionVersion, done, nextStepId, variables)
}
