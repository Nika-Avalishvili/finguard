package dev.finguard.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "fraud_patterns")
public class FraudPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50, nullable = false)
    private String patternType;

    @Column(length = 200, nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String indicators;

    @Column(length = 200)
    private String regulatoryReference;

    @Column(columnDefinition = "TEXT")
    private String exampleScenario;

    // Note: The 'embedding' column (vector(768)) is managed by Spring AI's pgvector store.
    // This entity maps the metadata columns only.

    @Column(length = 100)
    private String source;

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPatternType() { return patternType; }
    public void setPatternType(String patternType) { this.patternType = patternType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIndicators() { return indicators; }
    public void setIndicators(String indicators) { this.indicators = indicators; }

    public String getRegulatoryReference() { return regulatoryReference; }
    public void setRegulatoryReference(String regulatoryReference) { this.regulatoryReference = regulatoryReference; }

    public String getExampleScenario() { return exampleScenario; }
    public void setExampleScenario(String exampleScenario) { this.exampleScenario = exampleScenario; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
