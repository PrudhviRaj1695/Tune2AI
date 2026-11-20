package com.tune2ai.studio;

/** Holds only the latest aggregated finance summary for the current app process. */
final class FinanceDataStore {
    private volatile String summaryJson;

    void replace(String summaryJson) {
        this.summaryJson = summaryJson;
    }

    String getSummaryJson() {
        String value = summaryJson;
        if (value == null) throw new IllegalStateException("No finance summary is available");
        return value;
    }

    boolean hasSummary() {
        return summaryJson != null;
    }

    void clear() {
        summaryJson = null;
    }
}
