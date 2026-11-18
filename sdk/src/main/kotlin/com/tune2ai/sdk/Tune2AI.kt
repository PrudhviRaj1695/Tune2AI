package com.tune2ai.sdk

import android.content.Context
import com.tune2ai.sdk.api.BackendInfo
import com.tune2ai.sdk.api.ChatApi
import com.tune2ai.sdk.api.ModelsApi
import com.tune2ai.sdk.api.ResourceLimits
import com.tune2ai.sdk.api.ResourceSnapshot
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.Platform
import com.tune2ai.sdk.core.RequestController
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.inference.BackendRegistry
import com.tune2ai.sdk.inference.InferenceBackend
import com.tune2ai.sdk.platform.android.AndroidPlatform
import com.tune2ai.sdk.services.context.ContextManager
import com.tune2ai.sdk.services.model.ModelManager
import com.tune2ai.sdk.services.prompt.PromptManager
import com.tune2ai.sdk.services.prompt.PromptTemplate
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
    prompts: PromptManager,
    defaultTemplate: String?,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = ModelManager(backends.all, platform.storage, platform.resources, limits, scope)
    private val ready = scope.async { manager.restore() }
    private val controller = RequestController(manager, Router(manager), ContextManager(prompts, defaultTemplate))

    val chat = ChatApi(controller, platform.storage, scope, ready)
    val models = ModelsApi(manager, scope, ready)

    /** Registered backends and whether each can run right now. */
    fun backends(): List<BackendInfo> = backends.describe()

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

        /** Registers a backend; fails fast on a duplicate id or missing capabilities. */
        fun addBackend(backend: InferenceBackend) = apply { backends.register(backend) }

        fun resourceLimits(limits: ResourceLimits) = apply { this.limits = limits }

        fun addPromptTemplate(template: PromptTemplate) = apply { prompts.register(template) }

        /** Template used as the system prompt when a request does not name one. */
        fun defaultPromptTemplate(id: String) = apply { defaultTemplate = id }

        fun build(): Tune2AI {
            if (backends.all.isEmpty()) throw Tune2AIException.InvalidRequest("Add at least one inference backend with addBackend(...)")
            defaultTemplate?.let { prompts.get(it) } // fails now if the default template was never registered
            return Tune2AI(platform(), backends, limits, prompts, defaultTemplate)
        }

        internal companion object {
            fun forTests(platform: Platform) = Builder { platform }
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS = 2_000L
    }
}
