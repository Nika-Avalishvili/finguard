package dev.finguard.domain.model;

import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "experiment_results")
public class ExperimentResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100, nullable = false)
    private String experimentName;

    @Enumerated(EnumType.STRING)
    @Column(length = 50, nullable = false)
    private DetectionConfig config;

    @Enumerated(EnumType.STRING)
    @Column(length = 50, nullable = false)
    private DatasetSource dataset;

    private Integer fold;

    private Double precisionScore;
    private Double recallScore;
    @Column(name = "f1_score")
    private Double f1Score;
    private Double aucRoc;
    private Double aucPr;
    private Double falsePositiveRate;
    private Double avgCakrScore;
    private Double hallucinationRate;
    private Double avgLatencyMs;
    private Integer totalTransactions;
    private Integer totalAlerts;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String runParameters;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getExperimentName() { return experimentName; }
    public void setExperimentName(String experimentName) { this.experimentName = experimentName; }

    public DetectionConfig getConfig() { return config; }
    public void setConfig(DetectionConfig config) { this.config = config; }

    public DatasetSource getDataset() { return dataset; }
    public void setDataset(DatasetSource dataset) { this.dataset = dataset; }

    public Integer getFold() { return fold; }
    public void setFold(Integer fold) { this.fold = fold; }

    public Double getPrecisionScore() { return precisionScore; }
    public void setPrecisionScore(Double precisionScore) { this.precisionScore = precisionScore; }

    public Double getRecallScore() { return recallScore; }
    public void setRecallScore(Double recallScore) { this.recallScore = recallScore; }

    public Double getF1Score() { return f1Score; }
    public void setF1Score(Double f1Score) { this.f1Score = f1Score; }

    public Double getAucRoc() { return aucRoc; }
    public void setAucRoc(Double aucRoc) { this.aucRoc = aucRoc; }

    public Double getAucPr() { return aucPr; }
    public void setAucPr(Double aucPr) { this.aucPr = aucPr; }

    public Double getFalsePositiveRate() { return falsePositiveRate; }
    public void setFalsePositiveRate(Double falsePositiveRate) { this.falsePositiveRate = falsePositiveRate; }

    public Double getAvgCakrScore() { return avgCakrScore; }
    public void setAvgCakrScore(Double avgCakrScore) { this.avgCakrScore = avgCakrScore; }

    public Double getHallucinationRate() { return hallucinationRate; }
    public void setHallucinationRate(Double hallucinationRate) { this.hallucinationRate = hallucinationRate; }

    public Double getAvgLatencyMs() { return avgLatencyMs; }
    public void setAvgLatencyMs(Double avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }

    public Integer getTotalTransactions() { return totalTransactions; }
    public void setTotalTransactions(Integer totalTransactions) { this.totalTransactions = totalTransactions; }

    public Integer getTotalAlerts() { return totalAlerts; }
    public void setTotalAlerts(Integer totalAlerts) { this.totalAlerts = totalAlerts; }

    public String getRunParameters() { return runParameters; }
    public void setRunParameters(String runParameters) { this.runParameters = runParameters; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
