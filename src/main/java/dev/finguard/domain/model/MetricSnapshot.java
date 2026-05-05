package dev.finguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Aggregate counts snapshot (audit D-3).
 *
 * <p>Populated every minute by {@code MetricSnapshotRefresher}. The dashboard
 * reads the latest row via {@code MetricSnapshotRepository} instead of issuing
 * five unbounded {@code COUNT(*)} queries per page-load.</p>
 */
@Entity
@Table(name = "metric_snapshots", indexes = {
        @Index(name = "idx_metric_snapshots_at", columnList = "snapshot_at DESC")
})
public class MetricSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_at", nullable = false)
    private LocalDateTime snapshotAt;

    @Column(name = "total_transactions", nullable = false)
    private long totalTransactions;

    @Column(name = "total_fraud_tx", nullable = false)
    private long totalFraudTx;

    @Column(name = "total_alerts", nullable = false)
    private long totalAlerts;

    @Column(name = "total_explanations", nullable = false)
    private long totalExplanations;

    @Column(name = "total_fraud_patterns", nullable = false)
    private long totalFraudPatterns;

    @Column(name = "total_experiments", nullable = false)
    private long totalExperiments;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getSnapshotAt() { return snapshotAt; }
    public void setSnapshotAt(LocalDateTime snapshotAt) { this.snapshotAt = snapshotAt; }

    public long getTotalTransactions() { return totalTransactions; }
    public void setTotalTransactions(long totalTransactions) { this.totalTransactions = totalTransactions; }

    public long getTotalFraudTx() { return totalFraudTx; }
    public void setTotalFraudTx(long totalFraudTx) { this.totalFraudTx = totalFraudTx; }

    public long getTotalAlerts() { return totalAlerts; }
    public void setTotalAlerts(long totalAlerts) { this.totalAlerts = totalAlerts; }

    public long getTotalExplanations() { return totalExplanations; }
    public void setTotalExplanations(long totalExplanations) { this.totalExplanations = totalExplanations; }

    public long getTotalFraudPatterns() { return totalFraudPatterns; }
    public void setTotalFraudPatterns(long totalFraudPatterns) { this.totalFraudPatterns = totalFraudPatterns; }

    public long getTotalExperiments() { return totalExperiments; }
    public void setTotalExperiments(long totalExperiments) { this.totalExperiments = totalExperiments; }
}
