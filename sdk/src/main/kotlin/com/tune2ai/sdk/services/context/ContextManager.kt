package com.tune2ai.sdk.services.context

import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Role
import com.tune2ai.sdk.api.Tune2AIException.InvalidRequest
import com.tune2ai.sdk.core.AssembledContext
import com.tune2ai.sdk.core.ContextAssembler
import com.tune2ai.sdk.services.prompt.PromptManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A piece of retrieved knowledge or memory, with a reference reported in the request trace. */
data class ContextItem(val ref: String, val text: String)

/** Memory (T2AI-025) and RAG (T2AI-024) plug in here; the Context Manager is their only path to a model. */
interface ContextSource {
    val name: String
    suspend fun fetch(query: String, model: ModelDescriptor): List<ContextItem>

    /** How this source's items appear in the system prompt. */
    fun render(items: List<ContextItem>): String = "Relevant information:\n" + items.joinToString("\n") { "- ${it.text}" }
}

/**
 * A [ContextSource] for Java callers: implement [fetchBlocking], which runs on a background thread.
 * A failure leaves the request without this source's items instead of failing it.
 */
abstract class BlockingContextSource : ContextSource {
    abstract fun fetchBlocking(query: String, model: ModelDescriptor): List<ContextItem>

    override suspend fun fetch(query: String, model: ModelDescriptor): List<ContextItem> =
        withContext(Dispatchers.IO) {
            try {
                fetchBlocking(query, model)
            } catch (e: Exception) {
                emptyList()
            }
        }
}

/**
 * Builds the model input (FR-SVC-3), in order: system prompt → memory/retrieved items →
 * conversation history → the user's message. Keeps the total within the model's context minus the
 * tokens reserved for output, dropping the oldest conversation turns first, and reports what was
 * included and what was left out.
 */
class ContextManager(
    private val prompts: PromptManager,
    private val defaultTemplate: String? = null,
    private val sources: List<ContextSource> = emptyList(),
    private val countTokens: (String) -> Int = ::estimateTokens,
) : ContextAssembler {

    override suspend fun assemble(messages: List<ChatMessage>, model: ModelDescriptor, options: RequestOptions): AssembledContext {
        val included = mutableListOf<String>()
        val omitted = mutableListOf<String>()
        val budget = model.contextLength - options.maxTokens

        val systemParts = mutableListOf<String>()
        (options.promptTemplate ?: defaultTemplate)?.let { id ->
            val template = prompts.get(id)
            systemParts += template.render(options.promptVariables)
            included += "prompt:${template.ref}"
        }
        messages.filter { it.role == Role.SYSTEM }.forEach { systemParts += it.content }

        val question = messages.last().content
        for (source in sources) {
            val items = source.fetch(question, model)
            if (items.isEmpty()) continue
            systemParts += source.render(items)
            items.forEach { included += "${source.name}:${it.ref}" }
        }

        val system = systemParts.takeIf { it.isNotEmpty() }?.let { ChatMessage.system(it.joinToString("\n\n")) }
        val turns = messages.filter { it.role != Role.SYSTEM }
        val latest = turns.last()
        var history = turns.dropLast(1)

        fun cost() = listOfNotNull(system).sumOf { tokens(it) } + history.sumOf { tokens(it) } + tokens(latest)
        if (listOfNotNull(system).sumOf { tokens(it) } + tokens(latest) > budget) {
            throw InvalidRequest("The message and system prompt need about ${cost()} tokens; ${model.name} allows $budget after reserving ${options.maxTokens} for the reply")
        }
        var dropped = 0
        while (cost() > budget && history.isNotEmpty()) {
            history = history.drop(1)
            dropped++
        }
        if (history.isNotEmpty()) included += "conversation:${history.size} messages"
        if (dropped > 0) omitted += "conversation:$dropped oldest messages"

        val assembled = listOfNotNull(system) + history + latest
        val summary = buildList {
            add(if (included.isEmpty()) "Message only" else included.joinToString(" + ") { it.substringBefore(':').replaceFirstChar(Char::uppercase) })
            if (omitted.isNotEmpty()) add("trimmed $dropped")
        }.joinToString(" · ")
        return AssembledContext(assembled, summary, included, omitted)
    }

    private fun tokens(message: ChatMessage) = countTokens(message.content) + MESSAGE_OVERHEAD

    private companion object {
        const val MESSAGE_OVERHEAD = 4 // role markers added by chat templates
    }
}

/** ponytail: ~4 characters per token; use the backend's tokenizer if trimming proves too loose or too tight. */
fun estimateTokens(text: String): Int = (text.length + 3) / 4
