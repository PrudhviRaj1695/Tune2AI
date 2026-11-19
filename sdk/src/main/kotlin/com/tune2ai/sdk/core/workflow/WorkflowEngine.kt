package com.tune2ai.sdk.core.workflow

import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.RequestId
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIEvent.Progress
import com.tune2ai.sdk.api.Tune2AIEvent.Stage
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.api.Usage
import com.tune2ai.sdk.core.RequestController
import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.core.tools.ToolCallingEngine
import com.tune2ai.sdk.core.withTerminalGuarantee
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Runs a [WorkflowDefinition] (FR-ORC-5). The definition is validated before any step runs; an invalid
 * one ends [InvalidRequest][Tune2AIException.InvalidRequest] with no side effects. Model steps go
 * through the [RequestController] (routing, context, limits apply per step); tool steps go through the
 * [ToolCallingEngine]. Variables flow between steps through a string map seeded with the run's inputs.
 * The terminal step's output is emitted as a [TextDelta][Tune2AIEvent.TextDelta] before [Completed];
 * the stream always ends in exactly one terminal event (NFR-7).
 *
 * When a [storage] is supplied the run is checkpointed to the `workflows` namespace after each step
 * (FR-ORC-6), keyed by the run's [RequestId]; [resume] continues from the last checkpoint without
 * re-running completed steps. Without a [storage], runs are not persisted and [resume] is unavailable.
 */
class WorkflowEngine(
    private val controller: RequestController,
    private val tools: ToolCallingEngine,
    private val storage: Storage? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    fun run(
        definition: WorkflowDefinition,
        inputs: Map<String, String> = emptyMap(),
        options: RequestOptions = RequestOptions(),
        requestId: RequestId = RequestId.random(),
    ): Flow<Tune2AIEvent> = flow {
        emit(Progress(requestId, Stage.RECEIVED))
        val problems = WorkflowValidator.check(definition, tools.names)
        if (problems.isNotEmpty()) throw Tune2AIException.InvalidRequest("Invalid workflow: ${problems.joinToString("; ")}")
        val missing = definition.inputs - inputs.keys
        if (missing.isNotEmpty()) throw Tune2AIException.InvalidRequest("Missing workflow inputs: ${missing.sorted()}")
        emit(Progress(requestId, Stage.VALIDATED, "${definition.steps.size} steps"))
        drive(definition, definition.steps.first().id, inputs.toMutableMap(), 0, options, requestId)
    }.withTerminalGuarantee(requestId)

    /**
     * Continues the run [runId] from its last checkpoint. Re-supply the same [definition]; a changed
     * [WorkflowDefinition.version] is refused. A completed run replays its result. Fails
     * [InvalidRequest][Tune2AIException.InvalidRequest] if there is no such run or no storage.
     */
    fun resume(
        definition: WorkflowDefinition,
        runId: RequestId,
        options: RequestOptions = RequestOptions(),
    ): Flow<Tune2AIEvent> = flow {
        emit(Progress(runId, Stage.RECEIVED))
        val cp = checkpoint(runId.value) ?: throw Tune2AIException.InvalidRequest("No persisted workflow run '${runId.value}'")
        if (cp.definitionVersion != definition.version) {
            throw Tune2AIException.InvalidRequest("Workflow definition changed (checkpoint v${cp.definitionVersion}, now v${definition.version}); cannot resume")
        }
        emit(Progress(runId, Stage.VALIDATED, if (cp.done) "already complete" else "resume at ${cp.nextStepId}"))
        if (cp.done) {
            emit(Tune2AIEvent.Completed(runId, Usage(cp.outputTokens, null, 0)))
            return@flow
        }
        drive(definition, cp.nextStepId!!, cp.variables.toMutableMap(), cp.outputTokens, options, runId)
    }.withTerminalGuarantee(runId)

    /** Problems with [definition] against the registered tools; an empty list means it is safe to run. */
    fun validate(definition: WorkflowDefinition): List<String> = WorkflowValidator.check(definition, tools.names)

    /** Runs steps from [startStepId] until a terminal step, checkpointing after each. */
    private suspend fun FlowCollector<Tune2AIEvent>.drive(
        definition: WorkflowDefinition,
        startStepId: String,
        vars: MutableMap<String, String>,
        tokensSoFar: Int,
        options: RequestOptions,
        requestId: RequestId,
    ) {
        val byId = definition.steps.associateBy { it.id }
        var outputTokens = tokensSoFar
        var firstToken: Long? = null
        val start = clock()

        withTimeout(options.deadlineMillis) {
            var step: WorkflowStep? = byId.getValue(startStepId)
            while (step != null) {
                val current = step
                when (current.type) {
                    StepType.MODEL -> {
                        val text = StringBuilder()
                        controller.chat(listOf(ChatMessage.user(render(current.prompt!!, vars))), options, requestId).collect { event ->
                            when (event) {
                                is Progress -> if (event.stage !in setOf(Stage.RECEIVED, Stage.VALIDATED)) {
                                    emit(event.copy(detail = listOfNotNull("Step ${current.id}", event.detail).joinToString(" · ")))
                                }
                                is Tune2AIEvent.TextDelta -> {
                                    if (firstToken == null) firstToken = clock() - start
                                    text.append(event.text)
                                }
                                is Tune2AIEvent.Completed -> outputTokens += event.usage.outputTokens
                                is Tune2AIEvent.Failed -> throw event.error
                                is Tune2AIEvent.Cancelled -> throw CancellationException("Workflow step '${current.id}' cancelled")
                                else -> Unit
                            }
                        }
                        vars[current.outputVar] = text.toString().trim()
                    }
                    StepType.TOOL -> {
                        emit(Progress(requestId, Stage.GENERATING, "Step ${current.id} · ${current.tool}"))
                        val argsJson = render(current.arguments ?: "{}", vars)
                        val callId = "step_${current.id}"
                        emit(Tune2AIEvent.ToolCall(requestId, callId, current.tool!!, argsJson))
                        val outcome = tools.invoke(current.tool, argsJson, tools.names)
                        emit(Tune2AIEvent.ToolResult(requestId, callId, current.tool, outcome.resultJson, outcome.isError))
                        if (outcome.isError) throw Tune2AIException.ToolFailure("Step '${current.id}' tool '${current.tool}' failed: ${outcome.resultJson}")
                        vars[current.outputVar] = outcome.resultJson
                    }
                }

                val next = current.transitions.firstOrNull { it.whenVar == null || vars[it.whenVar] == it.equals }
                save(WorkflowCheckpoint(requestId.value, definition.id, definition.version, next?.to, vars, outputTokens))
                if (next == null) {
                    emit(Tune2AIEvent.TextDelta(requestId, vars[current.outputVar].orEmpty()))
                    emit(Tune2AIEvent.Completed(requestId, Usage(outputTokens, firstToken, clock() - start)))
                    return@withTimeout
                }
                step = byId[next.to]
            }
        }
    }

    private suspend fun save(cp: WorkflowCheckpoint) {
        storage?.put(NAMESPACE, cp.runId, JSON.encodeToString(WorkflowCheckpoint.serializer(), cp))
    }

    /** All persisted runs, for the host to list and inspect (FR-ORC-6). */
    suspend fun runs(): List<WorkflowCheckpoint> =
        storage?.list(NAMESPACE)?.values?.map { JSON.decodeFromString(WorkflowCheckpoint.serializer(), it) } ?: emptyList()

    suspend fun checkpoint(runId: String): WorkflowCheckpoint? =
        storage?.get(NAMESPACE, runId)?.let { JSON.decodeFromString(WorkflowCheckpoint.serializer(), it) }

    /** Removes a persisted run. Returns true if one was deleted. */
    suspend fun delete(runId: String): Boolean = storage?.delete(NAMESPACE, runId) ?: false

    private companion object {
        const val NAMESPACE = "workflows"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
