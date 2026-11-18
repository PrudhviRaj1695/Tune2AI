package com.tune2ai.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tune2ai.sdk.platform.android.AndroidPlatform
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidPlatformTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun roundTripsARecordAndReadsResources() = runTest {
        AndroidPlatform(context).use { platform ->
            platform.storage.put("platform-test", "k", "v")
            assertEquals("v", platform.storage.get("platform-test", "k"))
            platform.storage.clear("platform-test")

            val s = platform.resources.snapshot.value
            assertTrue("total memory", s.totalMemoryBytes > 0)
            assertTrue("available memory", s.availableMemoryBytes in 1..s.totalMemoryBytes)
            assertTrue("battery", s.batteryPercent == null || s.batteryPercent in 0..100)
            assertTrue("files dir is app-private", File(platform.filesDir).canonicalPath.startsWith(context.dataDir.canonicalPath))
        }
    }
}
