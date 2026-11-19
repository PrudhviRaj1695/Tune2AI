package com.tune2ai.sdk.services.prompt

import com.tune2ai.sdk.api.Tune2AIException.InvalidRequest

/**
 * A versioned system prompt with `{{variable}}` placeholders (FR-SVC-2). Write `\{{` for a literal `{{`.
 * Every placeholder must be declared in [variables]; that is checked when the template is created.
 */
data class PromptTemplate @JvmOverloads constructor(
    val id: String,
    val version: Int,
    val text: String,
    val variables: Set<String> = emptySet(),
) {
    init {
        val used = PLACEHOLDER.findAll(text).filter { it.groupValues[1].isEmpty() }.map { it.groupValues[2] }.toSet()
        require(used.all { it in variables }) { "Template $ref uses undeclared variables: ${used - variables}" }
    }

    /** `id@version`, as reported in request trace sources. */
    val ref: String get() = "$id@$version"

    /** Fills the placeholders. Fails before any model call if a variable is missing or unknown. */
    fun render(values: Map<String, String> = emptyMap()): String {
        val missing = variables - values.keys
        if (missing.isNotEmpty()) throw InvalidRequest("Prompt $ref is missing variables: ${missing.sorted()}")
        val unknown = values.keys - variables
        if (unknown.isNotEmpty()) throw InvalidRequest("Prompt $ref does not declare variables: ${unknown.sorted()}")
        return PLACEHOLDER.replace(text) { m -> if (m.groupValues[1].isNotEmpty()) "{{" + m.value.substring(3) else values.getValue(m.groupValues[2]) }
    }

    private companion object {
        // Braces are escaped on both sides: Android's ICU regex engine rejects a bare `}` that the JVM accepts.
        val PLACEHOLDER = Regex("""(\\?)\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}""")
    }
}

/** Registry of prompt templates. The latest version of an id is used unless a request pins one. */
internal class PromptManager {
    private val templates = HashMap<String, java.util.TreeMap<Int, PromptTemplate>>()

    fun register(template: PromptTemplate) = synchronized(templates) {
        templates.getOrPut(template.id) { java.util.TreeMap() }[template.version] = template
    }

    /** The template for [id], at [version] or the latest. */
    fun get(id: String, version: Int? = null): PromptTemplate = synchronized(templates) {
        val versions = templates[id] ?: throw InvalidRequest("Unknown prompt template '$id'")
        if (version == null) versions.lastEntry()!!.value
        else versions[version] ?: throw InvalidRequest("Prompt template '$id' has no version $version")
    }
}
