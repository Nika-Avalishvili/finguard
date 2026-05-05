package dev.finguard.domain.model;

import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.TransactionType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_transactions_sender", columnList = "senderAccount"),
    @Index(name = "idx_transactions_receiver", columnList = "receiverAccount"),
    @Index(name = "idx_transactions_timestamp", columnList = "timestamp"),
    @Index(name = "idx_transactions_is_fraud", columnList = "isFraud")
})
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 100)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(length = 50, nullable = false)
    private DatasetSource datasetSource;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(length = 100, nullable = false)
    private String senderAccount;

    @Column(length = 100, nullable = false)
    private String receiverAccount;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private TransactionType transactionType;

    @Column(precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(precision = 18, scale = 2)
    private BigDecimal senderBalanceBefore;

    @Column(precision = 18, scale = 2)
    private BigDecimal senderBalanceAfter;

    @Column(precision = 18, scale = 2)
    private BigDecimal receiverBalanceBefore;

    @Column(precision = 18, scale = 2)
    private BigDecimal receiverBalanceAfter;

    @Column(nullable = false)
    private Boolean isFraud = false;

    private Boolean isFlaggedFraud;

    /**
     * Train/test-split flag. Defaults to {@code true} so freshly ingested rows are
     * training-eligible and ML training works immediately after import. The newest 20%
     * can be carved off as a temporal test set via the
     * {@code /api/v1/ingestion/finalize-train-test-split} endpoint for honest evaluation.
     */
    @Column(name = "is_training_set", nullable = false)
    private Boolean isTrainingSet = true;

    /**
     * Cross-validation fold assignment in {@code [0, k-1]}, or {@code null} if
     * folds have not been computed for this row. See audit A-1 / changelog 015.
     * Populated by {@code TransactionRepository.assignFolds(k)}.
     */
    @Column(name = "fold")
    private Integer fold;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToOne(mappedBy = "transaction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private TransactionFeatures features;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public DatasetSource getDatasetSource() { return datasetSource; }
    public void setDatasetSource(DatasetSource datasetSource) { this.datasetSource = datasetSource; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getSenderAccount() { return senderAccount; }
    public void setSenderAccount(String senderAccount) { this.senderAccount = senderAccount; }

    public String getReceiverAccount() { return receiverAccount; }
    public void setReceiverAccount(String receiverAccount) { this.receiverAccount = receiverAccount; }

    public TransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getSenderBalanceBefore() { return senderBalanceBefore; }
    public void setSenderBalanceBefore(BigDecimal senderBalanceBefore) { this.senderBalanceBefore = senderBalanceBefore; }

    public BigDecimal getSenderBalanceAfter() { return senderBalanceAfter; }
    public void setSenderBalanceAfter(BigDecimal senderBalanceAfter) { this.senderBalanceAfter = senderBalanceAfter; }

    public BigDecimal getReceiverBalanceBefore() { return receiverBalanceBefore; }
    public void setReceiverBalanceBefore(BigDecimal receiverBalanceBefore) { this.receiverBalanceBefore = receiverBalanceBefore; }

    public BigDecimal getReceiverBalanceAfter() { return receiverBalanceAfter; }
    public void setReceiverBalanceAfter(BigDecimal receiverBalanceAfter) { this.receiverBalanceAfter = receiverBalanceAfter; }

    public Boolean getIsFraud() { return isFraud; }
    public void setIsFraud(Boolean isFraud) { this.isFraud = isFraud; }

    public Boolean getIsFlaggedFraud() { return isFlaggedFraud; }
    public void setIsFlaggedFraud(Boolean isFlaggedFraud) { this.isFlaggedFraud = isFlaggedFraud; }

    public Boolean getIsTrainingSet() { return isTrainingSet; }
    public void setIsTrainingSet(Boolean isTrainingSet) { this.isTrainingSet = isTrainingSet; }

    public Integer getFold() { return fold; }
    public void setFold(Integer fold) { this.fold = fold; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public TransactionFeatures getFeatures() { return features; }
    public void setFeatures(TransactionFeatures features) { this.features = features; }
}
