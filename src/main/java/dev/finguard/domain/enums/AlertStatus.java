package dev.finguard.domain.enums;

public enum AlertStatus {
    NEW("New"),
    REVIEWED("Reviewed"),
    CONFIRMED_FRAUD("Confirmed Fraud"),
    FALSE_POSITIVE("False Positive");

    private final String displayName;

    AlertStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
