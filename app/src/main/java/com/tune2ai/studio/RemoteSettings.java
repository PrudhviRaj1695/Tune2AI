package com.tune2ai.studio;

/**
 * The remote endpoint Studio talks to, set from Run setup. Kept in memory only: the API key is
 * never written to disk or logs, and is forgotten when the app process ends (NFR-2).
 */
final class RemoteSettings {
    volatile String baseUrl;
    volatile String apiKey;
    volatile String model;
}
