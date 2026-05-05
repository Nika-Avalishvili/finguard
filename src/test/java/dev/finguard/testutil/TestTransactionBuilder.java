package dev.finguard.testutil;

import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Fluent builder for creating Transaction instances in tests.
 * Provides sensible defaults for all fields — override only what matters for each test.
 */
public class TestTransactionBuilder {

    private Long id = null;
    private String externalId = null;
    private DatasetSource datasetSource = DatasetSource.PAYSIM;
    private LocalDateTime timestamp = LocalDateTime.of(2025, 6, 15, 10, 30);
    private String senderAccount = "SENDER_001";
    private String receiverAccount = "RECEIVER_001";
    private TransactionType transactionType = TransactionType.TRANSFER;
    private BigDecimal amount = new BigDecimal("1000.00");
    private BigDecimal senderBalanceBefore = new BigDecimal("50000.00");
    private BigDecimal senderBalanceAfter = new BigDecimal("49000.00");
    private BigDecimal receiverBalanceBefore = BigDecimal.ZERO;
    private BigDecimal receiverBalanceAfter = new BigDecimal("1000.00");
    private Boolean isFraud = false;
    private Boolean isFlaggedFraud = false;

    public static TestTransactionBuilder aTransaction() {
        return new TestTransactionBuilder();
    }

    public TestTransactionBuilder withId(Long id) {
        this.id = id;
        return this;
    }

    public TestTransactionBuilder withExternalId(String externalId) {
        this.externalId = externalId;
        return this;
    }

    public TestTransactionBuilder withAmount(String amount) {
        this.amount = new BigDecimal(amount);
        return this;
    }

    public TestTransactionBuilder withAmount(BigDecimal amount) {
        this.amount = amount;
        return this;
    }

    public TestTransactionBuilder withType(TransactionType type) {
        this.transactionType = type;
        return this;
    }

    public TestTransactionBuilder from(String sender) {
        this.senderAccount = sender;
        return this;
    }

    public TestTransactionBuilder to(String receiver) {
        this.receiverAccount = receiver;
        return this;
    }

    public TestTransactionBuilder at(LocalDateTime timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public TestTransactionBuilder withSenderBalanceBefore(String balance) {
        this.senderBalanceBefore = new BigDecimal(balance);
        return this;
    }

    public TestTransactionBuilder withSenderBalanceAfter(String balance) {
        this.senderBalanceAfter = new BigDecimal(balance);
        return this;
    }

    public TestTransactionBuilder thatIsFraudulent() {
        this.isFraud = true;
        return this;
    }

    public TestTransactionBuilder withDatasetSource(DatasetSource source) {
        this.datasetSource = source;
        return this;
    }

    public Transaction build() {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setExternalId(externalId);
        tx.setDatasetSource(datasetSource);
        tx.setTimestamp(timestamp);
        tx.setSenderAccount(senderAccount);
        tx.setReceiverAccount(receiverAccount);
        tx.setTransactionType(transactionType);
        tx.setAmount(amount);
        tx.setSenderBalanceBefore(senderBalanceBefore);
        tx.setSenderBalanceAfter(senderBalanceAfter);
        tx.setReceiverBalanceBefore(receiverBalanceBefore);
        tx.setReceiverBalanceAfter(receiverBalanceAfter);
        tx.setIsFraud(isFraud);
        tx.setIsFlaggedFraud(isFlaggedFraud);
        return tx;
    }
}
