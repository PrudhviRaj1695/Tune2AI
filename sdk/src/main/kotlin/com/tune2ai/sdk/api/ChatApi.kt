package com.tune2ai.sdk.api

import com.tune2ai.sdk.core.RequestController
import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.core.withTerminalGuarantee
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/** Receives events from the callback API. Called on a background thread. */
fun interface EventListener {
    fun onEvent(event: Tune2AIEvent)
}

/** Handle for a request started through the callback API. */
fun interface Cancellable {
    fun cancel()
}

/** One-shot and session chat (FR-SDK-4). Get it from `Tune2AI.chat`. */
class ChatApi internal constructor(
    private val controller: RequestController,
    private val storage: Storage,
    private val scope: CoroutineScope,
    private val ready: Deferred<Unit>,
) {
    /** One message, no history. */
    @JvmOverloads
    fun send(message: String, options: RequestOptions = RequestOptions()): Flow<Tune2AIEvent> =
        send(listOf(ChatMessage.user(message)), options)

    /** A full message list, no stored history. */
    @JvmOverloads
    fun send(messages: List<ChatMessage>, options: RequestOptions = RequestOptions(), requestId: RequestId = RequestId.random()): Flow<Tune2AIEvent> =
        flow {
            ready.await()
            emitAll(controller.chat(messages, options, requestId))
        }.withTerminalGuarantee(requestId)

    /** A conversation whose history is persisted in local storage under [id]. */
    fun session(id: String): ChatSession = ChatSession(id, this, storage, scope)

    /**
     * Callback form of [send] for Java and other non-coroutine callers. Exactly one terminal event is
     * delivered, including [Tune2AIEvent.Cancelled] after [Cancellable.cancel].
     */
    @JvmOverloads
    fun stream(message: String, options: RequestOptions = RequestOptions(), listener: EventListener): Cancellable {
        val requestId = RequestId.random()
        return stream(send(listOf(ChatMessage.user(message)), options, requestId), requestId, listener)
    }

    internal fun stream(events: Flow<Tune2AIEvent>, requestId: RequestId, listener: EventListener): Cancellable =
        streamTo(scope, events, requestId, listener)
}

/** Collects [events] into [listener]; delivers exactly one terminal event, including Cancelled after cancel. */
internal fun streamTo(scope: CoroutineScope, events: Flow<Tune2AIEvent>, requestId: RequestId, listener: EventListener): Cancellable {
    val terminal = AtomicBoolean(false)
    val job = scope.launch {
        events.collect {
            if (it is Tune2AIEvent.Terminal) terminal.set(true)
            listener.onEvent(it)
        }
    }
    job.invokeOnCompletion { if (!terminal.get()) listener.onEvent(Tune2AIEvent.Cancelled(requestId)) }
    return Cancellable { job.cancel() }
}

/**
 * A persisted conversation. Each completed exchange (user message + reply) is appended to the
 * history; cancelled or failed requests are not. The Context Manager trims old turns to fit.
 */
class ChatSession internal constructor(
    val id: String,
    private val chat: ChatApi,
    private val storage: Storage,
    private val scope: CoroutineScope,
) {
    private val namespace = "chat/$id"

    suspend fun history(): List<ChatMessage> =
        storage.get(namespace, KEY)?.let { json.decodeFromString(serializer, it) } ?: emptyList()

    @JvmOverloads
    fun send(message: String, options: RequestOptions = RequestOptions(), requestId: RequestId = RequestId.random()): Flow<Tune2AIEvent> = flow {
        val past = history()
        val user = ChatMessage.user(message)
        val reply = StringBuilder()
        chat.send(past + user, options, requestId).collect { event ->
            if (event is Tune2AIEvent.TextDelta) reply.append(event.text)
            if (event is Tune2AIEvent.Completed) {
                storage.put(namespace, KEY, json.encodeToString(serializer, past + user + ChatMessage.assistant(reply.toString().trim())))
            }
            emit(event)
        }
    }

    /** Callback form of [send] for Java callers; see [ChatApi.stream]. */
    @JvmOverloads
    fun stream(message: String, options: RequestOptions = RequestOptions(), listener: EventListener): Cancellable {
        val requestId = RequestId.random()
        return chat.stream(send(message, options, requestId), requestId, listener)
    }

    suspend fun clear() = storage.clear(namespace)

    /** Non-suspending [clear] for Java callers. */
    fun clearAsync() {
        scope.launch { clear() }
    }

    private companion object {
        const val KEY = "history"
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(ChatMessage.serializer())
    }
}
