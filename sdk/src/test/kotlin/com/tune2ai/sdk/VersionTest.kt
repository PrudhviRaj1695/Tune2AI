package com.tune2ai.sdk

import com.tune2ai.sdk.api.SDK_VERSION
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun versionIsSemver() {
        assertTrue(SDK_VERSION, Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""").matches(SDK_VERSION))
    }
}
