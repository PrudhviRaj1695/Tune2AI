package com.tune2ai.sdk

import com.tune2ai.sdk.api.SDK_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VersionTest {
    @Test
    fun versionIsSemver() {
        assertTrue(SDK_VERSION, Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""").matches(SDK_VERSION))
    }

    @Test
    fun versionMatchesTheGradleProperty() {
        val published = File("../gradle.properties").readLines().first { it.startsWith("VERSION_NAME=") }.substringAfter("=").trim()
        assertEquals("Version.kt and gradle.properties must agree (bump both)", published, SDK_VERSION)
    }
}
