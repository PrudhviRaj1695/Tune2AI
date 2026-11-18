package com.tune2ai.sdk.remote

import java.io.BufferedReader

/**
 * Minimal server-sent events parser: yields each event's `data`, joining multi-line data with '\n'.
 * Comments (`:`) and other fields (`event:`, `id:`, `retry:`) are ignored. Handles \n, \r\n, and \r.
 */
internal fun BufferedReader.sseData(): Sequence<String> = sequence {
    val data = StringBuilder()
    var hasData = false
    while (true) {
        val line = readLine() ?: break
        when {
            line.isEmpty() -> if (hasData) {
                yield(data.toString())
                data.clear()
                hasData = false
            }
            line.startsWith("data:") -> {
                if (hasData) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
                hasData = true
            }
            else -> Unit
        }
    }
    if (hasData) yield(data.toString())
}
