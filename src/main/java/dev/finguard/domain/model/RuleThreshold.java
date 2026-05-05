package dev.finguard.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Persisted current value for a detection rule's threshold.
 *
 * <p>Minimal row — only the parts that users can change. The canonical
 * metadata (industry range, citation, thesis default, description) lives in
 * {@code dev.finguard.detection.rule.RuleMetadata} as code-driven constants
 * because that information is curated research content, not runtime state.</p>
 *
 * <p>Seeded with thesis-calibrated defaults in Liquibase changelog
 * {@code 020-create-rule-thresholds.sql}; see §0.3c of
 * {@code THESIS_EVALUATION_GUIDE.md} for the calibration methodology.</p>
 */
@Entity
@Table(name = "rule_thresholds")
public class RuleThreshold {

    /**
     * Primary key matches {@code RuleMetadata.name()} (enum constant name).
     * A row with {@code rule_name = 'LARGE_TRANSACTION'} corresponds to
     * {@link dev.finguard.detection.rule.RuleMetadata#LARGE_TRANSACTION}.
     */
    @Id
    @Column(name = "rule_name", length = 50, nullable = false)
    private String ruleName;

    /**
     * The currently active threshold value. Stored as NUMERIC(19, 4) so it
     * holds both money-scale decimals (e.g. 1 000 000.00) and small integers
     * (e.g. 3 for RAPID_VELOCITY count). Callers cast via the service.
     */
    @Column(name = "current_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal currentValue;

    /** Updated automatically — useful for audit and thesis reproducibility. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void onChange() {
        this.updatedAt = LocalDateTime.now();
    }

    public RuleThreshold() { }

    public RuleThreshold(String ruleName, BigDecimal currentValue) {
        this.ruleName = ruleName;
        this.currentValue = currentValue;
    }

    public String getRuleName()             { return ruleName; }
    public void setRuleName(String v)       { this.ruleName = v; }

    public BigDecimal getCurrentValue()        { return currentValue; }
    public void setCurrentValue(BigDecimal v)  { this.currentValue = v; }

    public LocalDateTime getUpdatedAt()        { return updatedAt; }
}
