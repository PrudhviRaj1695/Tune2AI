package com.tune2ai.sdk.docs

import com.tune2ai.sdk.Tune2AI
import com.tune2ai.sdk.api.Cancellable
import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.EventListener
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Tune2AIEvent
import kotlinx.coroutines.flow.takeWhile

suspend fun askOnce(ai: Tune2AI): String {
    val answer = StringBuilder()
    ai.chat.send("What is the capital of France?").collect { event ->
        when (event) {
            is Tune2AIEvent.TextDelta -> answer.append(event.text)
            is Tune2AIEvent.Failed -> throw event.error
            else -> Unit
        }
    }
    return answer.toString()
}

suspend fun converse(ai: Tune2AI): List<ChatMessage> {
    val session = ai.chat.session("support-thread")
    session.send("My name is Ada.").collect { }
    session.send("What is my name?").collect { }
    return session.history()
}

fun pirateOptions() = RequestOptions(
    model = "qwen2.5-0.5b",
    temperature = 1.0f,
    maxTokens = 256,
    deadlineMillis = 60_000,
    system = "You are a pirate. Answer in pirate speak.",
)

suspend fun firstSentence(ai: Tune2AI): String {
    val text = StringBuilder()
    ai.chat.send("Tell me a long story").takeWhile { event ->
        if (event is Tune2AIEvent.TextDelta) text.append(event.text)
        !text.contains('.')
    }.collect { }
    return text.toString()
}

fun startFromJavaStyleCode(ai: Tune2AI, onEvent: (Tune2AIEvent) -> Unit): Cancellable =
    ai.chat.stream("Hello", listener = EventListener { onEvent(it) })
