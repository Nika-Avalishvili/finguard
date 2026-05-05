package dev.finguard.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;

@Entity
@Table(name = "transaction_features")
public class TransactionFeatures {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false, unique = true)
    private Transaction transaction;

    private Double amountZscore;

    @Column(name = "tx_velocity_1h")
    private Integer txVelocity1h;

    @Column(name = "tx_velocity_24h")
    private Integer txVelocity24h;

    @Column(name = "avg_amount_7d", precision = 18, scale = 2)
    private BigDecimal avgAmount7d;

    private Double amountRatioToAvg;

    private Double balanceChangeRatio;

    private Boolean isNewReceiver;

    @Column(name = "receiver_diversity_7d")
    private Integer receiverDiversity7d;

    private Short hourOfDay;

    private Short dayOfWeek;

    private Boolean isRoundAmount;

    private Boolean isHighRiskType;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String featureVector;

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Transaction getTransaction() { return transaction; }
    public void setTransaction(Transaction transaction) { this.transaction = transaction; }

    public Double getAmountZscore() { return amountZscore; }
    public void setAmountZscore(Double amountZscore) { this.amountZscore = amountZscore; }

    public Integer getTxVelocity1h() { return txVelocity1h; }
    public void setTxVelocity1h(Integer txVelocity1h) { this.txVelocity1h = txVelocity1h; }

    public Integer getTxVelocity24h() { return txVelocity24h; }
    public void setTxVelocity24h(Integer txVelocity24h) { this.txVelocity24h = txVelocity24h; }

    public BigDecimal getAvgAmount7d() { return avgAmount7d; }
    public void setAvgAmount7d(BigDecimal avgAmount7d) { this.avgAmount7d = avgAmount7d; }

    public Double getAmountRatioToAvg() { return amountRatioToAvg; }
    public void setAmountRatioToAvg(Double amountRatioToAvg) { this.amountRatioToAvg = amountRatioToAvg; }

    public Double getBalanceChangeRatio() { return balanceChangeRatio; }
    public void setBalanceChangeRatio(Double balanceChangeRatio) { this.balanceChangeRatio = balanceChangeRatio; }

    public Boolean getIsNewReceiver() { return isNewReceiver; }
    public void setIsNewReceiver(Boolean isNewReceiver) { this.isNewReceiver = isNewReceiver; }

    public Integer getReceiverDiversity7d() { return receiverDiversity7d; }
    public void setReceiverDiversity7d(Integer receiverDiversity7d) { this.receiverDiversity7d = receiverDiversity7d; }

    public Short getHourOfDay() { return hourOfDay; }
    public void setHourOfDay(Short hourOfDay) { this.hourOfDay = hourOfDay; }

    public Short getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(Short dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public Boolean getIsRoundAmount() { return isRoundAmount; }
    public void setIsRoundAmount(Boolean isRoundAmount) { this.isRoundAmount = isRoundAmount; }

    public Boolean getIsHighRiskType() { return isHighRiskType; }
    public void setIsHighRiskType(Boolean isHighRiskType) { this.isHighRiskType = isHighRiskType; }

    public String getFeatureVector() { return featureVector; }
    public void setFeatureVector(String featureVector) { this.featureVector = featureVector; }
}
