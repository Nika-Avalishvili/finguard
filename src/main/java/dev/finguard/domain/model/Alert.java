package dev.finguard.domain.model;

import dev.finguard.domain.enums.AlertStatus;
import dev.finguard.domain.enums.DetectionConfig;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "alerts", indexes = {
    @Index(name = "idx_alerts_status", columnList = "status"),
    @Index(name = "idx_alerts_config", columnList = "detectionConfig"),
    @Index(name = "idx_alerts_risk_score", columnList = "mlRiskScore")
})
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(length = 50, nullable = false)
    private DetectionConfig detectionConfig;

    @Column(length = 500)
    private String ruleTriggered;

    private Double mlRiskScore;

    @Column(length = 50)
    private String mlModelName;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String featureImportances;

    @Column(nullable = false)
    private Boolean isAnomaly = false;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AlertStatus status = AlertStatus.NEW;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "alert", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Explanation> explanations = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Transaction getTransaction() { return transaction; }
    public void setTransaction(Transaction transaction) { this.transaction = transaction; }

    public DetectionConfig getDetectionConfig() { return detectionConfig; }
    public void setDetectionConfig(DetectionConfig detectionConfig) { this.detectionConfig = detectionConfig; }

    public String getRuleTriggered() { return ruleTriggered; }
    public void setRuleTriggered(String ruleTriggered) { this.ruleTriggered = ruleTriggered; }

    public Double getMlRiskScore() { return mlRiskScore; }
    public void setMlRiskScore(Double mlRiskScore) { this.mlRiskScore = mlRiskScore; }

    public String getMlModelName() { return mlModelName; }
    public void setMlModelName(String mlModelName) { this.mlModelName = mlModelName; }

    public String getFeatureImportances() { return featureImportances; }
    public void setFeatureImportances(String featureImportances) { this.featureImportances = featureImportances; }

    public Boolean getIsAnomaly() { return isAnomaly; }
    public void setIsAnomaly(Boolean isAnomaly) { this.isAnomaly = isAnomaly; }

    public AlertStatus getStatus() { return status; }
    public void setStatus(AlertStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public List<Explanation> getExplanations() { return explanations; }
    public void setExplanations(List<Explanation> explanations) { this.explanations = explanations; }
}
