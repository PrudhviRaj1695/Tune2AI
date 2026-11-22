package com.tune2ai.sdk

import android.content.Context
import com.tune2ai.sdk.api.AgentApi
import com.tune2ai.sdk.api.BackendInfo
import com.tune2ai.sdk.api.ChatApi
import com.tune2ai.sdk.api.KnowledgeApi
import com.tune2ai.sdk.api.PrivacyApi
import com.tune2ai.sdk.api.ModelsApi
import com.tune2ai.sdk.api.ResourceLimits
import com.tune2ai.sdk.api.ResourceSnapshot
import com.tune2ai.sdk.api.Tool
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.Platform
import com.tune2ai.sdk.core.RequestController
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.core.agent.AgentEngine
import com.tune2ai.sdk.core.tools.ToolCallingEngine
import com.tune2ai.sdk.inference.BackendRegistry
import com.tune2ai.sdk.inference.InferenceBackend
import com.tune2ai.sdk.platform.android.AndroidPlatform
import com.tune2ai.sdk.services.context.ContextManager
import com.tune2ai.sdk.services.context.ContextSource
import com.tune2ai.sdk.services.embedding.EmbeddingService
import com.tune2ai.sdk.services.guard.PrivacyGuard
import com.tune2ai.sdk.services.model.ModelManager
import com.tune2ai.sdk.services.prompt.PromptManager
import com.tune2ai.sdk.services.prompt.PromptTemplate
import com.tune2ai.sdk.services.rag.RagContextSource
import com.tune2ai.sdk.services.rag.RagEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The SDK entry point (FR-SDK-1). Build one per process and [close] it when done.
 *
 * ```
 * val ai = Tune2AI.Builder(context).addBackend(LlamaBackend()).build()
 * ai.chat.send("Hello").collect { event -> … }
 * ```
 */
class Tune2AI private constructor(
    private val platform: Platform,
    private val backends: BackendRegistry,
    limits: ResourceLimits,
    private val prompts: PromptManager,
    defaultTemplate: String?,
    tools: List<Tool>,
    contextSources: List<ContextSource>,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = ModelManager(backends.all, platform.storage, platform.resources, limits, scope)
    private val ready = scope.async { manager.restore() }
    private val router = Router(manager)
    private val rag = RagEngine(platform.storage, EmbeddingService(manager, router))

    /** Guardrails for personal data: redaction and keeping personal context off remote models. */
    val privacy = PrivacyApi(PrivacyGuard())

    /** On-device knowledge (RAG): add documents; requests then include the relevant parts. */
    val knowledge = KnowledgeApi(rag, ready)

    private val controller = RequestController(
        manager,
        router,
        ContextManager(
            prompts,
            defaultTemplate,
            sources = contextSources + RagContextSource(rag, { knowledge.passagesPerAnswer }, { knowledge.minSimilarity }, { knowledge.includeNeighbors }, { knowledge.enabled }),
            privacy = privacy,
        ),
    )

    val chat = ChatApi(controller, platform.storage, scope, ready)
    val agent = AgentApi(AgentEngine(controller, ToolCallingEngine(tools), redact = { privacy.apply(it).text }), scope, ready)
    val models = ModelsApi(manager, scope, ready)

    /** Registered backends and whether each can run right now. */
    fun backends(): List<BackendInfo> = backends.describe()

    /**
     * Registers a prompt template while the app runs, for example a user-edited system prompt. A
     * higher version of an existing id becomes that id's default for later requests.
     */
    fun addPromptTemplate(template: PromptTemplate) = prompts.register(template)

    /** Latest device memory, battery, and thermal reading. */
    fun resources(): ResourceSnapshot = platform.resources.snapshot.value

    /** Cancels running requests, unloads models, and releases platform resources. */
    override fun close() {
        scope.cancel()
        runBlocking(Dispatchers.Default) {
            withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { manager.models().forEach { manager.unload(it.id) } }
        }
        platform.close()
    }

    class Builder private constructor(private val platform: () -> Platform) {
        constructor(context: Context) : this({ AndroidPlatform(context) })

        private val backends = BackendRegistry()
        private val prompts = PromptManager()
        private var limits = ResourceLimits()
        private var defaultTemplate: String? = null
        private val tools = mutableListOf<Tool>()
        private val contextSources = mutableListOf<ContextSource>()

        /** Registers a backend; fails fast on a duplicate id or missing capabilities. */
        fun addBackend(backend: InferenceBackend) = apply { backends.register(backend) }

        fun resourceLimits(limits: ResourceLimits) = apply { this.limits = limits }

        /** Offers a host-provided tool to agents (decision D6). Validated when [build] runs. */
        fun addTool(tool: Tool) = apply { tools += tool }

        /**
         * Adds app-provided context (for example live data the app reads at request time). Its items
         * reach the model through the Context Manager, before retrieved knowledge. Names must be unique.
         */
        fun addContextSource(source: ContextSource) = apply {
            if (source.name == "rag" || contextSources.any { it.name == source.name }) {
                throw Tune2AIException.InvalidRequest("A context source named '${source.name}' is already registered")
            }
            contextSources += source
        }

        fun addPromptTemplate(template: PromptTemplate) = apply { prompts.register(template) }

        /** Template used as the system prompt when a request does not name one. */
        fun defaultPromptTemplate(id: String) = apply { defaultTemplate = id }

        fun build(): Tune2AI {
            if (backends.all.isEmpty()) throw Tune2AIException.InvalidRequest("Add at least one inference backend with addBackend(...)")
            defaultTemplate?.let { prompts.get(it) } // fails now if the default template was never registered
            ToolCallingEngine(tools) // fails now on invalid names or schemas
            return Tune2AI(platform(), backends, limits, prompts, defaultTemplate, tools.toList(), contextSources.toList())
        }

        internal companion object {
            fun forTests(platform: Platform) = Builder { platform }
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS = 2_000L
    }
}
