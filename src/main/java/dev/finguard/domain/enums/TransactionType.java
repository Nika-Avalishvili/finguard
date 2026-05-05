package dev.finguard.domain.enums;

public enum TransactionType {
    TRANSFER("Transfer"),
    PAYMENT("Payment"),
    CASH_OUT("Cash Out"),
    CASH_IN("Cash In"),
    DEBIT("Debit");

    private final String displayName;

    TransactionType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
