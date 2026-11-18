package com.tune2ai.sdk.api

import com.tune2ai.sdk.core.agent.AgentEngine
import com.tune2ai.sdk.core.withTerminalGuarantee
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Bounded, tool-using tasks (FR-SDK-5). Get it from `Tune2AI.agent`. Tools are registered on the
 * builder with `addTool`; a run may use all of them or the subset in `tools`.
 *
 * The stream reports progress for each model step, `ToolCall`/`ToolResult` for each tool, the final
 * answer as text, and one terminal event: Completed, Cancelled, or Failed (`LIMIT_REACHED` when the
 * step or tool-call budget runs out).
 */
class AgentApi internal constructor(
    private val engine: AgentEngine,
    private val scope: CoroutineScope,
    private val ready: Deferred<Unit>,
) {
    @JvmOverloads
    fun run(
        task: String,
        tools: Set<String>? = null,
        limits: AgentLimits = AgentLimits(),
        options: RequestOptions = RequestOptions(),
        requestId: RequestId = RequestId.random(),
    ): Flow<Tune2AIEvent> = flow {
        ready.await()
        emitAll(engine.run(task, tools, limits, options, requestId))
    }.withTerminalGuarantee(requestId)

    /** Callback form of [run] for Java callers; exactly one terminal event, including Cancelled after cancel. */
    @JvmOverloads
    fun stream(
        task: String,
        tools: Set<String>? = null,
        limits: AgentLimits = AgentLimits(),
        options: RequestOptions = RequestOptions(),
        listener: EventListener,
    ): Cancellable {
        val requestId = RequestId.random()
        return streamTo(scope, run(task, tools, limits, options, requestId), requestId, listener)
    }
}
