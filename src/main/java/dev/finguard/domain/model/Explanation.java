package dev.finguard.domain.model;

import dev.finguard.domain.enums.ExplanationType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "explanations", indexes = {
    @Index(name = "idx_explanations_type", columnList = "explanationType"),
    @Index(name = "idx_explanations_hallucination", columnList = "hallucinationFree")
})
public class Explanation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id", nullable = false)
    private Alert alert;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private ExplanationType explanationType;

    @Column(columnDefinition = "TEXT")
    private String explanationText;

    @Column(length = 500)
    private String riskSummary;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String suspiciousPatterns;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String recommendedActions;

    private Double confidenceScore;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String hallucinationFlags;

    private Boolean hallucinationFree;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer latencyMs;

    // CAKR evaluation scores (1-5 scale, null if not yet evaluated)
    private Double cakrCompleteness;
    private Double cakrCorrectness;
    private Double cakrActionability;
    private Double cakrRegulatory;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String ragContextIds;

    @Column(columnDefinition = "TEXT")
    private String fullPrompt;

    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Compute the average CAKR score across dimensions that have been evaluated.
     *
     * <p>Audit C-7: prior to this change a single missing dimension caused the
     * method to return {@code null}, discarding the other three successful
     * evaluations. Now we average whatever dimensions are present and only
     * return {@code null} if <em>all four</em> are null (i.e., the explanation
     * has not been CAKR-scored at all).</p>
     */
    public Double getCakrAverage() {
        double sum = 0;
        int count = 0;
        if (cakrCompleteness  != null) { sum += cakrCompleteness;  count++; }
        if (cakrCorrectness   != null) { sum += cakrCorrectness;   count++; }
        if (cakrActionability != null) { sum += cakrActionability; count++; }
        if (cakrRegulatory    != null) { sum += cakrRegulatory;    count++; }
        return count > 0 ? sum / count : null;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Alert getAlert() { return alert; }
    public void setAlert(Alert alert) { this.alert = alert; }

    public ExplanationType getExplanationType() { return explanationType; }
    public void setExplanationType(ExplanationType explanationType) { this.explanationType = explanationType; }

    public String getExplanationText() { return explanationText; }
    public void setExplanationText(String explanationText) { this.explanationText = explanationText; }

    public String getRiskSummary() { return riskSummary; }
    public void setRiskSummary(String riskSummary) { this.riskSummary = riskSummary; }

    public String getSuspiciousPatterns() { return suspiciousPatterns; }
    public void setSuspiciousPatterns(String suspiciousPatterns) { this.suspiciousPatterns = suspiciousPatterns; }

    public String getRecommendedActions() { return recommendedActions; }
    public void setRecommendedActions(String recommendedActions) { this.recommendedActions = recommendedActions; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public String getHallucinationFlags() { return hallucinationFlags; }
    public void setHallucinationFlags(String hallucinationFlags) { this.hallucinationFlags = hallucinationFlags; }

    public Boolean getHallucinationFree() { return hallucinationFree; }
    public void setHallucinationFree(Boolean hallucinationFree) { this.hallucinationFree = hallucinationFree; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }

    public Double getCakrCompleteness() { return cakrCompleteness; }
    public void setCakrCompleteness(Double cakrCompleteness) { this.cakrCompleteness = cakrCompleteness; }

    public Double getCakrCorrectness() { return cakrCorrectness; }
    public void setCakrCorrectness(Double cakrCorrectness) { this.cakrCorrectness = cakrCorrectness; }

    public Double getCakrActionability() { return cakrActionability; }
    public void setCakrActionability(Double cakrActionability) { this.cakrActionability = cakrActionability; }

    public Double getCakrRegulatory() { return cakrRegulatory; }
    public void setCakrRegulatory(Double cakrRegulatory) { this.cakrRegulatory = cakrRegulatory; }

    public String getRagContextIds() { return ragContextIds; }
    public void setRagContextIds(String ragContextIds) { this.ragContextIds = ragContextIds; }

    public String getFullPrompt() { return fullPrompt; }
    public void setFullPrompt(String fullPrompt) { this.fullPrompt = fullPrompt; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
