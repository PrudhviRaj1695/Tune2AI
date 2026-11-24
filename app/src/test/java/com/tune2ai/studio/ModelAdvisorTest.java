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
        // CPU only: at most 1.5B, so answers stay fast.
        assertEquals("Qwen2.5 1.5B", best(7_400_000_000L));
        assertEquals("Qwen2.5 1.5B", best(16_000_000_000L));
        assertEquals("Qwen2.5 1.5B", best(5_600_000_000L));
        assertEquals("Llama 3.2 1B", best(3_700_000_000L));
        assertEquals("Qwen2.5 0.5B", best(2_800_000_000L));
        assertEquals("SmolLM2 360M", best(1_800_000_000L));
    }

    @Test
    public void aPhoneWithAGpuGetsTheGpuBuild() {
        assertEquals("Llama 3.2 3B GPU", bestOf(ModelAdvisor.assess(7_400_000_000L, PLENTY, PLENTY, true, true)));
        assertEquals("Llama 3.2 1B GPU", bestOf(ModelAdvisor.assess(3_700_000_000L, PLENTY, PLENTY, true, true)));
        // 7B and 8B fit a 16 GB phone but are never the recommendation.
        assertEquals("Llama 3.2 3B GPU", bestOf(ModelAdvisor.assess(16_000_000_000L, PLENTY, PLENTY, true, true)));
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
    public void currentAvailableMemoryCanBlockAModelThatFitsTheDeviceBudget() {
        List<ModelAdvisor.Assessment> all = ModelAdvisor.assess(12 * GB, GB, PLENTY, true);
        assertEquals("Qwen2.5 0.5B", bestOf(all));
        assertEquals(ModelAdvisor.Fit.TOO_LARGE, all.get(3).fit);
    }

    @Test
    public void lowStorageBlocksDownloadsThatWouldFillThePhone() {
        List<ModelAdvisor.Assessment> all = ModelAdvisor.assess(12 * GB, 12 * GB, GB, true);
        // The 773 MB Q4_0 build still leaves the 200 MB reserve; the 808 MB Q4_K_M build does not.
        assertEquals("Llama 3.2 1B GPU", bestOf(all));
        assertEquals(ModelAdvisor.Fit.NO_STORAGE, named(all, "Llama 3.2 1B").fit);
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

    @Test
    public void embeddingModelIsDownloadableButNeverRecommendedForChat() {
        assertEquals(ModelAdvisor.EMBEDDER, ModelAdvisor.find("bge-small-en-v1.5-q8_0"));
        assertEquals(ModelAdvisor.NOMIC, ModelAdvisor.find("nomic-embed-text-v1.5.q8_0"));
        assertEquals(ModelAdvisor.GEMMA, ModelAdvisor.find("embeddinggemma-300m-q8_0"));
        for (ModelAdvisor.Assessment a : ModelAdvisor.assess(12 * GB, PLENTY, true)) {
            assertTrue(!ModelAdvisor.isEmbedder(a.entry));
        }
        assertEquals(ModelAdvisor.GEMMA, ModelAdvisor.recommendedEmbedder(7_400_000_000L));
        assertEquals(ModelAdvisor.NOMIC, ModelAdvisor.recommendedEmbedder(3_700_000_000L));
        assertEquals(ModelAdvisor.EMBEDDER, ModelAdvisor.recommendedEmbedder(2_800_000_000L));
        assertEquals(512, ModelAdvisor.embedderContext(ModelAdvisor.EMBEDDER));
        assertEquals(2048, ModelAdvisor.embedderContext(ModelAdvisor.GEMMA));
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

    private static ModelAdvisor.Assessment named(List<ModelAdvisor.Assessment> all, String name) {
        for (ModelAdvisor.Assessment a : all) if (a.entry.name.equals(name)) return a;
        throw new AssertionError("no catalog entry " + name);
    }
}
