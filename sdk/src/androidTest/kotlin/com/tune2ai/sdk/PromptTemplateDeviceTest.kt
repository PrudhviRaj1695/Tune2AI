package com.tune2ai.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune2ai.sdk.services.prompt.PromptTemplate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Android's regex engine (ICU) differs from the JVM's; JVM unit tests alone missed a crash here. */
@RunWith(AndroidJUnit4::class)
class PromptTemplateDeviceTest {
    @Test
    fun rendersOnAndroidRegexEngine() {
        val template = PromptTemplate("t", 1, """Hello {{ name }}, keep \{{this}}.""", setOf("name"))
        assertEquals("Hello Ada, keep {{this}}.", template.render(mapOf("name" to "Ada")))
    }
}
