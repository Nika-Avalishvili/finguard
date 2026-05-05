package dev.finguard.domain.enums;

public enum ExplanationType {
    TEMPLATE("Template"),
    SHAP("SHAP Feature Importance"),
    LLM_DIRECT("LLM Direct"),
    LLM_RAG("LLM + RAG"),
    LLM_RAG_VALIDATED("LLM + RAG + Validation");

    private final String displayName;

    ExplanationType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
