package com.tune2ai.studio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The downloadable models Studio offers, and which of them fit a given phone. Plain Java with no
 * Android types, so the fit rules run in JVM tests.
 *
 * <p>A model fits when its estimated loaded size stays within {@link #RAM_BUDGET} of the phone's
 * total memory and the file fits in free storage. Android starts killing apps well before memory
 * is full, so the budget leaves room for the OS, the WebView, and other apps.
 * ponytail: total-RAM share is a heuristic; replace with measured per-device limits if real
 * devices still get killed.
 */
final class ModelAdvisor {
    /** Share of total RAM one loaded model may use. 8 GB phone: 3B fits; 6 GB: 1.5B; 4 GB: 1B. */
    static final double RAM_BUDGET = 0.35;
    /** Context length Studio loads models with; matches StudioBridge.CONTEXT_LENGTH. */
    static final int CONTEXT_LENGTH = 4096;
    /** Storage kept free after a download so the phone stays usable. */
    static final long STORAGE_RESERVE = 200L * 1024 * 1024;

    enum Fit { BEST, FITS, TOO_LARGE, NO_STORAGE, UNSUPPORTED }

    static final class Entry {
        final String id;          // lowercased file name without .gguf: the id StudioBridge registers
        final String name;
        final String fileName;
        final String url;
        final long sizeBytes;     // exact size of the file on Hugging Face
        final String blurb;

        Entry(String name, String repo, String fileName, long sizeBytes, String blurb) {
            this.id = fileName.substring(0, fileName.length() - ".gguf".length()).toLowerCase(java.util.Locale.ROOT);
            this.name = name;
            this.fileName = fileName;
            this.url = "https://huggingface.co/" + repo + "/resolve/main/" + fileName;
            this.sizeBytes = sizeBytes;
            this.blurb = blurb;
        }

        /** Same formula as LlamaBackend.estimateMemoryBytes, from the known file size. */
        long memoryBytes() {
            return sizeBytes + sizeBytes / 10 + CONTEXT_LENGTH * 64L * 1024;
        }
    }

    /** Smallest first. Sizes and file names checked against the Hugging Face repos. */
    static final List<Entry> CATALOG = Collections.unmodifiableList(Arrays.asList(
            new Entry("SmolLM2 135M", "bartowski/SmolLM2-135M-Instruct-GGUF",
                    "SmolLM2-135M-Instruct-Q8_0.gguf", 144_811_360L, "Tiny and fast. Good for testing, weak answers."),
            new Entry("SmolLM2 360M", "bartowski/SmolLM2-360M-Instruct-GGUF",
                    "SmolLM2-360M-Instruct-Q4_K_M.gguf", 270_590_880L, "Small and quick on older phones."),
            new Entry("Qwen2.5 0.5B", "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                    "qwen2.5-0.5b-instruct-q4_k_m.gguf", 491_400_032L, "Smallest model that handles Agent tools."),
            new Entry("Llama 3.2 1B", "bartowski/Llama-3.2-1B-Instruct-GGUF",
                    "Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807_694_464L, "Balanced quality and speed."),
            new Entry("Qwen2.5 1.5B", "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
                    "qwen2.5-1.5b-instruct-q4_k_m.gguf", 1_117_320_736L, "Better answers and tool use."),
            new Entry("Llama 3.2 3B", "bartowski/Llama-3.2-3B-Instruct-GGUF",
                    "Llama-3.2-3B-Instruct-Q4_K_M.gguf", 2_019_377_696L, "Best quality here. Needs a high-end phone.")));

    static final class Assessment {
        final Entry entry;
        final Fit fit;
        final String reason;

        Assessment(Entry entry, Fit fit, String reason) {
            this.entry = entry;
            this.fit = fit;
            this.reason = reason;
        }
    }

    private ModelAdvisor() {}

    /**
     * Rates every catalog entry for a phone. The largest entry that fits is {@link Fit#BEST}.
     *
     * @param totalRamBytes total memory the OS reports (ActivityManager.MemoryInfo.totalMem)
     * @param freeStorageBytes free space where models are stored
     * @param supportedAbi whether the phone runs one of the ABIs llama.cpp is built for
     */
    static List<Assessment> assess(long totalRamBytes, long freeStorageBytes, boolean supportedAbi) {
        long budget = (long) (totalRamBytes * RAM_BUDGET);
        List<Assessment> out = new ArrayList<>();
        int best = -1;
        for (Entry e : CATALOG) {
            Fit fit;
            String reason;
            if (!supportedAbi) {
                fit = Fit.UNSUPPORTED;
                reason = "This phone's processor type is not supported (needs 64-bit ARM or x86).";
            } else if (e.memoryBytes() > budget) {
                fit = Fit.TOO_LARGE;
                reason = "Needs about " + gb(e.memoryBytes()) + " of memory; this phone can safely give " + gb(budget) + ".";
            } else if (e.sizeBytes + STORAGE_RESERVE > freeStorageBytes) {
                fit = Fit.NO_STORAGE;
                reason = "Needs " + gb(e.sizeBytes + STORAGE_RESERVE) + " of free storage; " + gb(freeStorageBytes) + " is free.";
            } else {
                fit = Fit.FITS;
                reason = "Uses about " + gb(e.memoryBytes()) + " of memory.";
                best = out.size();
            }
            out.add(new Assessment(e, fit, reason));
        }
        if (best >= 0) {
            Assessment a = out.get(best);
            out.set(best, new Assessment(a.entry, Fit.BEST, "Largest model that runs safely on this phone. " + a.reason));
        }
        return out;
    }

    static Entry find(String id) {
        for (Entry e : CATALOG) if (e.id.equals(id)) return e;
        return null;
    }

    static String gb(long bytes) {
        return bytes >= 1_000_000_000L
                ? String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
                : (bytes / 1_000_000L) + " MB";
    }
}
