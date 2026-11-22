package com.tune2ai.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class ModelAdvisorTest {
    private static final long GB = 1_000_000_000L;
    private static final long PLENTY = 64 * GB;

    /** Phones report less than the marketed RAM: an "8 GB" phone typically reports about 7.4 GB. */
    @Test
    public void recommendsLargestModelThatFits() {
        assertEquals("Llama 3.2 3B", best(7_400_000_000L));
        assertEquals("Qwen2.5 1.5B", best(5_600_000_000L));
        assertEquals("Llama 3.2 1B", best(3_700_000_000L));
        assertEquals("Qwen2.5 0.5B", best(2_800_000_000L));
        assertEquals("SmolLM2 360M", best(1_800_000_000L));
    }

    @Test
    public void modelsAboveTheBudgetAreTooLarge() {
        List<ModelAdvisor.Assessment> all = ModelAdvisor.assess(5_600_000_000L, PLENTY, true);
        ModelAdvisor.Assessment big = all.get(all.size() - 1);
        assertEquals(ModelAdvisor.Fit.TOO_LARGE, big.fit);
        for (ModelAdvisor.Assessment a : all) {
            if (a.fit == ModelAdvisor.Fit.FITS || a.fit == ModelAdvisor.Fit.BEST) {
                assertTrue(a.entry.memoryBytes() <= 5_600_000_000L * ModelAdvisor.RAM_BUDGET);
            }
        }
    }

    @Test
    public void lowStorageBlocksDownloadsThatWouldFillThePhone() {
        List<ModelAdvisor.Assessment> all = ModelAdvisor.assess(12 * GB, GB, true);
        assertEquals("Qwen2.5 0.5B", bestOf(all));
        assertEquals(ModelAdvisor.Fit.NO_STORAGE, all.get(3).fit);
    }

    @Test
    public void unsupportedAbiHasNoRecommendation() {
        List<ModelAdvisor.Assessment> all = ModelAdvisor.assess(12 * GB, PLENTY, false);
        assertNull(bestOf(all));
        for (ModelAdvisor.Assessment a : all) assertEquals(ModelAdvisor.Fit.UNSUPPORTED, a.fit);
    }

    @Test
    public void tinyPhoneGetsNothing() {
        assertNull(best(500_000_000L));
    }

    @Test
    public void catalogIdsMatchTheIdsStudioRegistersDownloadsUnder() {
        ModelAdvisor.Entry e = ModelAdvisor.find("llama-3.2-1b-instruct-q4_k_m");
        assertNotNull(e);
        assertEquals("https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf", e.url);
        for (ModelAdvisor.Entry c : ModelAdvisor.CATALOG) assertTrue(c.url.startsWith("https://huggingface.co/"));
    }

    private static String best(long totalRam) {
        return bestOf(ModelAdvisor.assess(totalRam, PLENTY, true));
    }

    private static String bestOf(List<ModelAdvisor.Assessment> all) {
        String best = null;
        for (ModelAdvisor.Assessment a : all) {
            if (a.fit == ModelAdvisor.Fit.BEST) {
                assertNull("only one best", best);
                best = a.entry.name;
            }
        }
        return best;
    }
}
