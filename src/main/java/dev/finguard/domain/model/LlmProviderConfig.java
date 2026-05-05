package dev.finguard.domain.model;

import dev.finguard.domain.enums.LlmProvider;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "llm_provider_config")
public class LlmProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LlmProvider provider;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "api_key", length = 500)
    private String apiKey;

    @Column(name = "base_url", length = 200)
    private String baseUrl;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and setters

    public Long getId() { return id; }

    public LlmProvider getProvider() { return provider; }
    public void setProvider(LlmProvider provider) { this.provider = provider; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
