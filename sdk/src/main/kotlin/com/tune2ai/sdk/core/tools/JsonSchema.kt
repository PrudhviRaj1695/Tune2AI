package com.tune2ai.sdk.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validates JSON against the schema subset tools use: `type` (object, string, number, integer,
 * boolean, array), `properties`, `required`, `items`, `enum`. Unknown keywords are ignored.
 * ponytail: a subset by design; adopt a full JSON Schema library if tools need more.
 */
internal object JsonSchema {
    private val TYPES = setOf("object", "string", "number", "integer", "boolean", "array")

    /** Problems with [schema] itself, or empty if it is usable. */
    fun check(schema: JsonElement, path: String = "$"): List<String> {
        if (schema !is JsonObject) return listOf("$path: schema must be an object")
        val problems = mutableListOf<String>()
        val type = (schema["type"] as? JsonPrimitive)?.content
        if (type != null && type !in TYPES) problems += "$path: unsupported type '$type'"
        (schema["properties"] as? JsonObject)?.forEach { (name, sub) -> problems += check(sub, "$path.$name") }
        schema["items"]?.let { problems += check(it, "$path[]") }
        return problems
    }

    /** Ways [value] does not match [schema], or empty if it matches. */
    fun validate(schema: JsonObject, value: JsonElement, path: String = "$"): List<String> {
        val errors = mutableListOf<String>()
        schema["enum"]?.jsonArray?.let { allowed ->
            if (value !in allowed) errors += "$path must be one of ${allowed.joinToString()}"
        }
        when ((schema["type"] as? JsonPrimitive)?.content) {
            "object" -> {
                if (value !is JsonObject) return errors + "$path must be an object"
                schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.forEach {
                    if (it !in value) errors += "$path.$it is required"
                }
                (schema["properties"] as? JsonObject)?.forEach { (name, sub) ->
                    value[name]?.let { errors += validate(sub.jsonObject, it, "$path.$name") }
                }
            }
            "array" -> {
                if (value !is JsonArray) return errors + "$path must be an array"
                (schema["items"] as? JsonObject)?.let { items ->
                    value.forEachIndexed { i, item -> errors += validate(items, item, "$path[$i]") }
                }
            }
            "string" -> if (value !is JsonPrimitive || !value.isString) errors += "$path must be a string"
            "number" -> if (!isNumber(value)) errors += "$path must be a number"
            "integer" -> if (!isNumber(value) || !(value as JsonPrimitive).content.matches(Regex("-?\\d+"))) errors += "$path must be an integer"
            "boolean" -> if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) errors += "$path must be a boolean"
        }
        return errors
    }

    private fun isNumber(value: JsonElement) =
        value is JsonPrimitive && value !is JsonNull && !value.isString && value.doubleOrNull != null
}
