package com.tune2ai.sdk.core

import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.RequestId
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Role
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIEvent.Progress
import com.tune2ai.sdk.api.Tune2AIEvent.Stage
import com.tune2ai.sdk.api.Tune2AIException.InvalidRequest
import com.tune2ai.sdk.api.Usage
import com.tune2ai.sdk.inference.GenerateRequest
import com.tune2ai.sdk.services.model.ModelManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout

/**
 * The messages sent to the model, a one-line summary for the trace, and the sources that were
 * [included] or [omitted] to fit the context.
 */
internal data class AssembledContext(
    val messages: List<ChatMessage>,
    val summary: String,
    val included: List<String> = emptyList(),
    val omitted: List<String> = emptyList(),
)

/** Builds the model input. Implemented by the Context Manager (T2AI-022). */
internal fun interface ContextAssembler {
    suspend fun assemble(messages: List<ChatMessage>, model: ModelDescriptor, options: RequestOptions): AssembledContext

    companion object {
        val PassThrough = ContextAssembler { messages, _, _ -> AssembledContext(messages, "Message only") }
    }
}

/**
 * The single entry point for every request (FR-ORC-1): validate → route → assemble context →
 * generate. Emits [Progress] for each stage, streams text, and always ends with exactly one
 * terminal event. Runs off the main thread (NFR-6) and enforces the request deadline.
 */
internal class RequestController(
    private val models: ModelManager,
    private val router: Router,
    private val context: ContextAssembler = ContextAssembler.PassThrough,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    fun chat(
        messages: List<ChatMessage>,
        options: RequestOptions = RequestOptions(),
        requestId: RequestId = RequestId.random(),
    ): Flow<Tune2AIEvent> = flow {
        val start = clock()
        emit(Progress(requestId, Stage.RECEIVED))
        validate(messages, options)
        emit(Progress(requestId, Stage.VALIDATED))

        withTimeout(options.deadlineMillis) {
            val route = router.route(Task.CHAT, options)
            if (options.maxTokens >= route.model.contextLength) {
                throw InvalidRequest("maxTokens (${options.maxTokens}) must be below the model's context length (${route.model.contextLength})")
            }
            emit(Progress(requestId, Stage.ROUTED, "${route.backend.id} · ${route.model.name}"))

            val assembled = context.assemble(messages, route.model, options)
            emit(Progress(requestId, Stage.CONTEXT_ASSEMBLED, assembled.summary, assembled.included + assembled.omitted.map { "omitted $it" }))

            models.acquire(route.model, route.backend).use { lease ->
                emit(Progress(requestId, Stage.GENERATING, route.backend.id))
                var firstToken: Long? = null
                var tokens = 0
                route.backend
                    .generate(lease.model, GenerateRequest(assembled.messages, options.maxTokens, options.temperature))
                    .collect { piece ->
                        if (firstToken == null) firstToken = clock() - start
                        tokens++
                        emit(Tune2AIEvent.TextDelta(requestId, piece))
                    }
                emit(Tune2AIEvent.Completed(requestId, Usage(tokens, firstToken, clock() - start)))
            }
        }
    }
        // Guard before flowOn: an exception crossing flowOn cancels its buffer and drops events already emitted.
        .withTerminalGuarantee(requestId)
        .flowOn(dispatcher)

    private fun validate(messages: List<ChatMessage>, options: RequestOptions) {
        if (messages.isEmpty()) throw InvalidRequest("A chat request needs at least one message")
        if (messages.last().role != Role.USER) throw InvalidRequest("The last message must come from the user")
        if (messages.any { it.content.isBlank() }) throw InvalidRequest("Messages must not be blank")
        if (options.maxTokens < 1) throw InvalidRequest("maxTokens must be at least 1")
        if (options.temperature !in 0f..2f) throw InvalidRequest("temperature must be between 0 and 2")
        if (options.deadlineMillis <= 0) throw InvalidRequest("deadlineMillis must be positive")
    }
}
