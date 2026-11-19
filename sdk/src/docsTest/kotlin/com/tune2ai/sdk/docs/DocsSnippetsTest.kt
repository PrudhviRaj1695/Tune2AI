package com.tune2ai.sdk.docs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every ```kotlin block in docs/ must appear verbatim in the compiled examples next to this file, so a
 * documented snippet cannot drift from code that compiles and runs. Blocks tagged ```kotlin nocheck
 * (they need modules the SDK tests do not depend on) are skipped.
 */
class DocsSnippetsTest {
    private val docs = File("../docs")
    private val examples = File("src/docsTest/kotlin/com/tune2ai/sdk/docs")

    private fun normalize(text: String) = text.replace("\r\n", "\n").lines().joinToString("\n") { it.trimEnd() }.trim()

    /** Contents of plain ```kotlin fences only; every other fence (```groovy, ```kotlin nocheck) is skipped whole. */
    private fun kotlinBlocks(markdown: String): List<String> {
        val found = mutableListOf<String>()
        var fence: String? = null
        val body = mutableListOf<String>()
        for (line in markdown.replace("\r\n", "\n").lines()) {
            val trimmed = line.trim()
            when {
                fence == null && trimmed.startsWith("```") -> { fence = trimmed.removePrefix("```").trim(); body.clear() }
                fence != null && trimmed == "```" -> {
                    if (fence == "kotlin") found += normalize(body.joinToString("\n"))
                    fence = null
                }
                fence != null -> body += line
            }
        }
        return found
    }

    @Test
    fun everyKotlinBlockInTheDocsIsCompiledCode() {
        assertTrue("docs or examples directory not found from ${File(".").absolutePath}", docs.isDirectory && examples.isDirectory)
        val source = normalize(examples.listFiles { f -> f.name.endsWith("Examples.kt") }!!.joinToString("\n") { it.readText() })

        val pages = docs.walkTopDown().filter { it.isFile && it.extension == "md" && it.parentFile.name != "backlog" }.toList()
        var checked = 0
        val missing = mutableListOf<String>()
        for (page in pages) {
            for (block in kotlinBlocks(page.readText())) {
                checked++
                if (block !in source) missing += "${page.relativeTo(docs).path}:\n$block"
            }
        }
        assertTrue("these snippets are not in the compiled examples (edit sdk/src/docsTest, then copy them into the doc):\n\n${missing.joinToString("\n\n")}", missing.isEmpty())
        assertTrue("expected the docs to contain many checked snippets, found $checked", checked >= 15)
    }
}
