package dev.finguard.domain.enums;

public enum DetectionConfig {
    RULES_ONLY("Rules Only"),
    ML_ONLY("ML Only"),
    ML_LLM_DIRECT("ML + LLM (Direct)"),
    ML_LLM_RAG("ML + LLM + RAG"),
    FULL_SYSTEM("Full System");

    private final String displayName;

    DetectionConfig(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
